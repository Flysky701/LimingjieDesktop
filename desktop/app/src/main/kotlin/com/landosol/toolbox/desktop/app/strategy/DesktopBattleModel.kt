package com.landosol.toolbox.desktop.app.strategy

import com.landosol.toolbox.desktop.shared.DesktopLog
import com.landosol.toolbox.labyrinth.kernel.strategy.*
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Device-JVM owner of both native sessions. A session snapshots its rule scorer and setting. */
internal class DesktopBattleModel(
    private val directory: () -> Path,
    private val log: (String) -> Unit,
) : AutoCloseable {
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

    private var bundle: BattleOnnxBundle? = null
    private var bundleDirectory: Path? = null
    private var cachedContext: Pair<ContextKey, EncodedBattleContext>? = null
    @Volatile var towerAInvocations: Int = 0
        private set
    @Volatile var fallbackReason: String? = null
        private set

    @Synchronized fun select(enabled: Boolean, rules: LabyrinthBattleValueEstimator): LabyrinthBattleValueEstimator {
        if (!enabled) {
            close()
            fallbackReason = null
            return rules
        }
        val active = attempt("加载") {
            val path = directory().toAbsolutePath().normalize()
            if (bundleDirectory != path) {
                close()
                bundle = BattleOnnxBundle.load(path)
                bundleDirectory = path
            }
            checkNotNull(bundle)
        }
        val loadFailure = fallbackReason
        return object : ContextualBattleValueEstimator {
            override fun unitAffinities(context: BattleValueContext): Map<Int, Double>? =
                synchronized(this@DesktopBattleModel) {
                    if (active != null && active === bundle) {
                        attempt("推理") { encode(active, context).unitAffinities }
                    } else null
                }

            override fun estimateWithSource(
                context: BattleValueContext,
                candidates: List<List<Int>>,
            ): BattleValueEstimate {
                val reason = synchronized(this@DesktopBattleModel) {
                    if (active != null && active === bundle) {
                        val values = attempt("推理") {
                            if (candidates.isEmpty()) emptyList()
                            else active.scoreTeams(encode(active, context), context, candidates)
                        }
                        if (values != null) return BattleValueEstimate(values, BattleValueSource.MODEL)
                        fallbackReason
                    } else loadFailure ?: "战斗模型已关闭或重新加载"
                }
                return BattleValueEstimate(rules.estimate(context, candidates), BattleValueSource.RULE, reason)
            }
        }
    }

    // Called with the owner locked so a cache entry always belongs to the active native sessions.
    private fun encode(active: BattleOnnxBundle, context: BattleValueContext): EncodedBattleContext {
        val key = ContextKey(context)
        cachedContext?.let { (previousKey, encoded) -> if (previousKey == key) return encoded }
        return active.encodeContext(context).also { encoded ->
            towerAInvocations++
            cachedContext = key to encoded
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
        val previous = bundle
        bundle = null
        bundleDirectory = null
        cachedContext = null
        previous?.close()
    }
}
