package com.landosol.toolbox.labyrinth.kernel.event

import com.landosol.toolbox.gamedata.EventChoiceResource
import java.text.Normalizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable enum class RoleImprintScope {
    SPECIFIC_ROLE, NAMED_CLASS_RANDOM, RANDOM_CLASS_RANDOM, RANDOM_CLASS_SELECTABLE, ALL_RANDOM, ALL_SELECTABLE
}
@Serializable enum class UnknownGameEventPolicy {
    WAIT, SAFE_PARSE_OR_WAIT, FIRST_FREE_ENABLED, FIRST_ENABLED_SLOT;

    fun allows(choice: EventChoiceResource): Boolean = when (this) {
        WAIT -> false
        FIRST_ENABLED_SLOT -> true
        SAFE_PARSE_OR_WAIT, FIRST_FREE_ENABLED -> {
            val parsed = GameEventEffectParser.parse(choice.description, choice.conditionType, choice.conditionValue)
            if (this == SAFE_PARSE_OR_WAIT) parsed.safePositive else parsed.free
        }
    }
}

@Serializable sealed interface GameEventEffectPrimitive {
    val probability: Double
    @Serializable @SerialName("coin") data class AlphaCoin(val amount: Int, override val probability: Double = 1.0) : GameEventEffectPrimitive
    @Serializable @SerialName("relic") data class Relic(val minStars: Int, val maxStars: Int = minStars, val count: Int = 1,
        override val probability: Double = 1.0) : GameEventEffectPrimitive
    @Serializable @SerialName("imprint") data class RoleImprint(val scope: RoleImprintScope, val name: String = "",
        val count: Int = 1, val maxCount: Int = count, override val probability: Double = 1.0) : GameEventEffectPrimitive
    @Serializable @SerialName("battle") data class BattleChallenge(val extreme: Boolean, override val probability: Double = 1.0) : GameEventEffectPrimitive
    @Serializable @SerialName("continue") data class ChainContinue(override val probability: Double = 1.0) : GameEventEffectPrimitive
    @Serializable @SerialName("stop") data class ChainStop(override val probability: Double = 1.0) : GameEventEffectPrimitive
    @Serializable @SerialName("cost") data class CostCondition(val amount: Int?, override val probability: Double = 1.0) : GameEventEffectPrimitive
    @Serializable @SerialName("unknown") data class UnknownEffect(val text: String, override val probability: Double = 1.0) : GameEventEffectPrimitive
}

data class ParsedGameEventEffects(val primitives: List<GameEventEffectPrimitive>, val confidence: Double, val free: Boolean) {
    val safePositive get() = confidence >= 0.9 && free && primitives.isNotEmpty() && primitives.all {
        it.probability > 0 && when (it) {
            is GameEventEffectPrimitive.AlphaCoin -> it.amount > 0
            is GameEventEffectPrimitive.Relic -> it.count > 0
            is GameEventEffectPrimitive.RoleImprint -> it.count > 0
            else -> false
        }
    }
}

object GameEventEffectParser {
    private const val COIN = "(?:阿尔法金币|[αΑ]币|金币)"
    private val coin = Regex(COIN + "[×x*]?([0-9]+)|([0-9]+)(?:枚)?" + COIN)
    private val relic = Regex("★([1-3])(?:~★?([1-3]))?(?:迷宫)?遗物(?:[×x*]([0-9]+))?")
    private val imprint = Regex("(全体|随机(?:职阶|职能)|[\\p{IsHan}]{1,4}(?:型(?:职能|职阶)?|职阶|职能|者))(?:的)?(随机|选择|自选)印记(?:[×x*]([0-9]+)(?:~([0-9]+))?)?")
    private val join = Regex("([\\p{IsHan}]{1,10})(?:成为伙伴|加入队伍|加入)")
    private val battle = Regex("(?:向)?(?:战斗格子\\()?(普通(?:难度)?|极难)(?:\\)的敌人发起挑战|战斗)")
    private val advance = Regex("(?:并)?(?:进入下一个事件|继续下一段)")
    private val end = Regex("挑战结束|结束挑战|结束")
    private val cost = Regex("(?:消耗|支付|花费|扣除)(?:" + COIN + ")?[×x*]?([0-9]+)?(?:" + COIN + ")?")
    private val chance = Regex("(?:有)?([0-9]+)%(?:的概率|概率|时)?")
    private val filler = Regex("可获得|获得|免费领取|免费|领取|[+，,。；;！!：:]")

    private val tokens = listOf(chance, filler, cost, coin, relic, imprint, battle, advance, end, join)

    fun parse(text: String, conditionType: Int = 0, conditionValue: Int = 0): ParsedGameEventEffects {
        val lines = text.take(2000).lineSequence().filterNot { it.trim() == "选择" }.joinToString("")
        val normalized = Normalizer.normalize(lines, Normalizer.Form.NFKC).replace(Regex("\\s+"), "")
        val result = mutableListOf<GameEventEffectPrimitive>()
        var probability = 1.0
        var i = 0
        var unmatched = StringBuilder()
        fun flushUnknown() {
            if (unmatched.isNotEmpty()) {
                result += GameEventEffectPrimitive.UnknownEffect(unmatched.toString())
                unmatched = StringBuilder()
            }
        }
        while (i < normalized.length) {
            val match = tokens
                .firstNotNullOfOrNull { pattern -> pattern.matchAt(normalized, i)?.let { pattern to it } }
            if (match == null) { unmatched.append(normalized[i++]); continue }
            flushUnknown()
            val (pattern, token) = match
            val g = token.groupValues
            fun number(index: Int, fallback: Int = 1) = g[index].toIntOrNull() ?: fallback
            when (pattern) {
                chance -> probability = number(1, 0).takeIf { it in 0..100 }?.div(100.0) ?: 0.0
                cost -> result += GameEventEffectPrimitive.CostCondition(g[1].toIntOrNull())
                coin -> result += GameEventEffectPrimitive.AlphaCoin((g[1] + g[2]).toIntOrNull() ?: 0, probability)
                relic -> result += GameEventEffectPrimitive.Relic(number(1), number(2, number(1)), number(3), probability)
                imprint -> {
                    val random = g[2] == "随机"
                    val scope = when {
                        g[1] == "全体" -> if (random) RoleImprintScope.ALL_RANDOM else RoleImprintScope.ALL_SELECTABLE
                        g[1].startsWith("随机") -> if (random) RoleImprintScope.RANDOM_CLASS_RANDOM else RoleImprintScope.RANDOM_CLASS_SELECTABLE
                        else -> RoleImprintScope.NAMED_CLASS_RANDOM
                    }
                    val name = if (scope == RoleImprintScope.NAMED_CLASS_RANDOM)
                        g[1].substringBefore("型").substringBefore("职阶").substringBefore("职能") else g[1]
                    result += GameEventEffectPrimitive.RoleImprint(scope, name, number(3), number(4, number(3)), probability)
                }
                join -> result += GameEventEffectPrimitive.RoleImprint(RoleImprintScope.SPECIFIC_ROLE, g[1], probability = probability)
                battle -> result += GameEventEffectPrimitive.BattleChallenge(g[1] == "极难", probability)
                advance -> result += GameEventEffectPrimitive.ChainContinue(probability)
                end -> result += GameEventEffectPrimitive.ChainStop(probability)
            }
            i = token.range.last + 1
        }
        flushUnknown()
        if (conditionType != 0 || conditionValue > 0) result += GameEventEffectPrimitive.CostCondition(conditionValue.takeIf { it > 0 })
        val understood = result.isNotEmpty() && result.none { it is GameEventEffectPrimitive.UnknownEffect }
        val complete = text.length <= 2000
        val free = complete && (understood || normalized in setOf("免费", "免费领取")) &&
            result.none { it is GameEventEffectPrimitive.CostCondition }
        return ParsedGameEventEffects(result, if (understood && complete) 1.0 else 0.0, free)
    }
}
