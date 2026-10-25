package com.landosol.toolbox.desktop.app.adapters.storage

import com.landosol.toolbox.desktop.shared.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

enum class DesktopThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("Light Modern"), DARK("Dark Modern") }
data class DesktopPreferences(
    val theme: DesktopThemeMode = DesktopThemeMode.SYSTEM,
    val largeText: Boolean = false,
    val notifications: Boolean = true,
    val sound: Boolean = false,
    val originalRatings: Boolean = false,
    val page: String = "Run",
    val rightTab: String = "logs",
    val previewExpanded: Boolean = false,
    val animationConfirmedVersion: String = "",
    val goals: List<DesktopGoal> = emptyList(),
    val selectedTask: String = "BATCH",
)
data class DesktopGoal(val guildId: Int, val count: Int, val clears: Boolean = true)

class DesktopPreferencesStore(directory: Path, private val notice: (String) -> Unit) {
    private val file = directory.resolve("settings/ui.json")
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    private fun load(): DesktopPreferences = try {
        if (!Files.exists(file)) DesktopPreferences() else JSONObject(Files.readString(file)).let { j ->
            DesktopPreferences(DesktopThemeMode.entries.firstOrNull { it.name == j.optString("theme") } ?: DesktopThemeMode.SYSTEM,
                j.optBoolean("largeText"), j.optBoolean("notifications", true), j.optBoolean("sound"), j.optBoolean("originalRatings"),
                j.optString("page", "Run"), j.optString("rightTab", "logs"), j.optBoolean("previewExpanded"), j.optString("animationConfirmedVersion"),
                j.optJSONArray("goals")?.let { a -> (0 until a.length()).mapNotNull { i ->
                    val g = a.getJSONObject(i); val count = g.optInt("count")
                    if (count > 0) DesktopGoal(g.getInt("guildId"), count, g.optBoolean("clears", true)) else null
                } }.orEmpty(), j.optString("selectedTask", "BATCH"))
        }
    } catch (_: Exception) { notice("界面偏好无法读取，使用默认值；原文件保留。请在设置中重新选择。") ; DesktopPreferences() }
    @Synchronized fun update(change: (DesktopPreferences) -> DesktopPreferences) {
        val value = change(mutable.value)
        val j = JSONObject().put("theme", value.theme.name).put("largeText", value.largeText).put("notifications", value.notifications)
            .put("sound", value.sound).put("originalRatings", value.originalRatings).put("page", value.page).put("rightTab", value.rightTab)
            .put("previewExpanded", value.previewExpanded).put("animationConfirmedVersion", value.animationConfirmedVersion)
            .put("goals", JSONArray(value.goals.map { JSONObject().put("guildId", it.guildId).put("count", it.count).put("clears", it.clears) }))
            .put("selectedTask", value.selectedTask)
        AtomicFiles.write(file, j.toString(2).toByteArray())
        mutable.value = value
    }
}
