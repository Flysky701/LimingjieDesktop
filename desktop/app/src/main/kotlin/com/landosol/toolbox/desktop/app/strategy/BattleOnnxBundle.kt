package com.landosol.toolbox.desktop.app.strategy

import ai.onnxruntime.*
import com.landosol.toolbox.desktop.ocr.DesktopOrtEnvironment
import com.landosol.toolbox.labyrinth.kernel.strategy.BattleValue
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.ln

internal fun modelPath(key: String): String? =
    sequenceOf(System.getProperty(key), System.getenv(key), System.getenv(key.uppercase().replace('.', '_')))
        .firstOrNull { !it.isNullOrBlank() }

internal class BattleOnnxBundle private constructor(
    val features: BattleFeaturizer,
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

        fun load(directory: Path, catalogPath: Path = catalogPath(directory)): BattleOnnxBundle {
            val manifest = directory.resolve("manifest.json").readObject()
            require(manifest.string("format") == "limingjie_twotower_onnx_v1") { "Unsupported battle bundle format" }
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
                    return BattleOnnxBundle(features, a, openTower("tower_b", options))
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
        val inputs = mutableMapOf<String, OnnxTensor>()
        try {
            for (name in towerA.inputNames + towerB.inputNames - setOf("roster_ctx", "context_summary")) {
                inputs[name] = tensor(rows.map { it.getValue(name) })
            }
            towerA.run(towerA.inputNames.associateWith(inputs::getValue)).use { a ->
                val feed = towerB.inputNames.associateWith { name -> inputs[name] ?: (a.get(name).get() as OnnxTensor) }
                return towerB.run(feed).use { b -> consume(a, b) }
            }
        } finally {
            inputs.values.forEach(OnnxTensor::close)
        }
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

    fun predict(rows: List<Map<String, BattleFeature>>): List<BattleValue> = run(rows) { _, b ->
        fun output(name: String, width: Int): FloatBuffer {
            val tensor = b.get(name).get() as OnnxTensor
            require(tensor.info.type == OnnxJavaType.FLOAT &&
                tensor.info.shape.contentEquals(longArrayOf(rows.size.toLong(), width.toLong()))) { "Invalid $name output" }
            return tensor.floatBuffer
        }
        val probs = output("outcome_probs", 3)
        val damage = output("damage_mean", 1)
        val spread = output("damage_std", 1)
        val time = output("win_rem_time", 1)
        List(rows.size) {
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

    override fun close() {
        try { towerB.close() } finally { towerA.close() }
    }
}
