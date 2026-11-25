package com.landosol.toolbox.desktop.app.strategy

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import com.landosol.toolbox.labyrinth.LabyrinthBattleTeamRecommendationResult
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionContext
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionDataParser
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionDataResult
import com.landosol.toolbox.labyrinth.LabyrinthRoleProfile
import com.landosol.toolbox.labyrinth.kernel.strategy.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class BattleOnnxGoldenTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun disabledAndUnusableBundlesSelectRules() {
        val calls = mutableListOf<String>()
        val dir = temp.root.toPath()
        val ruleValue = BattleValue(0.5, 0.25, 0.25, 0.5, -1.0, 0.5)
        val rules = LabyrinthBattleValueEstimator { _, teams -> teams.map { ruleValue } }
        val context = BattleValueContext(1, 1, 1, emptyList(), LabyrinthRoleDecisionContext(0))
        DesktopBattleModel({ dir }, calls::add).use { owner ->
            assertSame(rules, owner.select(false, rules))
            assertTrue(calls.isEmpty())
            fun verifyFallback() {
                val estimator = owner.select(true, rules)
                assertEquals(listOf(ruleValue), estimator.estimate(context, listOf(listOf(1))))
                assertEquals(calls.last(), owner.fallbackReason)
            }
            verifyFallback()
            Files.writeString(dir.resolve("catalog.json"), "{}")
            Files.writeString(dir.resolve("manifest.json"), """{"format":"unknown"}""")
            verifyFallback()
            assertTrue(owner.fallbackReason.orEmpty().contains("format"))
            Files.writeString(dir.resolve("golden_io.json"), "{}")
            Files.writeString(dir.resolve("manifest.json"), """{"format":"limingjie_twotower_onnx_v1",
                "outcome_names":["win","timeout","wipe"],"files":{"golden_io.json":"wrong"}}""")
            verifyFallback()
            assertTrue(owner.fallbackReason.orEmpty().contains("SHA-256"))
        }
        val wrongHash = "0".repeat(64)
        val catalog = dir.resolve("catalog.json")
        Files.writeString(catalog, """{"catalog_sha256":"$wrongHash","units":{}}""")
        assertTrue(assertThrows(IllegalArgumentException::class.java) {
            BattleFeatureCatalog.load(catalog, wrongHash)
        }.message.orEmpty().contains("SHA-256"))
    }

    private fun numbers(value: JsonElement): List<Double> = when (value) {
        is JsonArray -> value.flatMap(::numbers)
        else -> listOf(value.jsonPrimitive.doubleOrNull ?: value.jsonPrimitive.boolean.numeric())
    }

    private fun dimensions(value: JsonElement): List<Int> = if (value is JsonArray) {
        listOf(value.size) + (value.firstOrNull()?.let(::dimensions) ?: emptyList())
    } else emptyList()

    private fun compare(label: String, expected: List<Double>, actual: List<Double>) {
        assertEquals(label, expected.size, actual.size)
        val error = expected.indices.maxOfOrNull { abs(expected[it] - actual[it]) } ?: 0.0
        assertTrue("$label max_abs_diff=$error", error < 1e-4)
    }

    private fun BattleValue.numbers() = listOf(pWin, pTimeout, pWipe, damageMean, damageLogStd, winRemTime)

    private fun fixtureFeatures(base: BattleFeaturizer, case: JsonObject): BattleFeaturizer {
        val overrides = case.getValue("overrides").jsonObject
        val units = base.catalog.units.toMutableMap()
        overrides["unit_search_area_width"]?.jsonObject?.forEach { (key, value) ->
            val unit = units.getValue(key.toInt())
            val width = value.jsonPrimitive.int
            units[key.toInt()] = unit.copy(width = width, features = unit.features.toMutableList().also { it[0] = width / 1000.0 })
        }
        val quests = base.catalog.quests.toMutableMap()
        overrides["synthetic_quest"]?.jsonObject?.let { quests[it.int("quest_id")] = it.ints("enemy_ids") }
        return BattleFeaturizer(base.catalog.copy(units = units, quests = quests), base.rosterLength, base.relicLength)
    }

    private fun context(sample: JsonObject, catalog: BattleFeatureCatalog): BattleValueContext {
        val units = sample.ints("owned_unit_ids").map { id ->
            val unit = catalog.units.getValue(id)
            BattleRosterUnit(id, LabyrinthRoleProfile(id.toString(), id.toString()), unit.role, unit.talent)
        }
        return BattleValueContext(sample.int("quest_id"), sample.int("area"), sample.int("difficulty"),
            units, LabyrinthRoleDecisionContext(0), sample.ints("relic_ids"), sample.ints("mark_stacks"),
            sample["alpha_rupee"]?.jsonPrimitive?.intOrNull, sample.getValue("revive_available").jsonPrimitive.boolean)
    }

    @Test fun replayPythonFeaturesAndChainedTowers() {
        val path = modelPath("limingjie.nn.bundle")?.let { Path.of(it) }
        assumeTrue("Set limingjie.nn.bundle to an exported bundle", path != null && Files.isDirectory(path))
        val directory = requireNotNull(path)
        BattleOnnxBundle.load(directory).use { bundle ->
            val cases = directory.resolve("golden_io.json").readObject().getValue("cases").jsonArray.map { it.jsonObject }
            assertTrue(cases.isNotEmpty())
            val rows = cases.map { case ->
                val features = fixtureFeatures(bundle.features, case)
                val sample = case.getValue("sample").jsonObject
                val row = features.row(context(sample, features.catalog), sample.ints("team_unit_ids"))
                for (tower in listOf("tower_a", "tower_b")) {
                    val inputs = case.getValue(tower).jsonObject.getValue("inputs").jsonObject
                    for ((name, expected) in inputs - setOf("roster_ctx", "context_summary")) {
                        val feature = row.getValue(name)
                        assertEquals(name, dimensions(expected), listOf(1) + feature.shape)
                        val actual = feature.values.map { if (feature.type == OnnxJavaType.FLOAT) it.toFloat().toDouble() else it }
                        compare("${case.string("name")}/$name", numbers(expected), actual)
                    }
                }
                row
            }
            bundle.run(rows) { a, b ->
                fun compareTower(name: String, result: OrtSession.Result) {
                    val expected = cases.map { it.getValue(name).jsonObject.getValue("outputs").jsonObject }
                    for ((key, value) in result) {
                        val tensor = value as OnnxTensor
                        assertEquals(key, listOf(rows.size) + dimensions(expected.first().getValue(key)).drop(1),
                            tensor.info.shape.map(Long::toInt))
                        val buffer = tensor.floatBuffer
                        compare("$name/$key", expected.flatMap { numbers(it.getValue(key)) },
                            List(buffer.remaining()) { buffer.get().toDouble() })
                    }
                }
                compareTower("tower_a", a)
                compareTower("tower_b", b)
            }
            for ((case, row) in cases.zip(rows)) {
                val expected = case.getValue("tower_b").jsonObject.getValue("outputs").jsonObject
                val v = bundle.predict(listOf(row)).single()
                compare(case.string("name"), numbers(expected.getValue("outcome_probs")) +
                    numbers(expected.getValue("damage_mean")) + ln(numbers(expected.getValue("damage_std")).single()) +
                    numbers(expected.getValue("win_rem_time")),
                    listOf(v.pWin, v.pTimeout, v.pWipe, v.damageMean, v.damageLogStd, v.winRemTime))
            }
            val sample = cases.first().getValue("sample").jsonObject
            val ctx = context(sample, bundle.features.catalog)
            val team = sample.ints("team_unit_ids")
            val ruleValue = BattleValue(1.0, 0.0, 0.0, 1.0, 0.0, 1.0)
            DesktopBattleModel({ directory }, {}).use { owner ->
                val estimator = owner.select(true, LabyrinthBattleValueEstimator { _, teams -> teams.map { ruleValue } })
                compare("desktop estimator", bundle.predict(listOf(rows.first())).single().numbers(),
                    estimator.estimate(ctx, listOf(team)).single().numbers())
                // A stale wave cannot be interpreted using this frozen catalog.
                assertEquals(listOf(ruleValue), estimator.estimate(ctx.copy(enemyIds = listOf(-1)), listOf(team)))
                assertTrue(owner.fallbackReason.orEmpty().contains("wave"))
            }
        }
    }

    @Test fun cachedTowerAMatchesFullPredictAndReusesContext() {
        val path = modelPath("limingjie.nn.bundle")?.let { Path.of(it) }
        assumeTrue("Set limingjie.nn.bundle to an exported bundle", path != null && Files.isDirectory(path))
        val directory = requireNotNull(path)
        BattleOnnxBundle.load(directory).use { bundle ->
            val cases = directory.resolve("golden_io.json").readObject().getValue("cases").jsonArray.map { it.jsonObject }
            assertEquals(6, cases.size)
            for (case in cases) {
                val sample = case.getValue("sample").jsonObject
                val ctx = context(sample, bundle.features.catalog)
                val candidate = sample.ints("team_unit_ids")
                val row = bundle.features.row(ctx, candidate)
                val withoutMetadata = ctx.copy(ownedUnits = ctx.ownedUnits.map { it.copy(roleId = 0, talentId = 0) })
                val encoded = bundle.encodeContext(withoutMetadata)
                assertArrayEquals(row.getValue("global_feats").values,
                    encoded.contextFeatures.inputs.getValue("global_feats").values, 0.0)
                val sortedTeam = case.ints("team_unit_ids_sorted")
                val positions = encoded.contextFeatures.rosterPosByUnitId
                val teamRow = requireNotNull(bundle.features.teamRow(encoded.contextFeatures, candidate))
                assertEquals(sortedTeam.map { if (it == 0) 0 else positions.getValue(it) },
                    teamRow.getValue("team_roster_pos").values.map(Double::toInt))
                assertEquals(positions.keys, encoded.unitAffinities.keys)
                compare(case.string("name"), bundle.predict(listOf(row)).single().numbers(),
                    bundle.scoreTeams(encoded, withoutMetadata, listOf(candidate)).single().numbers())
            }

            val sample = cases.first().getValue("sample").jsonObject
            val ctx = context(sample, bundle.features.catalog)
            val team = sample.ints("team_unit_ids")
            verifyBatchesAndOverflow(bundle, ctx)
            val ruleValue = BattleValue(0.5, 0.25, 0.25, 0.5, -1.0, 0.5)
            val rules = LabyrinthBattleValueEstimator { _, candidates -> candidates.map { ruleValue } }
            val logs = mutableListOf<String>()
            DesktopBattleModel({ directory }, logs::add).use { owner ->
                val estimator = owner.select(true, rules) as ContextualBattleValueEstimator
                val affinities = requireNotNull(estimator.unitAffinities(ctx))
                assertTrue(affinities.isNotEmpty())
                assertEquals(BattleValueSource.MODEL, estimator.estimateWithSource(ctx, listOf(team)).source)
                val reordered = ctx.copy(ownedUnits = ctx.ownedUnits.reversed())
                assertEquals(BattleValueSource.MODEL, estimator.estimateWithSource(reordered, listOf(team)).source)
                assertEquals(1, owner.towerAInvocations)

                val changed = ctx.copy(reviveAvailable = !ctx.reviveAvailable)
                assertEquals(BattleValueSource.MODEL, estimator.estimateWithSource(changed, listOf(team)).source)
                assertNotNull(estimator.unitAffinities(changed))
                assertEquals(2, owner.towerAInvocations)

                val unknown = ctx.copy(questId = Int.MIN_VALUE)
                assertNull(estimator.unitAffinities(unknown))
                val fallback = estimator.estimateWithSource(unknown, listOf(team))
                assertEquals(BattleValueSource.RULE, fallback.source)
                assertEquals(listOf(ruleValue), fallback.values)
                assertTrue(fallback.fallbackReason.orEmpty().contains("Unknown quest"))
                assertEquals(fallback.fallbackReason, logs.last())
                assertEquals(2, owner.towerAInvocations)

                assertSame(rules, owner.select(false, rules))
                val reopened = owner.select(true, rules) as ContextualBattleValueEstimator
                assertNotNull(reopened.unitAffinities(changed))
                assertEquals(3, owner.towerAInvocations)
                verifyPlanner(owner, ctx)
            }
        }
    }

    private fun verifyBatchesAndOverflow(bundle: BattleOnnxBundle, context: BattleValueContext) {
        val features = bundle.features
        val ids = features.catalog.units.keys.sortedWith(compareBy({ features.catalog.units.getValue(it).width }, { it }))
            .take(features.rosterLength + 1)
        val units = ids.map { id -> BattleRosterUnit(id, LabyrinthRoleProfile(id.toString(), id.toString())) }
        val ctx = context.copy(ownedUnits = units)
        val encoded = bundle.encodeContext(ctx)
        assertEquals(ids.take(features.rosterLength), encoded.contextFeatures.rosterPosByUnitId.keys.toList())
        val overflowTeam = ids.take(4) + ids.last()
        assertNull(features.teamRow(encoded.contextFeatures, overflowTeam))
        val candidates = listOf(ids.take(5), overflowTeam, ids.drop(1).take(5), ids.drop(2).take(5))
        val actual = bundle.scoreTeams(encoded, ctx, candidates, batchSize = 3)
        val expected = bundle.predict(candidates.map { features.row(ctx, it) })
        assertEquals(candidates.size, actual.size)
        for (index in candidates.indices) compare("batch team $index", expected[index].numbers(), actual[index].numbers())
        assertTrue(bundle.scoreTeams(encoded, ctx, emptyList()).isEmpty())
    }

    private fun verifyPlanner(owner: DesktopBattleModel, context: BattleValueContext) {
        val asset = "/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"
        val raw = requireNotNull(javaClass.getResourceAsStream(asset)).bufferedReader().use { it.readText() }
        val runtime = when (val parsed = LabyrinthRoleDecisionDataParser.parse(raw)) {
            is LabyrinthRoleDecisionDataResult.Ready -> parsed.runtime
            is LabyrinthRoleDecisionDataResult.Unavailable -> error(parsed.reason)
        }
        val units = context.ownedUnits.mapNotNull { unit ->
            runtime.profiles[(unit.unitId / 100).toString()]?.let { unit.copy(profile = it) }
        }.distinctBy { it.profile.characterId }.take(7)
        val roleContext = LabyrinthRoleDecisionContext(defenseMarkStacks = 0, currentArea = context.area)
        val active = runtime.withBattleValueEstimator(owner.select(true, runtime.battleValueEstimator))
        val acquired = units.map { it.profile.characterId }
        val result = active.battleTeamRecommendationPlanner.initialRecommendation(
            acquiredCharacterIds = acquired,
            context = roleContext,
            battleValueContext = context.copy(ownedUnits = units, ruleContext = roleContext),
        )
        assertTrue(result.toString(), result is LabyrinthBattleTeamRecommendationResult.Ready)
        val recommendation = (result as LabyrinthBattleTeamRecommendationResult.Ready).recommendation
        assertEquals(5, recommendation.members.size)
        assertTrue(recommendation.members.all { it.characterId in acquired })
        val assessment = requireNotNull(recommendation.modelAssessment)
        assertTrue(assessment.evaluatedCandidates > 1)
        assertTrue(listOf(assessment.pWin, assessment.damageMean, assessment.winRemTime).all { it in 0.0..1.0 })
        assertEquals(BattleConfidenceTier.classify(assessment.pWin), assessment.tier)
        assertEquals(4, owner.towerAInvocations)
    }
}
