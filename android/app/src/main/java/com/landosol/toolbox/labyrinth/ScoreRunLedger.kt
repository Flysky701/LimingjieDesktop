package com.landosol.toolbox.labyrinth

import android.content.Context

/** Worker-local counters survive reconnects. Account, opening and node identify one visit. */
class ScoreRunLedger(context: Context) {
    private val prefs by lazy { context.getSharedPreferences("score-run-limits", Context.MODE_PRIVATE) }

    fun refreshes(account: Long, enter: Long, node: Long): Int {
        val key = "shop-$account"
        if (prefs.getString(key, null) != "$enter/$node") return 0
        // A reconnect between the confirm tap and the stock observation cannot establish
        // whether the game charged the fee. Do not issue another refresh in that visit.
        return if (prefs.getBoolean("$key-pending", false)) Int.MAX_VALUE else prefs.getInt("$key-count", 0)
    }

    fun refreshPending(account: Long, enter: Long, node: Long) {
        val key = "shop-$account"
        val count = if (prefs.getString(key, null) == "$enter/$node") prefs.getInt("$key-count", 0) else 0
        check(prefs.edit().putString(key, "$enter/$node").putInt("$key-count", count)
            .putBoolean("$key-pending", true).commit()) { "商店刷新状态保存失败" }
    }

    fun refreshConfirmed(account: Long, enter: Long, node: Long) {
        val key = "shop-$account"
        if (prefs.getString(key, null) != "$enter/$node" || !prefs.getBoolean("$key-pending", false)) return
        check(prefs.edit().putInt("$key-count", prefs.getInt("$key-count", 0) + 1)
            .putBoolean("$key-pending", false).commit()) { "商店刷新结果保存失败" }
    }

    // At most two event-triggered restarts per ten minutes; a retry cannot consume the same opening twice.
    fun allowEventRestart(account: Long, enter: Long, now: Long): Boolean {
        val key = "event-$account"
        val start = prefs.getLong("$key-start", 0)
        val sameWindow = start > 0 && now - start in 0L until 600_000L
        val used = if (sameWindow) prefs.getInt("$key-count", 0) else 0
        if (used >= 2 || prefs.getLong("$key-enter", 0) == enter) return false
        check(prefs.edit().putLong("$key-start", if (sameWindow) start else now)
            .putLong("$key-enter", enter).putInt("$key-count", used + 1).commit()) { "凹分事件预算保存失败" }
        return true
    }
}
