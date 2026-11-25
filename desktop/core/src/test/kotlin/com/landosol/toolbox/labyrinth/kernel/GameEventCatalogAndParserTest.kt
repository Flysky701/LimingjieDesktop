package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.kernel.event.*
import com.landosol.toolbox.labyrinth.kernel.event.GameEventEffectPrimitive.*
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventTextRecognizer
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun realEvents(): List<EventResource> {
    val file = File("src/main/resources/assets/resource-packs/cn-bilibili/events.placeholder.json")
    return Json.decodeFromJsonElement(Json.parseToJsonElement(file.readText()).jsonObject.getValue("events"))
}

class GameEventCatalogAndParserTest {
    @Test fun overlayMergesResourcesAndAliasUsesNormalOcrEvidence() {
        val registry = GameEventCatalogRegistry(realEvents())
        assertEquals(22, registry.toEventResources().size)
        assertEquals(4, registry.byFamily("arena").size)
        assertEquals(4, registry.byFamily("guild-recruit").size)
        val base = registry.byId("1107")!!
        registry.addOverlay(GameEventOverlayDocument(events = listOf(GameEventDefinition("1107", tags = setOf("rock")),
            GameEventDefinition("festival", "星光庆典即将开幕", familyId = "holidays",
                choices = listOf(GameEventChoiceSpec("gift", 1, "领取庆典礼物", "阿尔法金币×800")))),
            aliases = mapOf("全新的星光活动" to "festival")))
        assertEquals(base, registry.byId("1107"))
        assertEquals(setOf("rock"), registry.tags("1107"))
        val festival = registry.byFamily("holidays").single()
        assertEquals(festival, registry.resolveRecognitionAlias("全新的星光活动"))
        val match = LabyrinthEventTextRecognizer(realEvents(), catalogRegistry = registry)
            .recognize("全新的星光活动 领取庆典礼物 阿尔法金币×800")
        assertTrue(match.trusted)
        assertEquals(festival, match.event)
    }

    @Test fun chineseEffectsAndUncertainCosts() {
        fun effects(text: String) = GameEventEffectParser.parse(text).primitives
        assertEquals(listOf(AlphaCoin(1500, .25)), effects("２５％时可获得阿尔法金币×１５００"))
        assertEquals(listOf(Relic(1,3)), effects("★１～３迷宫遗物×１"))
        assertEquals(listOf(RoleImprint(RoleImprintScope.SPECIFIC_ROLE,"纯"),
            RoleImprint(RoleImprintScope.ALL_RANDOM,"全体",2,4)), effects("纯成为伙伴 ＋全体随机印记×２～４"))
        listOf("治疗型职能的随机印记×2" to RoleImprintScope.NAMED_CLASS_RANDOM,
            "随机职阶随机印记×1" to RoleImprintScope.RANDOM_CLASS_RANDOM,
            "随机职能的选择印记×1" to RoleImprintScope.RANDOM_CLASS_SELECTABLE,
            "全体自选印记×1" to RoleImprintScope.ALL_SELECTABLE).forEach { (text, scope) ->
            assertEquals(scope, (effects(text).single() as RoleImprint).scope)
        }
        assertEquals(listOf(BattleChallenge(true), AlphaCoin(300)), effects("向战斗格子（极难）的 敌人发起挑战 ＋阿尔法金币×300"))
        assertEquals(listOf(BattleChallenge(false)), effects("普通战斗"))
        assertEquals(listOf(ChainContinue(.5)), effects("有50%的概率进入下一个事件"))
        assertEquals(listOf(ChainStop()), effects("挑战结束"))
        assertTrue(GameEventEffectParser.parse("免费领取α币×200").safePositive)
        assertFalse(GameEventEffectParser.parse("消耗２０００＋金币×100").free)
        assertFalse(GameEventEffectParser.parse("金币×100 附加未知条款").safePositive)
        assertFalse(GameEventEffectParser.parse("金币×100",1).free)
        assertEquals(UnknownGameEventPolicy.WAIT, KernelEventExtensionPack().unknownEventPolicy)
    }
}
