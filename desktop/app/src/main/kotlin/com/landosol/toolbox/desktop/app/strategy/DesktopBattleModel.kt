package com.landosol.toolbox.desktop.app.strategy

import com.landosol.toolbox.desktop.platform.DataDirectories
import com.landosol.toolbox.desktop.shared.DesktopLog
import com.landosol.toolbox.labyrinth.kernel.strategy.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CancellationException

enum class BattleModelHealth(val label: String) {
    DISABLED("已关闭"),
    READY("已就绪"),
    LOADED("已加载"),
    FAILED("加载失败"),
    FALLBACK("推理回退"),
}

enum class BattleBundleSource(val label: String) {
    SYSTEM_PROPERTY("JVM 参数"),
    ENV_VAR("环境变量"),
    SETTING_PATH("手动配置"),
    DEFAULT_DATA_DIR("默认数据目录"),
    APP_DIR("程序目录"),
    UNRESOLVED("默认路径（未找到）"),
}

data class BattleModelStatus(
    val enabled: Boolean = false,
    val health: BattleModelHealth = BattleModelHealth.DISABLED,
    val bundleId: String? = null,
    val resolvedPath: String = "",
    val source: BattleBundleSource = BattleBundleSource.UNRESOLVED,
    val overriddenByEnv: Boolean = false,
    val detail: String? = null,
    val towerAInvocations: Int = 0,
)

internal data class BattleBundleLocation(val path: Path, val source: BattleBundleSource) {
    val overriddenByEnv: Boolean
        get() = source == BattleBundleSource.SYSTEM_PROPERTY || source == BattleBundleSource.ENV_VAR
}

internal const val BATTLE_DEFAULT_DATA_DIR_ENV = "LIMINGJIE_NN_DEFAULT_DATA_DIR"

internal fun resolveBattleBundleLocation(
    configuredPath: String,
    dataDirectory: Path,
    systemProperties: Map<String, String> = mapOf(
        "limingjie.nn.bundle" to System.getProperty("limingjie.nn.bundle").orEmpty(),
        "jpackage.app-path" to System.getProperty("jpackage.app-path").orEmpty(),
    ),
    env: Map<String, String> = System.getenv(),
    defaultDataDirEnv: String? = env[BATTLE_DEFAULT_DATA_DIR_ENV],
    appPath: String? = systemProperties["jpackage.app-path"],
    defaultResolvedDataDir: Path? = runCatching { DataDirectories.resolve(emptyArray(), appPath?.takeIf(String::isNotBlank), env) }.getOrNull(),
): BattleBundleLocation {
    fun explicit(path: String?, source: BattleBundleSource) =
        path?.trim()?.takeIf(String::isNotEmpty)?.let { BattleBundleLocation(Path.of(it).toAbsolutePath().normalize(), source) }
    explicit(systemProperties["limingjie.nn.bundle"], BattleBundleSource.SYSTEM_PROPERTY)?.let { return it }
    explicit(env["limingjie.nn.bundle"], BattleBundleSource.ENV_VAR)?.let { return it }
    explicit(env["LIMINGJIE_NN_BUNDLE"], BattleBundleSource.ENV_VAR)?.let { return it }
    explicit(configuredPath, BattleBundleSource.SETTING_PATH)?.let { return it }

    val relative = Path.of("models", "s4_m2b_r1_seed43")
    val inherited = defaultDataDirEnv?.trim()?.takeIf(String::isNotEmpty)?.let(Path::of)
    val appDirectory = appPath?.takeIf(String::isNotBlank)?.let { Path.of(it).toAbsolutePath().parent }
    val candidates = listOfNotNull(dataDirectory, inherited, defaultResolvedDataDir)
        .map { BattleBundleLocation(it.resolve(relative).toAbsolutePath().normalize(), BattleBundleSource.DEFAULT_DATA_DIR) } +
        listOfNotNull(appDirectory?.let { BattleBundleLocation(it.resolve(relative).normalize(), BattleBundleSource.APP_DIR) })
    return candidates.firstOrNull { Files.isRegularFile(it.path.resolve("manifest.json")) }
        ?: BattleBundleLocation((inherited ?: dataDirectory).resolve(relative).toAbsolutePath().normalize(), BattleBundleSource.UNRESOLVED)
}

/** Device-JVM owner of both native sessions. A session snapshots its rule scorer and setting. */
internal class DesktopBattleModel(
    private val log: (String) -> Unit,
    private val locationResolver: () -> BattleBundleLocation,
    private val onInfo: (String) -> Unit,
) : AutoCloseable {
    constructor(directory: () -> Path, log: (String) -> Unit) : this(
        log, { BattleBundleLocation(directory(), BattleBundleSource.SETTING_PATH) }, {},
    )

    private data class ContextKey(
        val questId: Int,
        val area: Int,
        val difficulty: Int,
        val ownedUnits: List<BattleRosterUnit>,
        val relicIds: List<Int>,
        val markStacks: List<Int>,
        val alphaRupee: Int?,
        val reviveAvailable: Boolean,
        val enemyIds: List<Int>?,
    ) {
        constructor(context: BattleValueContext) : this(
            context.questId, context.area, context.difficulty, context.ownedUnits.sortedBy { it.unitId },
            context.relicIds.toList(), context.markStacks.toList(), context.alphaRupee, context.reviveAvailable,
            context.enemyIds?.toList(),
        )
    }

    private data class ManifestFingerprint(val size: Long, val modified: FileTime, val sha256: String)
    private data class BundleVersion(val directory: Path, val fingerprint: ManifestFingerprint)
    private data class LoadedBundle(val version: BundleVersion, val id: String, val bundle: BattleOnnxBundle)

    private var loaded: LoadedBundle? = null
    private val cachedContexts = object : LinkedHashMap<ContextKey, EncodedBattleContext>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ContextKey, EncodedBattleContext>) = size > 16
    }
    private var lastWarning: String? = null
    private val mutableStatus = MutableStateFlow(BattleModelStatus())
    val status: StateFlow<BattleModelStatus> = mutableStatus.asStateFlow()
    val towerAInvocations: Int get() = status.value.towerAInvocations
    val fallbackReason: String? get() = status.value.let {
        if (it.health == BattleModelHealth.FAILED || it.health == BattleModelHealth.FALLBACK) it.detail else null
    }

    @Synchronized fun select(enabled: Boolean, rules: LabyrinthBattleValueEstimator): LabyrinthBattleValueEstimator {
        checkBundle(enabled, load = enabled)
        if (!enabled) return rules
        val active = loaded?.bundle
        val loadFailure = fallbackReason
        return object : ContextualBattleValueEstimator {
            override fun unitAffinities(context: BattleValueContext): Map<Int, Double>? =
                synchronized(this@DesktopBattleModel) {
                    if (active != null && active === loaded?.bundle) infer { encode(context, active::encodeContext).unitAffinities }
                    else null
                }

            override fun estimateWithSource(
                context: BattleValueContext,
                candidates: List<List<Int>>,
            ): BattleValueEstimate {
                val reason = synchronized(this@DesktopBattleModel) {
                    if (active == null || active !== loaded?.bundle) {
                        loadFailure ?: "战斗模型已关闭或重新加载"
                    } else {
                        val values = if (candidates.isEmpty()) emptyList() else infer {
                            active.scoreTeams(encode(context, active::encodeContext), context, candidates)
                        }
                        if (values != null) return BattleValueEstimate(values, BattleValueSource.MODEL)
                        fallbackReason
                    }
                }
                return BattleValueEstimate(rules.estimate(context, candidates), BattleValueSource.RULE, reason)
            }
        }
    }

    @Synchronized fun inspect(
        enabled: Boolean,
        forceReload: Boolean = false,
        ephemeralCheck: Boolean = false,
    ): BattleModelStatus = checkBundle(enabled, load = forceReload, forceReload = forceReload, ephemeralCheck = ephemeralCheck)

    private fun checkBundle(
        enabled: Boolean,
        load: Boolean,
        forceReload: Boolean = false,
        ephemeralCheck: Boolean = false,
    ): BattleModelStatus {
        var next = BattleModelStatus(enabled = enabled, towerAInvocations = towerAInvocations)
        val result = attempt(
            onFailure = { failure ->
                releaseBundle()
                if (enabled) failed(next, BattleModelHealth.FAILED, "加载", failure)
                else next.copy(detail = DesktopLog.redact("无法解析模型目录：${failure.message.orEmpty()}"))
            },
        ) {
            val location = locationResolver()
            val path = location.path.toAbsolutePath().normalize()
            next = next.copy(resolvedPath = path.toString(), source = location.source, overriddenByEnv = location.overriddenByEnv)
            val manifestPath = path.resolve("manifest.json")
            if (!enabled) {
                releaseBundle()
                lastWarning = null
                val detail = if (Files.isRegularFile(manifestPath)) "模型已就绪（当前开关关闭）"
                else "开关未开启；默认路径暂无模型文件"
                return@attempt next.copy(detail = detail)
            }
            require(Files.isDirectory(path)) { "未找到模型包目录：$path" }
            require(Files.isRegularFile(manifestPath)) { "缺少 manifest.json：$path" }
            val modified = Files.getLastModifiedTime(manifestPath)
            val bytes = Files.readAllBytes(manifestPath)
            val version = BundleVersion(path, ManifestFingerprint(bytes.size.toLong(), modified, sha256(bytes)))
            val current = loaded
            if (!forceReload && current?.version == version) {
                return@attempt next.copy(health = BattleModelHealth.LOADED, bundleId = current.id)
            }
            releaseBundle()
            val manifest = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            require(manifest.string("format") == BATTLE_BUNDLE_FORMAT) { "Unsupported battle bundle format" }
            val id = manifest["bundle_id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: path.fileName?.toString().orEmpty()
            next = next.copy(bundleId = id)
            val catalogPath = BattleOnnxBundle.catalogPath(path)
            require(Files.isRegularFile(catalogPath)) { "缺少模型特征文件：$catalogPath" }
            if (load) {
                val bundle = BattleOnnxBundle.load(path, catalogPath, manifest)
                if (ephemeralCheck) bundle.close() else loaded = LoadedBundle(version, id, bundle)
                lastWarning = null
                onInfo("战斗模型已加载：${id}（路径：${path}）")
            }
            next.copy(health = if (loaded == null) BattleModelHealth.READY else BattleModelHealth.LOADED)
        }
        mutableStatus.value = result
        return result
    }

    private inline fun <T> infer(block: () -> T): T? = attempt(
        onFailure = { failure ->
            mutableStatus.value = failed(status.value, BattleModelHealth.FALLBACK, "推理", failure)
            null
        },
    ) {
        block().also {
            lastWarning = null
            mutableStatus.value = status.value.copy(health = BattleModelHealth.LOADED, detail = null)
        }
    }

    private fun failed(before: BattleModelStatus, health: BattleModelHealth, stage: String, failure: Throwable): BattleModelStatus {
        val reason = DesktopLog.redact("战斗模型${stage}失败，使用规则评分（${failure.javaClass.simpleName}）：${failure.message.orEmpty()}")
        if (reason != lastWarning) log(reason)
        lastWarning = reason
        return before.copy(health = health, detail = reason)
    }

    private inline fun <T> attempt(onFailure: (Throwable) -> T, block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure(failure)
    } catch (failure: LinkageError) {
        onFailure(failure)
    }

    @Synchronized override fun close() {
        releaseBundle()
        lastWarning = null
        mutableStatus.value = status.value.copy(enabled = false, health = BattleModelHealth.DISABLED, bundleId = null, detail = null)
    }

    private fun releaseBundle() {
        val previous = loaded
        loaded = null
        cachedContexts.clear()
        previous?.bundle?.close()
    }

    // Native callers check the active bundle while holding the same lock.
    @Synchronized internal fun encode(
        context: BattleValueContext,
        encodeContext: (BattleValueContext) -> EncodedBattleContext,
    ): EncodedBattleContext {
        val key = ContextKey(context)
        cachedContexts[key]?.let { return it }
        return encodeContext(context).also { encoded ->
            cachedContexts[key] = encoded
            mutableStatus.value = status.value.copy(towerAInvocations = towerAInvocations + 1)
        }
    }
}
