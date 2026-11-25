package com.landosol.toolbox.desktop.app.strategy

import com.landosol.toolbox.desktop.app.control.ControlModelCodec
import com.landosol.toolbox.desktop.platform.DesktopSettings
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionContext
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueContext
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

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val enabled = DesktopSettings(battleOnnxEnabled = true, pathBPlanningEnabled = true)
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
}
