package com.landosol.toolbox.desktop.platform

import kotlinx.serialization.Serializable

@Serializable enum class NetworkMode { SYSTEM, DIRECT }
@Serializable enum class CloseBehavior { ASK, MINIMIZE, STOP_AND_EXIT }
@Serializable enum class PreferredTouchMode { MAATOUCH, COMPATIBILITY }
@Serializable data class DesktopSettings(
    val adbPath: String = "",
    val lastDevice: String = "",
    val touchMode: PreferredTouchMode = PreferredTouchMode.MAATOUCH,
    val closeBehavior: CloseBehavior = CloseBehavior.ASK,
    val networkMode: NetworkMode = NetworkMode.SYSTEM,
    val windowWidth: Int = 1280,
    val windowHeight: Int = 800,
    val windowX: Int? = null,
    val windowY: Int? = null,
    val wizardCompleted: Boolean = false,
    val gameSettingsConfirmed: Boolean = false,
    val battleOnnxEnabled: Boolean = false,
    val battleOnnxBundlePath: String = "",
    val pathBPlanningEnabled: Boolean = false,
    val battleModelStyle: String = "BALANCED",
)

@Serializable enum class DesktopThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("Light Modern"), DARK("Dark Modern") }
@Serializable data class DesktopPreferences(
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
@Serializable data class DesktopGoal(val guildId: Int, val count: Int, val clears: Boolean = true)

@Serializable
data class AppConfig(
    val environment: DesktopSettings = DesktopSettings(),
    val ui: DesktopPreferences = DesktopPreferences(),
    // Workers inherit shared task defaults until preferences have first been saved locally.
    val uiPreferencesSaved: Boolean = false,
)
