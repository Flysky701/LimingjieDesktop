package com.landosol.toolbox.desktop.app.control

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** A single worker incarnation. UI callbacks must capture this port, never resolve the active tab later. */
interface ControlSessionPort {
    val instanceId: String
    val workerRunId: String
    val snapshots: StateFlow<JsonObject>
    val connection: StateFlow<WorkerConnectionStatus>
    val error: StateFlow<String?>
    suspend fun request(op: String, args: JsonObject = JsonObject(emptyMap())): JsonObject
    fun setSelected(selected: Boolean)
    suspend fun shutdown()
}

/** Closed domain operation names. Responses are JSON; failures are thrown with a user-readable reason. */
object ControlOps {
    const val START="task.start" // task: DesktopTask.name; goals?:[{guildId,targetCount,mode}]; guildId?; retreatConfirmed:Boolean
    const val STOP="task.stop" // reason?:String
    const val PAUSE="task.pause"
    const val SAVE_SETUP="setup.save" // settings:LabyrinthRerollSettings, goals:[{guildId,count,clears}]
    const val SAVE_REROLL="reroll.save" // value:LabyrinthRerollSettings
    const val DISMISS_MESSAGE="task.dismissMessage"
    const val PREFS_PATCH="preferences.patch" // only changed DesktopPreferences constructor fields
    const val SETTINGS_PATCH="settings.patch" // only changed DesktopSettings constructor fields
    const val ACCOUNT_NEW="account.new"
    const val ACCOUNT_EDIT="account.edit" // id:Long; reply editor:AccountEditorState (password always empty)
    const val ACCOUNT_SAVE="account.save" // editor:AccountEditorState; worker performs original validation
    const val ACCOUNT_SELECT="account.select" // id:Long
    const val ACCOUNT_DELETE="account.delete" // id:Long (UI has already shown deletion confirmation)
    const val ACCOUNT_DISMISS="account.dismiss"
    const val STRATEGY_SAVE="strategy.save" // value:LabyrinthStrategySettings
    const val CATALOG="catalog.load" // reply items:[LabyrinthRoleRatingItem]
    const val DEVICE_DISCOVER="device.discover" // force:Boolean
    const val DEVICE_CONNECT="device.connect" // serial:String,reconnect:Boolean
    const val DEVICE_DISCONNECT="device.disconnect"
    const val DEVICE_CANCEL="device.cancel"
    const val ADB_CONFIGURE="device.adb.configure" // path:String; reply location:AdbLocation
    const val ADB_DOWNLOAD="device.adb.download" // reply location:AdbLocation
    const val DEVICE_TEST="device.testTap" // worker enforces non-game foreground restriction
    const val REFRESH="environment.refresh" // resolution,foreground,database; worker observes its own busy gates
    const val FOREGROUND="device.foreground" // reply value:String?
    const val PREVIEW="preview.start"
    const val CAPTURE_STOP="capture.stop"
    const val DATABASE_UPDATE="database.update" // reply message:String
    const val NETWORK_TEST="network.test" // reply message:String
    const val ENCRYPTION_TEST="encryption.test" // reply message:String
    const val LOG_CLEAR="log.clear"
    const val LOG_ADD="log.add" // text:String,kind:UserLogKind.name
    const val DIAGNOSTICS_EXPORT="diagnostics.export" // path:String (chosen by UI); worker writes its own diagnostic ZIP
    const val OPEN_DATA="files.openData"
    const val OPEN_LOGS="files.openLogs"
    const val OPEN_CRASH="files.openCrash" // name:String only; never an arbitrary path
    const val CAPTCHA_CANCEL="captcha.cancel" // ticketId:String
}
