package com.landosol.toolbox.desktop.app.adapters.storage

import com.landosol.toolbox.core.config.select
import com.landosol.toolbox.desktop.platform.AppConfig
import java.nio.file.Path

typealias DesktopPreferences = com.landosol.toolbox.desktop.platform.DesktopPreferences
typealias DesktopThemeMode = com.landosol.toolbox.desktop.platform.DesktopThemeMode
typealias DesktopGoal = com.landosol.toolbox.desktop.platform.DesktopGoal

class DesktopPreferencesStore(private val app: AppConfigStore) {
    constructor(directory: Path, notice: (String) -> Unit) :
        this(AppConfigStore(directory) { notice("配置损坏，原文件已备份为 ${it.fileName}") })

    val state = app.state.select(AppConfig::ui)

    fun update(change: (DesktopPreferences) -> DesktopPreferences) {
        app.update { it.copy(ui = change(it.ui), uiPreferencesSaved = true) }
    }
}
