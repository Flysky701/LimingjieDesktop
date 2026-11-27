package com.landosol.toolbox.desktop.app.strategy

import ai.onnxruntime.*
import com.landosol.toolbox.desktop.ocr.DesktopOrtEnvironment
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleQuestCatalogMeta
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValue
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValueContext
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.ln

internal const val BATTLE_BUNDLE_FORMAT = "limingjie_twotower_onnx_v1"

internal fun modelPath(key: String): String? =
    sequenceOf(System.getProperty(key), System.getenv(key), System.getenv(key.uppercase().replace('.', '_')))
        .firstOrNull { !it.isNullOrBlank() }

internal data class EncodedBattleContext(
    val contextFeatures: ContextFeatures,
    val rosterCtx: FloatArray,
    val contextSummary: FloatArray,
    val unitAffinities: Map<Int, Double>,
    val dModel: Int,
)

internal class BattleOnnxBundle private constructor(
    val features: BattleFeaturizer,
    private val dModel: Int,
    private val towerA: OrtSession,
    private val towerB: OrtSession,
) : AutoCloseable {
    companion object {
        private val environment get() = DesktopOrtEnvironment.instance

        fun catalogPath(directory: Path): Path {
            modelPath("limingjie.nn.catalog")?.let { return Path.of(it) }
            return listOf(directory.resolve("catalog.json"), directory.resolve("feature_pack.json"),
                directory.toAbsolutePath().resolveSibling("feature_pack.json")).firstOrNull { Files.isRegularFile(it) }
                ?: error("缺少 feature_pack.json 或 catalog.json，可设置 limingjie.nn.catalog")
        }

        fun load(directory: Path, catalogPath: Path = catalogPath(directory)): BattleOnnxBundle =
            load(directory, catalogPath, directory.resolve("manifest.json").readObject())

        fun load(directory: Path, catalogPath: Path, manifest: JsonObject): BattleOnnxBundle {
            require(manifest.string("format") == BATTLE_BUNDLE_FORMAT) { "Unsupported battle bundle format" }
            require(manifest.strings("outcome_names") == listOf("win", "timeout", "wipe")) { "Unexpected outcome classes" }
            val files = manifest.getValue("files").jsonObject
            fun verified(name: String): ByteArray = Files.readAllBytes(directory.resolve(name)).also {
                require(sha256(it) == files.string(name)) { "$name SHA-256 mismatch" }
            }
            verified("golden_io.json")
            val catalog = BattleFeatureCatalog.load(catalogPath, manifest.string("catalog_sha256"))
            val dims = manifest.getValue("dimensions").jsonObject
            val sizes = mapOf("num_units" to catalog.units.size + 1, "num_enemies" to catalog.enemies.size + 1,
                "num_quests" to catalog.quests.size + 1, "num_relics" to catalog.relics.size + 1,
                "team_slots" to 5, "enemy_slots" to 5, "num_outcome_classes" to 3, "team_pos_channels" to 3,
                "unit_feat_dim" to BattleFeatureCatalog.UNIT_FEATURES, "enemy_feat_dim" to BattleFeatureCatalog.ENEMY_FEATURES,
                "global_feat_dim" to BattleFeatureCatalog.GLOBAL_FEATURES, "team_synergy_dim" to BattleFeatureCatalog.SYNERGY_FEATURES)
            sizes.forEach { (key, size) -> require(dims.int(key) == size) { "Unexpected dimension $key" } }
            val features = BattleFeaturizer(catalog, dims.int("roster_max_len"), dims.int("relic_max_len"))
            val dModel = dims.int("d_model")
            require(dModel > 0) { "Unexpected dimension d_model" }
            fun openTower(key: String, options: OrtSession.SessionOptions): OrtSession {
                val spec = manifest.getValue(key).jsonObject
                val name = "$key.onnx"
                require(spec.string("file") == name && spec.string("sha256") == files.string(name)) { "$key metadata mismatch" }
                val session = environment.createSession(verified(name), options)
                try {
                    require(session.inputNames.toList() == spec.strings("input_names") &&
                        session.outputNames.toList() == spec.strings("output_names")) { "$key signature mismatch" }
                    return session
                } catch (error: Throwable) {
                    session.close()
                    throw error
                }
            }
            OrtSession.SessionOptions().use { options ->
                options.setMemoryPatternOptimization(false)
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                val a = openTower("tower_a", options)
                try {
                    return BattleOnnxBundle(features, dModel, a, openTower("tower_b", options))
                } catch (error: Throwable) {
                    a.close()
                    throw error
                }
            }
        }
    }

    /** Both native results and all inputs live through consume, including A's borrowed B inputs. */
    fun <T> run(rows: List<Map<String, BattleFeature>>, consume: (OrtSession.Result, OrtSession.Result) -> T): T {
        require(rows.isNotEmpty())
        val names = towerA.inputNames + towerB.inputNames - setOf("roster_ctx", "context_summary")
        return withInputs(names, { name -> tensor(rows.map { it.getValue(name) }) }) { inputs ->
            towerA.run(towerA.inputNames.associateWith(inputs::getValue)).use { a ->
                val feed = towerB.inputNames.associateWith { name -> inputs[name] ?: a.get(name).get() as OnnxTensor }
                towerB.run(feed).use { b -> consume(a, b) }
            }
        }
    }

    fun encodeContext(context: BattleValueContext): EncodedBattleContext {
        val contextFeatures = features.contextRow(context)
        return withInputs(towerA.inputNames, { name -> tensor(listOf(contextFeatures.inputs.getValue(name))) }) { inputs ->
            towerA.run(inputs).use { result ->
                val roster = result.floatOutput("roster_ctx", 1, features.rosterLength, dModel).copyValues()
                val summary = result.floatOutput("context_summary", 1, dModel).copyValues()
                val affinity = result.floatOutput("unit_affinity", 1, features.rosterLength)
                val affinities = contextFeatures.rosterPosByUnitId.mapValues { (_, index) ->
                    affinity.get(index).toDouble().also { require(it.isFinite()) { "Invalid unit affinity" } }
                }
                EncodedBattleContext(contextFeatures, roster, summary, affinities, dModel)
            }
        }
    }

    fun scoreTeams(
        encoded: EncodedBattleContext,
        context: BattleValueContext,
        candidates: List<List<Int>>,
        batchSize: Int = 64,
    ): List<BattleValue> = candidates.chunked(batchSize).flatMap { teams ->
        val rows = teams.map { features.teamRow(encoded.contextFeatures, it) }
        val fastRows = rows.filterNotNull()
        val scores = if (fastRows.isEmpty()) emptyList() else scoreBatch(encoded, fastRows)
        val fastScores = scores.iterator()
        teams.mapIndexed { index, team ->
            if (rows[index] != null) fastScores.next() else predict(listOf(features.row(context, team))).single()
        }
    }

    private fun scoreBatch(encoded: EncodedBattleContext, rows: List<Map<String, BattleFeature>>): List<BattleValue> =
        withInputs(towerB.inputNames, { name ->
            when (name) {
                "roster_ctx" -> repeatTensor(encoded.rosterCtx, rows.size, features.rosterLength, encoded.dModel)
                "context_summary" -> repeatTensor(encoded.contextSummary, rows.size, encoded.dModel)
                else -> tensor(rows.map { it.getValue(name) })
            }
        }) { inputs ->
            towerB.run(inputs).use { readPredictions(it, rows.size) }
        }

    private inline fun <T> withInputs(
        names: Iterable<String>,
        create: (String) -> OnnxTensor,
        consume: (Map<String, OnnxTensor>) -> T,
    ): T {
        val inputs = mutableMapOf<String, OnnxTensor>()
        try {
            for (name in names) inputs[name] = create(name)
            return consume(inputs)
        } finally {
            inputs.values.forEach(OnnxTensor::close)
        }
    }

    private fun repeatTensor(values: FloatArray, batchSize: Int, vararg rowShape: Int): OnnxTensor {
        val buffer = FloatBuffer.allocate(Math.multiplyExact(batchSize, values.size))
        repeat(batchSize) { buffer.put(values) }
        val shape = (listOf(batchSize) + rowShape.toList()).map(Int::toLong).toLongArray()
        return OnnxTensor.createTensor(environment, buffer.flip(), shape)
    }

    private fun tensor(rows: List<BattleFeature>): OnnxTensor {
        val first = rows.first()
        val shape = (listOf(rows.size) + first.shape).map(Int::toLong).toLongArray()
        val size = Math.multiplyExact(rows.size, first.values.size)
        return when (first.type) {
            OnnxJavaType.INT64 -> {
                val buffer = LongBuffer.allocate(size)
                rows.forEach { row -> row.values.forEach { buffer.put(it.toLong()) } }
                OnnxTensor.createTensor(environment, buffer.flip(), shape)
            }
            OnnxJavaType.BOOL -> {
                val buffer = ByteBuffer.allocate(size)
                rows.forEach { row -> row.values.forEach { buffer.put(it.toInt().toByte()) } }
                OnnxTensor.createTensor(environment, buffer.flip(), shape, OnnxJavaType.BOOL)
            }
            OnnxJavaType.FLOAT -> {
                val buffer = FloatBuffer.allocate(size)
                rows.forEach { row -> row.values.forEach { buffer.put(it.toFloat()) } }
                OnnxTensor.createTensor(environment, buffer.flip(), shape)
            }
            else -> error("Unsupported battle feature type")
        }
    }

    private fun OrtSession.Result.floatOutput(name: String, vararg shape: Int): FloatBuffer {
        val tensor = get(name).get() as OnnxTensor
        require(tensor.info.type == OnnxJavaType.FLOAT &&
            tensor.info.shape.contentEquals(shape.map(Int::toLong).toLongArray())) { "Invalid $name output" }
        return tensor.floatBuffer
    }

    private fun FloatBuffer.copyValues(): FloatArray = FloatArray(remaining()).also { get(it) }

    fun predict(rows: List<Map<String, BattleFeature>>): List<BattleValue> =
        if (rows.isEmpty()) emptyList() else run(rows) { _, b -> readPredictions(b, rows.size) }

    private fun readPredictions(result: OrtSession.Result, batchSize: Int): List<BattleValue> {
        val probs = result.floatOutput("outcome_probs", batchSize, 3)
        val damage = result.floatOutput("damage_mean", batchSize, 1)
        val spread = result.floatOutput("damage_std", batchSize, 1)
        val time = result.floatOutput("win_rem_time", batchSize, 1)
        return List(batchSize) {
            val win = probs.get().toDouble()
            val timeout = probs.get().toDouble()
            val wipe = probs.get().toDouble()
            val mean = damage.get().toDouble()
            val std = spread.get().toDouble()
            val remaining = time.get().toDouble()
            require(listOf(win, timeout, wipe, mean, remaining).all { it in 0.0..1.0 } &&
                abs(win + timeout + wipe - 1) < 1e-4 && std.isFinite() && std > 0) { "Invalid battle predictions" }
            BattleValue(win, timeout, wipe, mean, ln(std), remaining)
        }
    }

    fun questCatalogMeta(questId: Int): BattleQuestCatalogMeta? = features.catalog.questMeta[questId]

    override fun close() {
        try { towerB.close() } finally { towerA.close() }
    }
}
