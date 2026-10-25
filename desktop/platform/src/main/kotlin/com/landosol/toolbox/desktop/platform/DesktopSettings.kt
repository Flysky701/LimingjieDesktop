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
)
