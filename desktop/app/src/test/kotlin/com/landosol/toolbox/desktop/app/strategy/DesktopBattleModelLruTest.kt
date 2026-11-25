package com.landosol.toolbox.desktop.app.strategy

import com.landosol.toolbox.desktop.app.control.ControlModelCodec
import com.landosol.toolbox.desktop.platform.DesktopSettings
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionContext
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueContext
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class DesktopBattleModelLruTest {
    @Test fun pathBSettingRoundTripsAndDefaultsForLegacyJson() {
        assertFalse(DesktopSettings().pathBPlanningEnabled)
        assertEquals("BALANCED", DesktopSettings().battleModelStyle)

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val enabled = DesktopSettings(battleOnnxEnabled = true, pathBPlanningEnabled = true, battleModelStyle = "AGGRESSIVE")
        assertEquals(enabled, json.decodeFromString<DesktopSettings>(json.encodeToString(enabled)))
        assertEquals(enabled, ControlModelCodec.decode<DesktopSettings>(ControlModelCodec.encode(enabled)))

        val legacy = """{"battleOnnxEnabled":true}"""
        val expected = DesktopSettings(battleOnnxEnabled = true)
        assertEquals(expected, json.decodeFromString<DesktopSettings>(legacy))
        assertEquals(expected, ControlModelCodec.decode<DesktopSettings>(json.parseToJsonElement(legacy)))
    }

    @Test fun interleavedContextsReuseTowerAAndEvictTheLeastRecentlyUsedEntry() {
        val context = BattleValueContext(1, 1, 1, emptyList(), LabyrinthRoleDecisionContext(0))
        DesktopBattleModel({ Path.of(".") }, {}).use { owner ->
            fun encode(gold: Int): EncodedBattleContext = owner.encode(context.copy(alphaRupee = gold)) {
                EncodedBattleContext(
                    ContextFeatures(emptyMap(), emptyMap(), emptyList(), 0.0, 0.0),
                    floatArrayOf(), floatArrayOf(), emptyMap(), 1,
                )
            }

            val hot = encode(1)
            val second = encode(2)
            assertSame(hot, encode(1))
            assertEquals(2, owner.towerAInvocations)

            for (gold in 3..16) encode(gold)
            assertSame(second, encode(2))
            assertSame(hot, encode(1))
            assertEquals(16, owner.towerAInvocations)

            encode(17)
            assertSame(hot, encode(1))
            assertEquals(17, owner.towerAInvocations)
            encode(3)
            assertEquals(18, owner.towerAInvocations)
        }
    }

    @Test fun bundledModelResolvesFromAppDirWhenUnsetAndDefersToEnvOverride() {
        val temp = Files.createTempDirectory("bundle-app-dir-")
        try {
            val workerDir = Files.createDirectories(temp.resolve("worker"))
            val appDir = Files.createDirectories(temp.resolve("app"))
            val appModel = Files.createDirectories(appDir.resolve("models/s4_m2b_r1_seed43"))
            Files.writeString(appModel.resolve("manifest.json"), "{}")
            val executable = appDir.resolve("LimingjieAssistant.exe")

            val fromApp = resolveBattleBundleLocation(
                configuredPath = "",
                dataDirectory = workerDir,
                systemProperties = emptyMap(),
                env = emptyMap(),
                defaultResolvedDataDir = temp.resolve("os-default"),
                appPath = executable.toString(),
            )
            assertEquals(BattleBundleSource.APP_DIR, fromApp.source)
            assertEquals(appModel, fromApp.path)

            val envDir = temp.resolve("override")
            val fromEnv = resolveBattleBundleLocation(
                configuredPath = "",
                dataDirectory = workerDir,
                systemProperties = emptyMap(),
                env = mapOf("LIMINGJIE_NN_BUNDLE" to envDir.toString()),
                defaultResolvedDataDir = temp.resolve("os-default"),
                appPath = executable.toString(),
            )
            assertEquals(BattleBundleSource.ENV_VAR, fromEnv.source)
            assertEquals(envDir, fromEnv.path)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}
