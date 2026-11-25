package com.landosol.toolbox.labyrinth

enum class LabyrinthPersonalScoreFilter(val label: String) {
    ALL("全部评分"), RATED("仅看已修改"), UNRATED("未设个人评分");

    fun includes(hasPersonalScore: Boolean): Boolean = when (this) {
        ALL -> true
        RATED -> hasPersonalScore
        UNRATED -> !hasPersonalScore
    }
}
