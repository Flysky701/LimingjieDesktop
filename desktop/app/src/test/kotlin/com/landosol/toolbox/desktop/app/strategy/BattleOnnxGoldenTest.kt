package com.landosol.toolbox.desktop.app.strategy

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import com.landosol.toolbox.desktop.app.control.ControlModelCodec
import com.landosol.toolbox.labyrinth.LabyrinthBattleTeamRecommendationResult
import com.landosol.toolbox.labyrinth.LabyrinthCombatContext
import com.landosol.toolbox.labyrinth.LabyrinthCombatKind
import com.landosol.toolbox.labyrinth.LabyrinthExEncounterCatalog
import com.landosol.toolbox.labyrinth.LabyrinthNodeJson
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionContext
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionDataParser
import com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionDataResult
import com.landosol.toolbox.labyrinth.LabyrinthRoleProfile
import com.landosol.toolbox.labyrinth.LabyrinthRouteJson
import com.landosol.toolbox.labyrinth.labyrinthActiveBattleQuestId
import com.landosol.toolbox.labyrinth.labyrinthBattleTeamSelectionMessage
import com.landosol.toolbox.labyrinth.summary
import com.landosol.toolbox.labyrinth.kernel.planning.BattleExam
import com.landosol.toolbox.labyrinth.kernel.planning.ModelBattlePassEstimator
import com.landosol.toolbox.labyrinth.kernel.planning.PlanningState
import com.landosol.toolbox.labyrinth.kernel.planning.evaluateRelicChoices
import com.landosol.toolbox.labyrinth.kernel.planning.evaluateRoleChoices
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
        var ruleCalls = 0
        val rules = LabyrinthBattleValueEstimator { _, teams -> ruleCalls++; teams.map { ruleValue } }
        val context = BattleValueContext(1, 1, 1, emptyList(), LabyrinthRoleDecisionContext(0))
        DesktopBattleModel({ dir }, calls::add).use { owner ->
            assertSame(rules, owner.select(false, rules))
            assertTrue(calls.isEmpty())
            fun verifyFallback() {
                val estimator = owner.select(true, rules) as ContextualBattleValueEstimator
                val logged = calls.size
                val previousRuleCalls = ruleCalls
                assertNull(estimator.unitAffinities(context))
                assertEquals(previousRuleCalls, ruleCalls)
                val result = estimator.estimateWithSource(context, listOf(listOf(1)))
                assertEquals(BattleValueEstimate(listOf(ruleValue), BattleValueSource.RULE, calls.last()), result)
                assertEquals(previousRuleCalls + 1, ruleCalls)
                assertEquals(logged, calls.size)
                assertEquals(BattleModelHealth.FAILED, owner.status.value.health)
                assertEquals(0, owner.status.value.towerAInvocations)
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

    @Test fun bundleLocationPrecedenceAndMissingDefault() {
        val root = temp.root.toPath()
        val worker = root.resolve("worker")
        val inherited = root.resolve("global")
        val osDefault = root.resolve("default")
        val executable = root.resolve("app/Tool.exe")
        val setting = root.resolve("custom")
        val relative = Path.of("models", "s4_m2b_r1_seed43")
        val dotted = root.resolve("dotted")
        val upper = root.resolve("upper")
        val jvm = root.resolve("jvm")
        val env = mapOf(BATTLE_DEFAULT_DATA_DIR_ENV to inherited.toString(), "limingjie.nn.bundle" to dotted.toString(),
            "LIMINGJIE_NN_BUNDLE" to upper.toString())
        fun resolve(configured: String = "", properties: Map<String, String> = emptyMap(),
            environment: Map<String, String> = mapOf(BATTLE_DEFAULT_DATA_DIR_ENV to inherited.toString()),
        ) = resolveBattleBundleLocation(configured, worker, systemProperties = properties, env = environment,
            defaultResolvedDataDir = osDefault, appPath = executable.toString())
        fun expect(path: Path, source: BattleBundleSource, actual: BattleBundleLocation, overridden: Boolean = false) {
            assertEquals(path, actual.path)
            assertEquals(source, actual.source)
            assertEquals(overridden, actual.overriddenByEnv)
        }
        fun addManifest(base: Path) {
            Files.writeString(Files.createDirectories(base.resolve(relative)).resolve("manifest.json"), "{}")
        }
        expect(jvm, BattleBundleSource.SYSTEM_PROPERTY,
            resolve(setting.toString(), mapOf("limingjie.nn.bundle" to jvm.toString()), env), true)
        expect(dotted, BattleBundleSource.ENV_VAR, resolve(setting.toString(), environment = env), true)
        expect(upper, BattleBundleSource.ENV_VAR, resolve(setting.toString(), environment = env - "limingjie.nn.bundle"), true)
        expect(setting, BattleBundleSource.SETTING_PATH, resolve("  $setting  "))
        expect(inherited.resolve(relative), BattleBundleSource.UNRESOLVED, resolve())
        expect(worker.resolve(relative), BattleBundleSource.UNRESOLVED, resolve(environment = emptyMap()))
        addManifest(executable.parent)
        expect(executable.parent.resolve(relative), BattleBundleSource.APP_DIR, resolve())
        addManifest(osDefault)
        expect(osDefault.resolve(relative), BattleBundleSource.DEFAULT_DATA_DIR, resolve())
        addManifest(inherited)
        expect(inherited.resolve(relative), BattleBundleSource.DEFAULT_DATA_DIR, resolve())
        addManifest(worker)
        expect(worker.resolve(relative), BattleBundleSource.DEFAULT_DATA_DIR, resolve())
    }

    @Test fun statusAndDecisionTraceRoundTripThroughControlCodec() {
        val trace = BattleModelTrace(BattleModelDecisionStatus.ADOPTED, 770220901, "NN胜率 84.2%")
        val status = BattleModelStatus(true, BattleModelHealth.FALLBACK, "s4_m2b_r1_seed43", "test/models",
            BattleBundleSource.ENV_VAR, true, "推理回退", 3)
        assertEquals(status, ControlModelCodec.decode<BattleModelStatus>(ControlModelCodec.encode(status)))
        assertEquals(trace, ControlModelCodec.decode<BattleModelTrace>(ControlModelCodec.encode(trace)))
    }

    @Test fun all64ExQuestsBuildFeaturesAndRunBothTowers() {
        val directory = realBundleDirectory()
        val contexts = LabyrinthExEncounterCatalog.all.flatMap { encounter ->
            (1..5).mapNotNull { area ->
                LabyrinthExEncounterCatalog.questIdFor(area, encounter)?.let { area to it }
            }
        }
        assertEquals(14, LabyrinthExEncounterCatalog.all.size)
        assertEquals(64, contexts.size)
        assertEquals(64, contexts.map { it.second }.distinct().size)
        val quests = directory.resolve("feature_pack.json").readObject().getValue("quests").jsonObject
        BattleOnnxBundle.load(directory).use { bundle ->
            val sample = goldenSample(directory)
            val base = context(sample, bundle.features.catalog)
            val team = sample.ints("team_unit_ids")
            contexts.forEach { (area, questId) ->
                assertTrue("Missing quest $questId", questId.toString() in quests)
                val battle = base.copy(area = area, questId = questId,
                    ruleContext = base.ruleContext.copy(currentArea = area))
                val encoded = bundle.encodeContext(battle)
                assertTrue("Tower A/$questId", encoded.unitAffinities.isNotEmpty() && encoded.unitAffinities.values.all(Double::isFinite))
                val values = bundle.scoreTeams(encoded, battle, listOf(team)).single()
                assertTrue("Tower B/$questId", values.numbers().all(Double::isFinite))
            }
        }
    }

    @Test fun floorTwoKurumiRouteWithoutQuestIdUsesOnnxRecommendation() {
        val directory = realBundleDirectory()
        val node = LabyrinthNodeJson(2, 3, 1, 20301L, 3, null, emptyList(), false)
        val route = LabyrinthRouteJson(1L, 1, 1, 1, false, false, "", emptyList(), emptyList(),
            listOf(node), currentBlockId = node.blockId)
        val encounter = LabyrinthExEncounterCatalog.all.single { it.id == "multi_kurumi_shadow" }
        val questId = requireNotNull(labyrinthActiveBattleQuestId(LabyrinthCombatKind.EX, route, 2, encounter))
        assertEquals(770220901, questId)
        val runtime = roleRuntime()
        val roster = goldenSample(directory).ints("owned_unit_ids").mapNotNull { id ->
            runtime.profiles[(id / 100).toString()]?.let { BattleRosterUnit(id, it) }
        }.distinctBy { it.profile.characterId }.take(12)
        val rules = LabyrinthRoleDecisionContext(0, targetCount = encounter.targetCount, encounterStrategy = encounter, currentArea = node.area)
        val battle = BattleValueContext(questId, node.area, route.difficulty, roster, rules)
        val combat = LabyrinthCombatContext(LabyrinthCombatKind.EX, targetCount = encounter.targetCount)
        DesktopBattleModel({ directory }, {}).use { owner ->
            val active = runtime.withBattleValueEstimator(owner.select(true, runtime.battleValueEstimator))
            val result = active.battleTeamRecommendationPlanner.initialRecommendation(
                roster.map { it.profile.characterId }, rules, battleValueContext = battle,
            )
            assertTrue(result.toString(), result is LabyrinthBattleTeamRecommendationResult.Ready)
            val recommendation = (result as LabyrinthBattleTeamRecommendationResult.Ready).recommendation
            val assessment = requireNotNull(recommendation.modelAssessment)
            assertTrue(assessment.adopted)
            assertEquals(BattleModelTrace(BattleModelDecisionStatus.ADOPTED, questId, assessment.summary()), recommendation.modelTrace)
            for (text in listOf(labyrinthBattleTeamSelectionMessage(combat, recommendation, null), recommendation.logText(),
                recommendation.desktopDecisionText(combat))) {
                assertTrue(text, "NN胜率" in text && "770220901" in text)
            }
        }
    }

    @Test fun pathBMarginalsReuseInterleavedBossContexts() {
        val directory = realBundleDirectory()
        val runtime = roleRuntime()
        val manifest = directory.resolve("manifest.json").readObject()
        val catalog = BattleFeatureCatalog.load(directory.resolve("feature_pack.json"), manifest.string("catalog_sha256"))
        val exams = listOf(BattleExam(770330401, 3, isBoss = true), BattleExam(770530301, 5, isBoss = true))
        assertTrue(exams.all { it.questId in catalog.quests })

        val profiles = runtime.profiles.values.filter { profile ->
            profile.position != null && profile.characterId.toIntOrNull()?.let { it * 100 + 1 in catalog.units } == true
        }
        val front = profiles.single { it.characterId == "1052" }
        val rear = profiles.filter { requireNotNull(it.position) > requireNotNull(front.position) }.take(6)
        assertEquals(6, rear.size)
        val roleCandidates = rear.drop(4).map { it.characterId }
        val owned = (listOf(front) + rear.take(4)).map { it.characterId }.toSet()
        val relicCandidates = listOf(40003) + catalog.relics.keys.filter { it != 40003 }.sorted().take(1)
        assertTrue(relicCandidates.all { it in catalog.relics })
        val state = PlanningState(5, 3, owned, emptySet(), emptyMap(), remainingShops = 1,
            enhanceNode21 = false, reviveAvailable = false)

        DesktopBattleModel({ directory }, {}).use { owner ->
            val model = owner.select(true, runtime.battleValueEstimator) as ContextualBattleValueEstimator
            val estimator = ModelBattlePassEstimator(model, runtime.profiles)
            val roles = evaluateRoleChoices(state, roleCandidates, exams, estimator)
            val relics = evaluateRelicChoices(state, relicCandidates, exams, estimator)
            assertEquals(2, roles.size)
            assertEquals(2, relics.size)
            assertTrue(roles.all { it.usedModel && it.afterPassProb in 0.0..1.0 && it.totalScore.isFinite() })
            assertTrue(relics.all { it.usedModel && it.afterPassProb in 0.0..1.0 && it.totalScore.isFinite() })
            // Two Boss contexts each for the original roster, two additional roles and two relics.
            val encodes = owner.towerAInvocations
            assertEquals(10, encodes)
            val started = System.nanoTime()
            assertEquals(relics, evaluateRelicChoices(state, relicCandidates, exams, estimator))
            assertEquals(roles, evaluateRoleChoices(state, roleCandidates, exams, estimator))
            val warmMillis = (System.nanoTime() - started) / 1_000_000
            assertEquals(encodes, owner.towerAInvocations)
            assertTrue("Warm Boss marginal evaluation took ${warmMillis}ms", warmMillis < 5_000)
        }
    }

    @Test fun manifestContentChangeReloadsEvenWithSameSizeAndTimestamp() {
        val source = realBundleDirectory()
        val directory = temp.newFolder("bundle").toPath()
        listOf("manifest.json", "feature_pack.json", "golden_io.json", "tower_a.onnx", "tower_b.onnx").forEach {
            Files.copy(source.resolve(it), directory.resolve(it))
        }
        val manifest = directory.resolve("manifest.json")
        val timestamp = Files.getLastModifiedTime(manifest)
        val initial = Files.readString(manifest)
        val edited = initial.replaceFirst("m2b_r1", "m2b_r2")
        assertNotEquals(initial, edited)
        assertEquals(initial.length, edited.length)
        val warnings = mutableListOf<String>()
        val info = mutableListOf<String>()
        val rules = LabyrinthBattleValueEstimator { _, _ -> emptyList() }
        val location = BattleBundleLocation(directory, BattleBundleSource.SETTING_PATH)
        DesktopBattleModel(warnings::add, { location }, info::add).use { owner ->
            val disabled = owner.inspect(false)
            assertEquals(BattleModelHealth.DISABLED, disabled.health)
            assertEquals(directory.toString(), disabled.resolvedPath)
            assertEquals(BattleModelHealth.READY, owner.inspect(true).health)
            assertTrue(info.isEmpty())
            assertEquals(BattleModelHealth.READY, owner.inspect(true, forceReload = true, ephemeralCheck = true).health)
            val first = owner.select(true, rules) as ContextualBattleValueEstimator
            val catalog = BattleFeatureCatalog.load(source.resolve("feature_pack.json"), manifest.readObject().string("catalog_sha256"))
            val sample = goldenSample(source)
            val battle = context(sample, catalog)
            val team = listOf(sample.ints("team_unit_ids"))
            assertEquals(BattleValueSource.MODEL, first.estimateWithSource(battle, team).source)
            assertEquals(BattleModelHealth.LOADED, owner.inspect(true).health)
            assertEquals(2, info.size)
            Files.writeString(manifest, edited)
            Files.setLastModifiedTime(manifest, timestamp)
            assertEquals(timestamp, Files.getLastModifiedTime(manifest))

            val second = owner.select(true, rules) as ContextualBattleValueEstimator
            assertEquals(BattleValueSource.RULE, first.estimateWithSource(battle, team).source)
            assertEquals(BattleValueSource.MODEL, second.estimateWithSource(battle, team).source)
            assertEquals(2, owner.status.value.towerAInvocations)
            assertEquals(3, info.size)
            assertTrue(warnings.isEmpty())
        }
    }

    private fun realBundleDirectory(): Path {
        val path = modelPath("limingjie.nn.bundle")?.let(Path::of)
        assumeTrue("Set limingjie.nn.bundle to an exported bundle", path != null && Files.isDirectory(path))
        return requireNotNull(path)
    }

    private fun goldenSample(directory: Path): JsonObject =
        directory.resolve("golden_io.json").readObject().getValue("cases").jsonArray.first().jsonObject.getValue("sample").jsonObject

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
        val directory = realBundleDirectory()
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
        val directory = realBundleDirectory()
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

    private fun roleRuntime(): com.landosol.toolbox.labyrinth.LabyrinthRoleDecisionRuntime {
        val asset = "/assets/resource-packs/cn-bilibili/labyrinth-role-decision.json"
        val raw = requireNotNull(javaClass.getResourceAsStream(asset)).bufferedReader().use { it.readText() }
        return when (val parsed = LabyrinthRoleDecisionDataParser.parse(raw)) {
            is LabyrinthRoleDecisionDataResult.Ready -> parsed.runtime
            is LabyrinthRoleDecisionDataResult.Unavailable -> error(parsed.reason)
        }
    }

    private fun verifyPlanner(owner: DesktopBattleModel, context: BattleValueContext) {
        val runtime = roleRuntime()
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
