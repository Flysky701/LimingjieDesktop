package com.landosol.toolbox.labyrinth.vision

enum class LabyrinthShopRefreshDialogKind { REFRESH, EXIT, UNKNOWN }

/** Semantic evidence only. A generic confirmation button never sets confirmed by itself. */
data class LabyrinthShopRefreshDialog(
    val kind: LabyrinthShopRefreshDialogKind = LabyrinthShopRefreshDialogKind.UNKNOWN,
    val fee: Int? = null,
    val confirmed: Boolean = false,
    val text: String? = null,
    val evidenceId: Long? = null,
    val capturedAtMillis: Long = Long.MIN_VALUE,
) {
    fun authorizesRefresh(expectedFee: Int): Boolean = kind == LabyrinthShopRefreshDialogKind.REFRESH &&
        confirmed && fee == expectedFee && expectedFee in setOf(300, 600)
}

/** Whole title + body text, deliberately excluding the blue generic button and wallet bar. */
object LabyrinthShopRefreshDialogText {
    fun parse(text: String?, confirmations: Int = 1): LabyrinthShopRefreshDialog {
        val value = normalize(text)
        // EXIT wins even when the message also mentions refreshing. Never reuse the old visual
        // EXIT_CONFIRMATION label to decide this: the refresh modal has the same generic structure.
        val exit = Regex("(?:关闭|离开|退出|结束)(?:此|该|当前|这家|这个)?的?商店|商店(?:关闭|退出)|结束购物").containsMatchIn(value)
        if (exit) return LabyrinthShopRefreshDialog(
            kind = LabyrinthShopRefreshDialogKind.EXIT,
            confirmed = confirmations >= 2,
            text = text,
        )
        if (!value.contains("更新") && !value.contains("刷新")) return LabyrinthShopRefreshDialog(text = text)

        // A named fee is stronger than another amount (e.g. 持有余额) in the same modal. If there
        // is no fee label, accept only a single numeric amount; conflicting fees fail closed.
        val namedFees = Regex("(?:消耗|花费|费用|需要|使用|支付|扣除|耗费)(?:为|是|:|：)?(?:阿尔法)?(?:金币)?([0-9][0-9,]*)")
            .findAll(value).mapNotNull { it.groupValues[1].replace(",", "").toIntOrNull() }.toList()
        val amounts = if (namedFees.isNotEmpty()) namedFees else Regex("[0-9][0-9,]*")
            .findAll(value).mapNotNull { it.value.replace(",", "").toIntOrNull() }.toList()
        val fee = amounts.distinct().singleOrNull()?.takeIf { it == 300 || it == 600 }
        return LabyrinthShopRefreshDialog(
            kind = LabyrinthShopRefreshDialogKind.REFRESH,
            fee = fee,
            confirmed = confirmations >= 2 && fee != null,
            text = text,
        )
    }

    /** Live modal fees come only from their numeric ROI; never fall back to body/balance amounts. */
    fun parseRegions(text: String?, confirmations: Int, feeText: String?, feeConfirmations: Int): LabyrinthShopRefreshDialog {
        val semantic = parse(text, confirmations)
        if (semantic.kind != LabyrinthShopRefreshDialogKind.REFRESH) return semantic
        val fee = normalize(feeText).takeIf { it == "300" || it == "600" }?.toIntOrNull()
        return semantic.copy(fee = fee, confirmed = confirmations >= 2 && feeConfirmations >= 2 && fee != null)
    }

    private fun normalize(text: String?): String = buildString {
        for (c in text.orEmpty()) {
            if (c.isWhitespace()) continue
            append(when {
                c in '０'..'９' -> '0' + (c - '０')
                c == '，' -> ','
                c == '關' -> '关'
                c == '閉' -> '闭'
                c == '離' -> '离'
                c == '開' -> '开'
                c == '幣' -> '币'
                else -> c
            })
        }
    }
}
