package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.labyrinth.LabyrinthShopDecision
import com.landosol.toolbox.labyrinth.LabyrinthShopPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.landosol.toolbox.clanbattle.recognition.PixelImage
import kotlinx.serialization.json.*

class LabyrinthShopCategoryResolutionTest {
    @Test fun `logged screenshot and OCR readings classify all three imprints against packaged relic catalog`() {
        val root = generateSequence(File(".").canonicalFile, File::getParentFile)
            .first { File(it, "android/app/src/test/resources/shop/imprints-20260914.jpg").isFile }
        val resources = File(root, "android/app/src/main/assets/resource-packs/cn-bilibili")
        val templates = File(resources, "icons/relics").listFiles()!!.filter { it.extension == "png" }.map {
            LabyrinthRelicTemplate(it.nameWithoutExtension, it.nameWithoutExtension, "弱体", "1", "", readImage(it))
        }
        val raw = LabyrinthShopRecognizer(readImage(File(resources, "vision/shop/shop_buy_button.png")), templates)
            .recognize(readImage(File(root, "android/app/src/test/resources/shop/imprints-20260914.jpg")))
        val log = Json.parseToJsonElement(File(root, "android/app/src/test/resources/shop/imprints-20260914.json").readText()).jsonObject
        val titles = log.getValue("items").jsonArray.map { it.jsonObject.getValue("titleText").jsonPrimitive.content }
        assertEquals(3, raw.items.size)
        assertTrue(raw.items.all { it.purchasable })
        val classified = raw.items.mapIndexed { i, item -> resolver.resolve(item, titles[i], i.toLong(), 0) }
        assertTrue(classified.all { it.kind == LabyrinthShopItemKind.ROLE_IMPRINT && it.relicMatch == null })
        assertEquals("增益型职能印记", classified[2].roleImprintLabel)
        assertTrue(decide(classified) is LabyrinthShopDecision.BuyRoleImprint)
    }

    private fun readImage(file: File): PixelImage {
        val image = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file)
        val width = image.javaClass.getMethod("getWidth").invoke(image) as Int
        val height = image.javaClass.getMethod("getHeight").invoke(image) as Int
        val pixels = IntArray(width * height)
        image.javaClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(image, 0, 0, width, height, pixels, 0, width)
        return PixelImage(width, height, pixels)
    }
    private val resolver = LabyrinthShopCategoryResolution()
    private val rect = EntryPixelRect(0, 0, 100, 100)
    private fun item(id: String = "3", recognized: Boolean = false) = LabyrinthShopItemMatch(
        id, rect, rect, 0.99, 1.0, LabyrinthShopItemStatus.AVAILABLE,
        relicMatch = LabyrinthRelicMatch(slotId = id, relicId = if (recognized) "40005" else null,
            displayName = "候选遗物", attribute = "弱体", attributeBonus = "1", effect = null,
            confidence = 0.715, screenRect = rect, iconRect = rect, rivalMargin = 0.000338),
    )
    private fun decide(items: List<LabyrinthShopItemMatch>, area: Int = 3, purchased: Int = 0) =
        LabyrinthShopPolicy().decide(area, items, emptyList(), refreshAvailable = true, relicPurchasesInCycle = purchased)

    @Test fun `title classification overrides even a recognized relic icon`() {
        val result = resolver.resolve(item(recognized = true), "增益型识能的随机印记", 22, 0)
        assertEquals(LabyrinthShopItemKind.ROLE_IMPRINT, result.kind)
        assertEquals("增益型职能印记", result.roleImprintLabel)
        assertNull(result.relicMatch)
    }

    @Test fun `relic cannot be bought until its title was checked`() {
        assertTrue(decide(listOf(resolver.resolve(item(recognized = true), null, null, 0))) is LabyrinthShopDecision.Wait)
        assertTrue(decide(listOf(resolver.resolve(item(recognized = true), "候选遗物", 1, 0))) is LabyrinthShopDecision.BuyRelic)
    }

    @Test fun `variant advances on completed OCR, not on elapsed time`() {
        // A pending read (completed = false) never advances the variant, however long it waits,
        // as long as the engine is answering other slots: this is the bundle-134750 bug, where a
        // 3 s clock retired titles whose OCR was still queued and closed the shop after one buy.
        assertEquals(0, resolver.attempt("3", 100, 0))
        resolver.noteVariantOutcome("3", 100, 0, false, engineLastCompletedAt = 2_500, now = 3_000)
        assertEquals(0, resolver.attempt("3", 100, 3_000))
        // Third slot in a serial queue waits ~16 s for its turn on the reporting device; engine
        // progress in that window must keep it waiting rather than burn a crop variant.
        resolver.noteVariantOutcome("3", 100, 0, false, engineLastCompletedAt = 14_000, now = 16_000)
        assertEquals(0, resolver.attempt("3", 100, 16_000))
        // A completed-but-unresolved read retires the variant.
        resolver.noteVariantOutcome("3", 100, 0, true, engineLastCompletedAt = 16_500, now = 16_500)
        assertEquals(1, resolver.attempt("3", 100, 16_600))
        resolver.noteVariantOutcome("3", 100, 1, true, engineLastCompletedAt = 17_000, now = 17_000)
        assertEquals(2, resolver.attempt("3", 100, 17_100))
        resolver.noteVariantOutcome("3", 100, 2, true, engineLastCompletedAt = 17_500, now = 17_500)
        assertEquals(3, resolver.attempt("3", 100, 17_600))
        // A stale variant index cannot double-advance, and EXHAUSTED never advances further.
        resolver.noteVariantOutcome("3", 100, 1, true, engineLastCompletedAt = 17_700, now = 17_700)
        assertEquals(3, resolver.attempt("3", 100, 17_800))
        // A new fingerprint and clear() both reset the counter.
        assertEquals(0, resolver.attempt("3", 101, 18_000))
        resolver.clear()
        assertEquals(0, resolver.attempt("3", 101, 18_001))
    }

    @Test fun `only a silent OCR engine trips the backstop so the shop can never hang`() {
        assertEquals(0, resolver.attempt("s", 7, 0))
        // Engine still answering other slots: no advance, no matter how long this one waits.
        resolver.noteVariantOutcome("s", 7, 0, false, engineLastCompletedAt = 30_000, now = 45_000)
        assertEquals(0, resolver.attempt("s", 7, 45_000))
        // Engine silent since 30_000: one tick short of the backstop still waits.
        resolver.noteVariantOutcome("s", 7, 0, false, engineLastCompletedAt = 30_000, now = 49_999)
        assertEquals(0, resolver.attempt("s", 7, 49_999))
        resolver.noteVariantOutcome("s", 7, 0, false, engineLastCompletedAt = 30_000, now = 50_000)
        assertEquals(1, resolver.attempt("s", 7, 50_000))
    }

    @Test fun `three completed unresolved reads skip item without assuming exhausted relic stock`() {
        val imprint = resolver.resolve(item("1"), "攻击型职能的随机印记", 1, 0)
        assertTrue(decide(listOf(imprint, resolver.resolve(item(), "看不清", 2, 0))) is LabyrinthShopDecision.Wait)
        val skipped = resolver.resolve(item(), "看不清", 4, 3)
        assertTrue(decide(listOf(imprint, skipped), area = 5) is LabyrinthShopDecision.BuyRoleImprint)
        assertTrue(decide(listOf(skipped), area = 5) is LabyrinthShopDecision.Close)
    }

    /**
     * 2026-11-09: a shop title read as "职能印记" with no role name. That is a READY imprint, so
     * the policy used to end the visit; the recovery window below is what keeps the remaining crop
     * variants (and therefore the purchase) alive without letting a run park forever.
     */
    @Test fun `a nameless imprint keeps a bounded grace window for the remaining crops`() {
        val nameless = resolver.resolve(item(), "职能印记", 1, 0)
        assertEquals(LabyrinthShopItemKind.ROLE_IMPRINT, nameless.kind)
        assertNull(nameless.roleImprintLabel)
        assertTrue(resolver.needsRoleName(nameless))

        assertTrue(resolver.labelGraceOpen("3", 1_000))
        // The window starts on the first nameless read, so later frames do not extend it.
        assertTrue(resolver.labelGraceOpen("3", 12_999))
        assertFalse(resolver.labelGraceOpen("3", 13_000))
        // A named imprint never asks for a retry.
        assertFalse(resolver.needsRoleName(resolver.resolve(item(), "增益型职能的随机印记", 2, 0)))
        // A new shop visit restarts the window.
        resolver.clear()
        assertTrue(resolver.labelGraceOpen("3", 20_000))
    }

    @Test fun `remaining titles finish bounded reading after third relic purchase`() {
        assertTrue(decide(listOf(resolver.resolve(item(), null, null, 0)), purchased = 3) is LabyrinthShopDecision.Wait)
        assertTrue(decide(listOf(resolver.resolve(item(), null, null, 3)), purchased = 3) is LabyrinthShopDecision.Close)
    }

    @Test fun `three imprints recover stock and follow area refresh rules`() {
        val items = listOf("攻击型职能的随机印记", "治疗型职能的随机印记", "增益型识能的随机印记")
            .mapIndexed { i, title -> resolver.resolve(item(i.toString()), title, i.toLong(), 0) }
        assertTrue(decide(items, 3) is LabyrinthShopDecision.BuyRoleImprint)
        assertTrue(decide(items, 5) is LabyrinthShopDecision.Refresh)
    }

    /**
     * 2026-09-22 shop: two 坦克型职能的随机印记 at 780 beside a 破防型职能的选择印记 at 1,820.
     * Both grant a character of the named class, but the choice variant costs more than twice as
     * much and opens a full-roster picker the run then has to answer, so the random one wins.
     */
    @Test fun `a random imprint is preferred over the pricier choice imprint`() {
        val choice = resolver.resolve(item("1"), "破防型职能的选择印记", 1, 0)
        val random = resolver.resolve(item("2"), "坦克型职能的随机印记", 2, 0)
        assertTrue(choice.choiceRoleImprint)
        assertFalse(random.choiceRoleImprint)

        // Listed choice-first, the random one must still be the purchase.
        val preferred = decide(listOf(choice, random), area = 5) as LabyrinthShopDecision.BuyRoleImprint
        assertEquals("2", preferred.item.slotId)
        assertFalse(preferred.opensRolePicker)

        // 2026-11-01 rule: inside areas 3/5 a role-specific 选择 imprint is skipped outright
        // (随机 780 against 选择 1,820 for the same class), so a lone choice imprint ends the visit.
        assertTrue("area 5 must skip a lone choice imprint",
            decide(listOf(choice), area = 5) is LabyrinthShopDecision.Close)
        // Areas without the imprint gate keep the old fallback behaviour.
        val fallback = decide(listOf(choice), area = 2) as LabyrinthShopDecision.BuyRoleImprint
        assertEquals("1", fallback.item.slotId)
        assertTrue(fallback.opensRolePicker)

        // Among equals the leftmost slot keeps the pick deterministic.
        val second = resolver.resolve(item("3"), "坦克型职能的随机印记", 3, 0)
        val tie = decide(listOf(second, random), area = 5) as LabyrinthShopDecision.BuyRoleImprint
        assertEquals("2", tie.item.slotId)
    }
}
