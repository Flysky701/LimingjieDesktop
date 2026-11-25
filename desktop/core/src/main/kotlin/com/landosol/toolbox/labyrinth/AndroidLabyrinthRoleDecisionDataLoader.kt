package com.landosol.toolbox.labyrinth

import android.content.Context

class AndroidLabyrinthRoleDecisionDataLoader(
    private val context: Context,
) {
    fun load(): LabyrinthRoleDecisionDataResult = runCatching {
        context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
    }.fold(
        onSuccess = LabyrinthRoleDecisionDataParser::parse,
        onFailure = { failure ->
            LabyrinthRoleDecisionDataResult.Unavailable(
                "未加载角色决策资料 $ASSET_PATH：${failure.message ?: "文件不存在"}",
            )
        },
    )

    companion object {
        const val ASSET_PATH = "resource-packs/cn-bilibili/labyrinth-role-decision.json"
    }
}
