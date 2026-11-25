package com.landosol.toolbox.gamedata

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourcePackValidatorTest {
    @Test
    fun `accepts complete pack with matching hash`() {
        val root = Files.createTempDirectory("resource-pack").toFile()
        try {
            val characters = File(root, "characters.placeholder.json").apply { writeText("{}") }
            val manifest = ResourcePackManifest(
                packId = "cn-bilibili",
                version = "fixture-1",
                files = listOf(ResourcePackFile(characters.name, sha256(characters))),
            )

            assertTrue(ResourcePackValidator().validate(root, manifest) is ResourcePackValidation.Valid)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects traversal and missing required files`() {
        val root = Files.createTempDirectory("resource-pack").toFile()
        try {
            val result = ResourcePackValidator().validate(
                root,
                ResourcePackManifest(
                    packId = "cn-bilibili",
                    version = "fixture-1",
                    files = listOf(ResourcePackFile("../outside.json")),
                ),
            )

            assertTrue(result is ResourcePackValidation.Invalid)
            assertTrue((result as ResourcePackValidation.Invalid).reasons.any { it.contains("不安全") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects absolute windows paths and dot segments`() {
        val root = Files.createTempDirectory("resource-pack-paths").toFile()
        try {
            val result = ResourcePackValidator().validate(
                root,
                ResourcePackManifest(
                    packId = "cn-bilibili",
                    version = "fixture-1",
                    files = listOf(
                        ResourcePackFile("C:\\outside.json"),
                        ResourcePackFile("data/./characters.json"),
                    ),
                ),
            )

            assertTrue(result is ResourcePackValidation.Invalid)
            assertTrue((result as ResourcePackValidation.Invalid).reasons.any { it.contains("不安全") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `loads placeholder shaped documents and validates ids`() {
        val root = Files.createTempDirectory("resource-pack-loader").toFile()
        try {
            File(root, "manifest.json").writeText(
                """
                {"packId":"cn-bilibili","version":"fixture-1","files":[
                  "characters.placeholder.json","relics.placeholder.json",
                  "bosses.placeholder.json","events.placeholder.json"]}
                """.trimIndent(),
            )
            File(root, "characters.placeholder.json").writeText(
                """{"schemaVersion":1,"characters":[{"id":"c1","name":"测试","aliases":["Test"],"available":false,"rarity":5}]}""",
            )
            File(root, "relics.placeholder.json").writeText("""{"schemaVersion":1,"relics":[]}""")
            File(root, "bosses.placeholder.json").writeText("""{"schemaVersion":1,"bosses":[]}""")
            File(root, "events.placeholder.json").writeText("""{"schemaVersion":1,"events":[]}""")

            val result = ResourcePackLoader().load(root)

            assertTrue(result is ResourcePackLoadResult.Success)
            val character = (result as ResourcePackLoadResult.Success).data.characters.single()
            assertEquals("c1", character.id)
            assertEquals(listOf("Test"), character.aliases)
            assertTrue(!character.available)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `loads bundled LandosolRoster character catalog`() {
        val bundled = listOf(
            File("src/main/resources/assets/resource-packs/cn-bilibili"),
            File("desktop/core/src/main/resources/assets/resource-packs/cn-bilibili"),
        ).firstOrNull(File::isDirectory)
        assertTrue("bundled resource pack is missing", bundled != null)

        val root = Files.createTempDirectory("bundled-resource-pack").toFile()
        try {
            bundled!!.copyRecursively(root, overwrite = true)
            val result = ResourcePackLoader().load(root)

            assertTrue("bundled pack failed: $result", result is ResourcePackLoadResult.Success)
            val loaded = result as ResourcePackLoadResult.Success
            val characters = loaded.data.characters
            assertTrue("expected the imported roster to be present", characters.size > 300)
            assertEquals("1072", characters.first { it.id == "1072" }.id)
            assertTrue(!characters.first { it.id == "1072" }.available)
            assertEquals("涅妃＝涅菈", characters.first { it.id == "1297" }.officialName)
            assertEquals("涅妃＝涅菈", characters.first { it.id == "1297" }.displayName)
            assertEquals("镜华(哥德)", characters.first { it.id == "1390" }.name)
            assertEquals("布武机(夏日)", characters.first { it.id == "1401" }.name)
            assertEquals("斑比(夏日)", characters.first { it.id == "1402" }.name)
            assertTrue(characters.any { it.aliases.contains("Hiyori") })
            val relic = loaded.data.relics.first { it.id == "11001" }
            assertEquals("加速", relic.mark)
            assertTrue(relic.effect.isNotBlank())
            val workbook = loaded.data.workbook
            assertTrue(workbook != null)
            assertEquals(10, workbook!!.tables.size)
            assertTrue("攻略" in workbook.excludedSheets)
            val eventOcr = loaded.data.eventOcr
            assertTrue(eventOcr != null)
            assertEquals("选择", eventOcr!!.strategy.selectButtonLabel)
            assertEquals(3, eventOcr.layouts.size)
            assertEquals(41, loaded.data.resolvedEventSignatures.size)
            val tankEvent = loaded.data.resolvedEventSignatures.first { it.sourceCell == "B1" }
            assertTrue(tankEvent.displayText.contains("タンク"))
            assertTrue(tankEvent.translatedEffect.contains("掩护者职阶角色随机"))
            val icons = loaded.data.icons
            assertTrue(icons != null)
            assertEquals(800, icons!!.characterIcons.size)
            assertEquals(369, icons.coverage.coveredCharacters)
            assertEquals(109, icons.relicIcons.size)
            assertEquals(109, icons.coverage.coveredRelics)
            val iconCatalog = GameIconCatalog(icons)
            assertTrue(
                iconCatalog.characterIcons("1001").map(GameIconAsset::variant)
                    .containsAll(listOf("11", "31", "61")),
            )
            assertTrue(iconCatalog.characterIcons("1401").map(GameIconAsset::variant).containsAll(listOf("11", "31")))
            assertTrue(iconCatalog.characterIcons("1402").map(GameIconAsset::variant).containsAll(listOf("11", "31")))
            assertEquals("icons/relics/11001.png", iconCatalog.relicIcon("11001")?.file)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects unsafe game icon paths`() {
        val root = Files.createTempDirectory("game-icon-pack").toFile()
        try {
            File(root, "icons.json").writeText(
                """
                {
                  "schemaVersion": 1,
                  "coverage": {"catalogCharacters":1,"coveredCharacters":1,"characterIconFiles":1},
                  "characterIcons": [{
                    "ownerId":"c1","variant":"31","file":"../outside.png",
                    "format":"png","width":128,"height":128,"sha256":"invalid"
                  }]
                }
                """.trimIndent(),
            )

            val result = GameIconPackLoader().load(root, setOf("c1"), emptySet())

            assertTrue(result is GameIconPackLoadResult.Failure)
            assertTrue((result as GameIconPackLoadResult.Failure).reasons.any { it.contains("不安全") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects duplicate ids and invalid ranges`() {
        val data = ResourcePackData(
            manifest = ResourcePackDocument("cn-bilibili", "fixture-1"),
            characters = listOf(
                CharacterResource(id = "c1", rarity = 8),
                CharacterResource(id = "c1"),
            ),
            relics = listOf(RelicResource(id = "r1", price = -1)),
            bosses = listOf(
                BossResource(
                    id = "b1",
                    area = 0,
                    references = ResourceReferences(characterIds = listOf("missing-character")),
                ),
            ),
            events = emptyList(),
        )

        val reasons = ResourcePackDataValidator().validate(data)

        assertTrue(reasons.any { it.contains("重复 ID") })
        assertTrue(reasons.any { it.contains("稀有度") })
        assertTrue(reasons.any { it.contains("价格") })
        assertTrue(reasons.any { it.contains("区域") })
        assertTrue(reasons.any { it.contains("不存在的角色 ID") })
    }

    @Test
    fun `rejects ambiguous category files`() {
        val root = Files.createTempDirectory("resource-pack-duplicates").toFile()
        try {
            val files = listOf(
                "characters.one.json",
                "characters.two.json",
                "relics.placeholder.json",
                "bosses.placeholder.json",
                "events.placeholder.json",
            )
            File(root, "manifest.json").writeText(
                """{"packId":"cn-bilibili","version":"fixture-1","files":[${files.joinToString { "\"$it\"" }}]}""",
            )
            files.forEach { file ->
                val category = file.substringBefore('.')
                File(root, file).writeText("""{"schemaVersion":1,"$category":[]}""")
            }

            val result = ResourcePackLoader().load(root)

            assertTrue(result is ResourcePackLoadResult.Failure)
            assertTrue((result as ResourcePackLoadResult.Failure).reasons.any { it.contains("类别文件重复") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects blank and duplicate character aliases`() {
        val data = ResourcePackData(
            manifest = ResourcePackDocument("cn-bilibili", "fixture-1"),
            characters = listOf(
                CharacterResource(id = "c1", name = "角色甲", aliases = listOf("角色甲", "")),
            ),
            relics = emptyList(),
            bosses = emptyList(),
            events = emptyList(),
        )

        val reasons = ResourcePackDataValidator().validate(data)

        assertTrue(reasons.any { it.contains("空别名") })
        assertTrue(reasons.any { it.contains("重复名称或别名") })
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte) }
}
