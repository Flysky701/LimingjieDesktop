package com.landosol.toolbox.desktop.app.strategy

import com.landosol.toolbox.desktop.shared.DesktopLog
import com.landosol.toolbox.labyrinth.kernel.strategy.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Device-JVM owner of both native sessions. A session snapshots its rule scorer and setting. */
internal class DesktopBattleModel(
    private val directory: () -> Path = {
        Path.of(requireNotNull(modelPath("limingjie.nn.bundle")) { "缺少 limingjie.nn.bundle" })
    },
    private val log: (String) -> Unit,
) : AutoCloseable {
    private var bundle: BattleOnnxBundle? = null
    @Volatile var fallbackReason: String? = null
        private set

    @Synchronized fun select(enabled: Boolean, rules: LabyrinthBattleValueEstimator): LabyrinthBattleValueEstimator {
        if (!enabled) {
            close()
            fallbackReason = null
            return rules
        }
        if (bundle == null) bundle = attempt("加载") {
            BattleOnnxBundle.load(directory())
        }
        return LabyrinthBattleValueEstimator { context, teams ->
            predict(context, teams) ?: rules.estimate(context, teams)
        }
    }

    @Synchronized private fun predict(context: BattleValueContext, candidates: List<List<Int>>): List<BattleValue>? {
        val active = bundle ?: return null
        return attempt("推理") {
            candidates.chunked(32).flatMap { teams -> active.predict(teams.map { active.features.row(context, it) }) }
        }
    }

    private inline fun <T> attempt(stage: String, block: () -> T): T? {
        val failure = try {
            return block().also { fallbackReason = null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            e
        } catch (e: LinkageError) {
            e
        }
        val reason = DesktopLog.redact("战斗模型${stage}失败，使用规则评分（${failure.javaClass.simpleName}）：${failure.message.orEmpty()}")
        if (reason != fallbackReason) log(reason)
        fallbackReason = reason
        return null
    }

    @Synchronized override fun close() {
        bundle?.close()
        bundle = null
    }
}
