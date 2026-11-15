package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.gamedata.EventChoiceResource
import com.landosol.toolbox.gamedata.EventResource
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthArenaEvent
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEventChoiceVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EventChainCustomDecisionTest {
    private val monster = EventResource(id = "1112", name = "活动会场出现了魔物！", area = 1, choices = listOf(
        EventChoiceResource(id = "11121", name = "见识下御久间流的 剑术吧！", slot = 1),
        EventChoiceResource(id = "11122", name = "这里就 交给我们吧！", slot = 2),
    ))
    private val cooking = EventResource(id = "2315", name = "料理完成了！不过食材还有剩余哦！", area = 2, choices = listOf(
        EventChoiceResource(id = "23152", name = "这样应该就 足够了吧？", slot = 1),
        EventChoiceResource(id = "23151", name = "我还没 吃够呢☆", slot = 2),
    ))

    /** Throws at the expensive boundary instead of relying on device-dependent milliseconds. */
    private class ProfileEnumerationProbe(
        private val throwOnEnumeration: Boolean = false,
        private val presentIds: Set<String> = emptySet(),
    ) : Map<String, LabyrinthRoleProfile> by emptyMap() {
        var enumerationCount = 0
            private set
        private fun touched() {
            enumerationCount++
            check(!throwOnEnumeration) { "Reward estimation enumerated role profiles" }
        }
        override val values: Collection<LabyrinthRoleProfile>
            get() { touched(); return emptyList() }
        override val entries: Set<Map.Entry<String, LabyrinthRoleProfile>>
            get() { touched(); return emptySet() }
        override val keys: Set<String>
            get() { touched(); return presentIds }
        override fun containsKey(key: String): Boolean = key in presentIds
    }

    private fun planner(
        settings: LabyrinthStrategySettings = LabyrinthStrategySettings(),
        profiles: Map<String, LabyrinthRoleProfile> = emptyMap(),
    ) = LabyrinthEventChoicePlanner(profiles, LabyrinthTeamOptimizer(LabyrinthTeamScorer(
        LabyrinthTeamScoringConfig(attributeDamageBonus = (1..5).associateWith { 0.0 }),
    )), settings)

    private fun configured(event: EventResource, slot: Int) = LabyrinthStrategySettings(
        eventCustomizationEnabled = true,
        eventChoiceOverrides = mapOf(event.name to event.choices.single { it.slot == slot }.name),
    )

    private fun observation(event: EventResource, trusted: Boolean = true, stable: Int = 2) =
        LabyrinthEventChoiceObservation(event, event.choices.map { choice ->
            LabyrinthEventChoiceVisual(choice, EntryPixelRect(choice.slot * 200, 500, 180, 100), 0.8, 0.8)
        }, event.name, 1.0, 0.4, stable, trusted)

    private fun decide(
        planner: LabyrinthEventChoicePlanner,
        observed: LabyrinthEventChoiceObservation,
        rejected: Set<String> = emptySet(),
    ) = planner.decide(observed, emptySet(), LabyrinthRoleDecisionContext(0), emptyMap(), rejected)

    private fun enabled(observed: LabyrinthEventChoiceObservation, choiceId: String, value: Double) =
        observed.copy(choices = observed.choices.map {
            if (it.choice.id == choiceId) it.copy(enabledConfidence = value) else it
        })

    private fun assertCustomWithoutEnumeration(event: EventResource, slot: Int) {
        val probe = ProfileEnumerationProbe(throwOnEnumeration = true)
        val observed = observation(event)
        val result = decide(planner(configured(event, slot), probe), observed) as LabyrinthEventChoiceDecision.Select
        val choice = event.choices.single { it.slot == slot }
        assertEquals(choice.id, result.choiceId)
        assertEquals(choice.name, result.label)
        assertEquals(observed.choices.single { it.choice.id == choice.id }.buttonRect, result.buttonRect)
        assertTrue(result.actionSafe)
        assertFalse(result.utilityEstimated)
        assertEquals(0.0, result.utility, 0.0)
        assertTrue(result.explanation.contains("按自定义选择"))
        assertTrue(result.explanation.contains("未做自动收益估值"))
        assertEquals(0, probe.enumerationCount)
    }

    @Test fun `1112 custom left avoids profile enumeration`() = assertCustomWithoutEnumeration(monster, 1)
    @Test fun `1112 custom right overrides higher coin utility without estimation`() = assertCustomWithoutEnumeration(monster, 2)
    @Test fun `2315 custom left avoids profile enumeration`() = assertCustomWithoutEnumeration(cooking, 1)
    @Test fun `2315 custom right avoids continuation estimation`() = assertCustomWithoutEnumeration(cooking, 2)

    @Test fun `disabled customization keeps full automatic result despite saved override`() {
        val probe = ProfileEnumerationProbe()
        val observed = observation(monster)
        val result = decide(planner(configured(monster, 2).copy(eventCustomizationEnabled = false), probe), observed)
            as LabyrinthEventChoiceDecision.Select
        assertEquals(decide(planner(), observed), result)
        assertEquals("11121", result.choiceId)
        assertTrue(result.utilityEstimated)
        assertTrue(probe.enumerationCount > 0)
    }

    @Test fun `invalid override falls back to unchanged automatic cooking right winner`() {
        val observed = observation(cooking)
        val settings = configured(cooking, 1).copy(eventChoiceOverrides = mapOf(cooking.name to "无效分支"))
        val result = decide(planner(settings), observed) as LabyrinthEventChoiceDecision.Select
        assertEquals(decide(planner(), observed), result)
        assertEquals("23151", result.choiceId)
        assertTrue(result.utility > 0.0)
        assertTrue(result.utilityEstimated)
        assertEquals("继续料理：50%进入第3段；失败500α币", result.explanation)
    }

    @Test fun `disabled configured button falls back by utility not by configured slot`() {
        val probe = ProfileEnumerationProbe()
        val observed = enabled(observation(monster), "11122", 0.34)
        val result = decide(planner(configured(monster, 2), probe), observed) as LabyrinthEventChoiceDecision.Select
        assertEquals(decide(planner(), observed), result)
        assertEquals("11121", result.choiceId)
        assertTrue(result.actionSafe)
        assertTrue(result.utilityEstimated)
        assertTrue(probe.enumerationCount > 0)
    }

    @Test fun `rejected configured branch falls back to automatic remaining choice`() {
        val observed = observation(monster)
        val rejected = setOf("11122")
        val result = decide(planner(configured(monster, 2)), observed, rejected) as LabyrinthEventChoiceDecision.Select
        assertEquals(decide(planner(), observed, rejected), result)
        assertEquals("11121", result.choiceId)
        assertTrue(result.actionSafe)
        assertTrue(result.utilityEstimated)
    }

    @Test fun `all rejected choices wait without estimating anything`() {
        val probe = ProfileEnumerationProbe(throwOnEnumeration = true)
        val result = decide(planner(configured(monster, 2), probe), observation(monster), monster.choices.map { it.id }.toSet())
        assertTrue(result is LabyrinthEventChoiceDecision.Wait)
        assertEquals(0, probe.enumerationCount)
    }

    @Test fun `bright blue threshold rather than layout blue controls custom preference`() {
        val settings = configured(monster, 2)
        val boundary = enabled(observation(monster), "11122", 0.35)
        val result = decide(planner(settings, ProfileEnumerationProbe(true)), boundary) as LabyrinthEventChoiceDecision.Select
        assertEquals("11122", result.choiceId)
        assertFalse(result.utilityEstimated)
        assertTrue(result.actionSafe)
        for (value in listOf(0.34, 0.0, Double.NaN)) {
            val fallback = decide(planner(settings), enabled(boundary, "11122", value)) as LabyrinthEventChoiceDecision.Select
            assertEquals("11121", fallback.choiceId)
            assertTrue(fallback.utilityEstimated)
        }
    }

    @Test fun `custom candidate is visible but never actionable before repeated trust`() {
        val p = planner(configured(cooking, 1), ProfileEnumerationProbe(true))
        for (observed in listOf(observation(cooking, trusted = false), observation(cooking, stable = 1))) {
            val result = decide(p, observed) as LabyrinthEventChoiceDecision.Select
            assertEquals("23152", result.choiceId)
            assertFalse(result.actionSafe)
            assertFalse(result.utilityEstimated)
            assertTrue(result.safetyNote.orEmpty().contains("稳定"))
        }
    }

    @Test fun `missing or mismatched custom visual cannot become a safe preferred choice`() {
        val observed = observation(monster)
        val malformed = listOf(
            observed.copy(choices = observed.choices.filterNot { it.choice.id == "11122" }),
            observed.copy(choices = observed.choices.map {
                if (it.choice.id == "11122") it.copy(choice = it.choice.copy(name = "错误分支")) else it
            }),
            observed.copy(choices = observed.choices.map {
                if (it.choice.id == "11122") it.copy(choice = it.choice.copy(id = "未知选项")) else it
            }),
        )
        for (input in malformed) {
            val result = decide(planner(configured(monster, 2)), input) as LabyrinthEventChoiceDecision.Select
            assertEquals("11121", result.choiceId)
            assertTrue(result.actionSafe)
            assertTrue(result.utilityEstimated)
        }
    }

    @Test fun `duplicate visual is rejected as override and automatic fallback remains valid`() {
        val observed = observation(monster)
        val duplicate = observed.copy(choices = observed.choices + observed.choices[1])
        val result = decide(planner(configured(monster, 2)), duplicate) as LabyrinthEventChoiceDecision.Select
        assertEquals("11121", result.choiceId)
        assertTrue(result.actionSafe)
        assertTrue(result.utilityEstimated)
    }

    @Test fun `automatic cached utilities still react to enabled and rejected changes`() {
        val p = planner()
        val observed = observation(monster)
        val first = decide(p, observed) as LabyrinthEventChoiceDecision.Select
        assertEquals("11121", first.choiceId)
        val disabledLeft = decide(p, enabled(observed, "11121", 0.0)) as LabyrinthEventChoiceDecision.Select
        assertEquals("11122", disabledLeft.choiceId)
        assertTrue(disabledLeft.actionSafe)
        val rejectedLeft = decide(p, observed, setOf("11121")) as LabyrinthEventChoiceDecision.Select
        assertEquals("11122", rejectedLeft.choiceId)
        assertTrue(rejectedLeft.actionSafe)
        val noUsable = decide(p, enabled(observed, "11121", 0.0), setOf("11122")) as LabyrinthEventChoiceDecision.Select
        assertEquals("11121", noUsable.choiceId)
        assertFalse(noUsable.actionSafe)
        assertTrue(noUsable.safetyNote.orEmpty().contains("灰蓝"))
        assertTrue(listOf(first, disabledLeft, rejectedLeft, noUsable).all { it.utilityEstimated })
    }

    @Test fun `automatic equal utility ordering still chooses first slot`() {
        val event = EventResource(id = "9000", name = "未覆盖测试事件", choices = listOf(
            EventChoiceResource(id = "90002", name = "第二项", slot = 2), EventChoiceResource(id = "90001", name = "第一项", slot = 1),
        ))
        val result = decide(planner(), observation(event)) as LabyrinthEventChoiceDecision.Select
        assertEquals("90001", result.choiceId)
        assertEquals(0.0, result.utility, 0.0)
        assertTrue(result.utilityEstimated)
    }

    @Test fun `revalidation preserves Wait object`() {
        val wait = LabyrinthEventChoiceDecision.Wait("等待识别")
        assertSame(wait, planner().revalidateDecision(wait, observation(monster), emptySet(), emptySet()))
    }

    @Test fun `revalidation binds latest rect without enumerating profiles or estimating again`() {
        val probe = ProfileEnumerationProbe(true)
        val p = planner(configured(cooking, 1), probe)
        val old = decide(p, observation(cooking)) as LabyrinthEventChoiceDecision.Select
        val latestRect = EntryPixelRect(99, 540, 214, 115)
        val latest = observation(cooking).copy(choices = observation(cooking).choices.map {
            if (it.choice.id == old.choiceId) it.copy(buttonRect = latestRect) else it
        })
        val result = p.revalidateDecision(old, latest, setOf("1002"), emptySet()) as LabyrinthEventChoiceDecision.Select
        assertEquals(old.copy(buttonRect = latestRect), result)
        assertFalse(result.utilityEstimated)
        assertEquals(0, probe.enumerationCount)
    }

    @Test fun `revalidation recomputes trusted stability and promotes previously unsafe result`() {
        val p = planner(configured(cooking, 1), ProfileEnumerationProbe(true))
        val safe = decide(p, observation(cooking)) as LabyrinthEventChoiceDecision.Select
        for (input in listOf(observation(cooking, trusted = false), observation(cooking, stable = 1))) {
            val unsafe = p.revalidateDecision(safe, input, emptySet(), emptySet()) as LabyrinthEventChoiceDecision.Select
            assertFalse(unsafe.actionSafe)
            assertTrue(unsafe.safetyNote.orEmpty().contains("稳定"))
            val recovered = p.revalidateDecision(unsafe, observation(cooking), emptySet(), emptySet()) as LabyrinthEventChoiceDecision.Select
            assertTrue(recovered.actionSafe)
            assertEquals(null, recovered.safetyNote)
        }
    }

    @Test fun `revalidation disables current dim or rejected choice regardless of old safety`() {
        val p = planner(configured(cooking, 1), ProfileEnumerationProbe(true))
        val observed = observation(cooking)
        val safe = decide(p, observed) as LabyrinthEventChoiceDecision.Select
        for (value in listOf(0.34, 0.0, Double.NaN)) {
            val unsafe = p.revalidateDecision(safe, enabled(observed, safe.choiceId, value), emptySet(), emptySet())
                as LabyrinthEventChoiceDecision.Select
            assertFalse(unsafe.actionSafe)
            assertTrue(unsafe.safetyNote.orEmpty().contains("灰蓝"))
        }
        val boundary = p.revalidateDecision(safe.copy(actionSafe = false), enabled(observed, safe.choiceId, 0.35), emptySet(), emptySet())
            as LabyrinthEventChoiceDecision.Select
        assertTrue(boundary.actionSafe)
        val rejected = p.revalidateDecision(safe, observed, emptySet(), setOf(safe.choiceId)) as LabyrinthEventChoiceDecision.Select
        assertFalse(rejected.actionSafe)
        assertTrue(rejected.safetyNote.orEmpty().contains("已点击"))
    }

    @Test fun `revalidation rejects changed event missing catalog or wrong label`() {
        val p = planner(configured(monster, 2), ProfileEnumerationProbe(true))
        val observed = observation(monster)
        val selected = decide(p, observed) as LabyrinthEventChoiceDecision.Select
        assertTrue(p.revalidateDecision(selected, observation(cooking), emptySet(), emptySet()) is LabyrinthEventChoiceDecision.Wait)
        assertTrue(p.revalidateDecision(selected.copy(choiceId = "未知选项"), observed, emptySet(), emptySet()) is LabyrinthEventChoiceDecision.Wait)
        assertTrue(p.revalidateDecision(selected.copy(label = "错误名称"), observed, emptySet(), emptySet()) is LabyrinthEventChoiceDecision.Wait)
    }

    @Test fun `revalidation refuses duplicate and mismatched visual identities`() {
        val p = planner(configured(monster, 2), ProfileEnumerationProbe(true))
        val observed = observation(monster)
        val selected = decide(p, observed) as LabyrinthEventChoiceDecision.Select
        val selectedVisual = observed.choices.single { it.choice.id == selected.choiceId }
        val replacements = listOf(
            emptyList(),
            listOf(selectedVisual, selectedVisual.copy(choice = selectedVisual.choice.copy(name = "歧义名称"))),
            listOf(selectedVisual.copy(choice = selectedVisual.choice.copy(slot = 3))),
            listOf(selectedVisual.copy(choice = selectedVisual.choice.copy(name = "错误名称"))),
            listOf(selectedVisual.copy(choice = selectedVisual.choice.copy(id = "未知选项"))),
        )
        for (replacement in replacements) {
            val input = observed.copy(choices = observed.choices.filterNot { it.choice.id == selected.choiceId } + replacement)
            assertTrue(p.revalidateDecision(selected, input, emptySet(), emptySet()) is LabyrinthEventChoiceDecision.Wait)
        }
    }

    @Test fun `revalidation refuses duplicate catalog ids names or target slots`() {
        val p = planner(configured(monster, 2), ProfileEnumerationProbe(true))
        val observed = observation(monster)
        val selected = decide(p, observed) as LabyrinthEventChoiceDecision.Select
        val choice = monster.choices[1]
        val catalogs = listOf(
            monster.copy(choices = monster.choices + choice),
            monster.copy(choices = monster.choices + choice.copy(id = "other", name = "其他项")),
        )
        for (catalog in catalogs) {
            assertTrue(p.revalidateDecision(selected, observed.copy(event = catalog), emptySet(), emptySet()) is LabyrinthEventChoiceDecision.Wait)
        }
    }

    @Test fun `ordinary role pool coverage is rechecked without profile enumeration`() {
        val choice = EventChoiceResource(id = "11011", name = "角色池选项", slot = 1)
        val event = EventResource(id = "role-pool-test", name = "普通角色池测试", choices = listOf(choice))
        val observed = observation(event)
        val selected = LabyrinthEventChoiceDecision.Select(event.id, choice.id, choice.name, observed.choices[0].buttonRect,
            12.0, "已有收益结果", actionSafe = true)
        assertTrue(selected.utilityEstimated) // Existing constructor calls retain their default.
        val probe = ProfileEnumerationProbe(true)
        val unsafe = planner(profiles = probe).revalidateDecision(selected, observed, setOf("1002"), emptySet())
            as LabyrinthEventChoiceDecision.Select
        assertFalse(unsafe.actionSafe)
        assertTrue(unsafe.safetyNote.orEmpty().contains("1002"))
        assertEquals(0, probe.enumerationCount)
        val known = ProfileEnumerationProbe(true, setOf("1002"))
        val safe = planner(profiles = known).revalidateDecision(unsafe, observed, setOf("1002"), emptySet())
            as LabyrinthEventChoiceDecision.Select
        assertTrue(safe.actionSafe)
        assertEquals(0, known.enumerationCount)
        assertEquals(selected.utility, safe.utility, 0.0)
    }

    @Test fun `independent guild choices stay fixed and exempt from profile coverage`() {
        val fixed = listOf(
            Triple("1101", "11012", "想要治疗型 成为伙伴！"),
            Triple("1102", "11021", "想要强化型 成为伙伴！"),
            Triple("1103", "11031", "想要减益型 成为伙伴！"),
            Triple("1104", "11042", "想要破防型 成为伙伴！"),
        )
        for ((id, choiceId, name) in fixed) {
            val selectedSlot = if (id in setOf("1101", "1104")) 2 else 1
            val event = EventResource(id = id, name = "这里是公会管理协会！请问要找什么样的伙伴？", choices = listOf(
                EventChoiceResource(id = choiceId, name = name, slot = selectedSlot),
                EventChoiceResource(id = "other", name = "未选分支", slot = 3 - selectedSlot),
            ))
            val probe = ProfileEnumerationProbe(true)
            val p = planner(LabyrinthStrategySettings(eventCustomizationEnabled = true), probe)
            val observed = observation(event)
            val selected = p.decide(observed, setOf("1002"), LabyrinthRoleDecisionContext(0), emptyMap())
                as LabyrinthEventChoiceDecision.Select
            assertEquals(choiceId, selected.choiceId)
            val current = p.revalidateDecision(selected, observed, setOf("1002"), emptySet()) as LabyrinthEventChoiceDecision.Select
            assertTrue(current.actionSafe)
            assertEquals(0, probe.enumerationCount)
        }
    }

    @Test fun `slate policy remains ahead of customization and cannot revalidate paid branch`() {
        val event = EventResource(id = "1107", name = "发现了不可思议的石板！要怎么办？", choices = listOf(
            EventChoiceResource(id = "11073", name = "摸一下 应该没关系吧？", slot = 1),
            EventChoiceResource(id = "11072", name = "全力挥击", slot = 2),
        ))
        val p = planner(configured(event, 2), ProfileEnumerationProbe(true))
        val observed = observation(event)
        val selected = decide(p, observed) as LabyrinthEventChoiceDecision.Select
        assertEquals("11073", selected.choiceId)
        assertTrue(p.revalidateDecision(selected, observed, emptySet(), emptySet()) is LabyrinthEventChoiceDecision.Select)
        val forged = selected.copy(choiceId = "11072", label = "全力挥击")
        assertTrue(p.revalidateDecision(forged, observed, emptySet(), emptySet()) is LabyrinthEventChoiceDecision.Wait)
    }

    @Test fun `arena independent path and full current double button gate remain intact`() {
        val arena = LabyrinthArenaEvent.event
        val p = planner(configured(arena, 2), ProfileEnumerationProbe(true))
        val observed = observation(arena)
        val selected = decide(p, observed) as LabyrinthEventChoiceDecision.Select
        assertEquals("arena.extreme", selected.choiceId)
        val incompleteBlue = observed.copy(choices = observed.choices.map {
            if (it.choice.id != selected.choiceId) it.copy(buttonConfidence = 0.1) else it
        })
        val unsafe = p.revalidateDecision(selected, incompleteBlue, emptySet(), emptySet()) as LabyrinthEventChoiceDecision.Select
        assertFalse(unsafe.actionSafe)
        assertTrue(unsafe.safetyNote.orEmpty().contains("双选"))
        val recovered = p.revalidateDecision(unsafe, observed, emptySet(), emptySet()) as LabyrinthEventChoiceDecision.Select
        assertTrue(recovered.actionSafe)
        val ambiguous = observed.copy(choices = observed.choices + observed.choices[0])
        assertTrue(p.revalidateDecision(selected, ambiguous, emptySet(), emptySet()) is LabyrinthEventChoiceDecision.Wait)
    }
}
