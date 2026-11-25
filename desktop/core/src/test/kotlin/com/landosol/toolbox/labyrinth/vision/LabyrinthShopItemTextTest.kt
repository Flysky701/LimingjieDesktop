package com.landosol.toolbox.labyrinth.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthShopItemTextTest {
    @Test fun `complete known imprint title tolerates one OCR substitution`() {
        assertEquals("增益型职能印记", LabyrinthShopItemText.roleImprintLabel("增益型识能的随机印记"))
        assertEquals("治疗型职能印记", LabyrinthShopItemText.roleImprintLabel("治疗型职熊的选择印记"))
        assertFalse(LabyrinthShopItemText.looksLikeRoleImprint("增益型识熊的随机印记"))
        assertFalse(LabyrinthShopItemText.looksLikeRoleImprint("未知型识能的随机印记"))
        assertFalse(LabyrinthShopItemText.looksLikeRoleImprint("增益型识能的随机"))
    }
    @Test
    fun `shop role imprint titles are separated from relics`() {
        assertEquals(
            "坦克型职能印记",
            LabyrinthShopItemText.roleImprintLabel("坦克型职能的选择印记"),
        )
        assertEquals(
            "破防型职能印记",
            LabyrinthShopItemText.roleImprintLabel("破防型职能的随机印记"),
        )
        assertEquals(
            "增益型职能印记",
            LabyrinthShopItemText.roleImprintLabel("增益型 职能的 随机印记"),
        )
        assertEquals(
            "治疗型职能印记",
            LabyrinthShopItemText.roleImprintLabel("治疗型职能的随机印记"),
        )
        assertEquals(
            "治疗型职能印记",
            LabyrinthShopItemText.roleImprintLabel("治疗型职能的随机印紀"),
        )
        assertEquals(
            "治疗型职能印记",
            LabyrinthShopItemText.roleImprintLabel("治疗型职能的随机"),
        )
    }

    @Test
    fun `ordinary relic names are not role imprints`() {
        assertNull(LabyrinthShopItemText.roleImprintLabel("防守铠甲"))
        assertNull(LabyrinthShopItemText.roleImprintLabel("流烈耳饰"))
        assertNull(LabyrinthShopItemText.roleImprintLabel("终焉邪眼杖"))
        assertFalse(LabyrinthShopItemText.looksLikeRoleImprint("终焉邪眼杖"))
    }

    @Test
    fun `coarse imprint gate can reject relic hypothesis without guessing exact role`() {
        assertTrue(LabyrinthShopItemText.looksLikeRoleImprint("干扰型职能的随机印记"))
        assertTrue(LabyrinthShopItemText.looksLikeRoleImprint("职能的随机印記"))
        assertTrue(LabyrinthShopItemText.looksLikeRoleImprint("治疗型职能的随机"))
        assertNull(LabyrinthShopItemText.roleImprintLabel("职能的随机印記"))
    }

    @Test fun `selection imprints are told apart from random ones`() {
        // 2026-09-20 bundle 155513: both kinds were on sale in one stock cycle. Only the 选择
        // variant opens a roster picker after purchase, so the run has to know which it bought.
        assertTrue(LabyrinthShopItemText.isChoiceRoleImprint("攻击型职能的选择印记"))
        assertTrue(LabyrinthShopItemText.isChoiceRoleImprint("强化型职能的选择印记"))
        assertFalse(LabyrinthShopItemText.isChoiceRoleImprint("攻击型职能的随机印记"))
        assertFalse(LabyrinthShopItemText.isChoiceRoleImprint("干扰型职能的随机印记"))
        // Live OCR of these titles, exactly as the bundle recorded them.
        assertTrue(LabyrinthShopItemText.isChoiceRoleImprint("强化型职能的选择记"))
        assertFalse(LabyrinthShopItemText.isChoiceRoleImprint("增益型识能的随机印记"))
        // Relic titles are never imprints of either kind.
        assertFalse(LabyrinthShopItemText.isChoiceRoleImprint("苍珠手镯"))
        assertFalse(LabyrinthShopItemText.isChoiceRoleImprint(null))
    }
}
