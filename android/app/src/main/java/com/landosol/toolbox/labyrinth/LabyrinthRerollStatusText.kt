package com.landosol.toolbox.labyrinth

fun labyrinthRerollStatusText(state: LabyrinthUiState, now: Long = System.currentTimeMillis()): String {
    val guild = state.guildOptions.firstOrNull { it.guildId == state.selectedGuildId }?.name ?: "未选择公会"
    val seconds = state.startedAtMillis?.let { ((now - it).coerceAtLeast(0) / 1000) } ?: 0
    val stage = when {
        state.captcha != null -> "需要验证码，请返回应用处理"
        state.isWorking -> state.progress ?: "准备刷取"
        else -> state.message ?: "就绪"
    }
    return "$guild · 难度${state.selectedDifficulty} · ${seconds / 60}分${seconds % 60}秒\n$stage"
}
