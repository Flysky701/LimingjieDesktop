package com.landosol.toolbox.desktop.app.ui

import com.landosol.toolbox.desktop.app.control.ControlSessionPort

/** A device runtime only. Shared account selection is never used as its binding. */
data class DeviceRunTab(
    val id:String,
    val name:String,
    val session:ControlSessionPort,
    val deviceId:String,
    val serial:String,
    val boundAccountId:Long?=null,
)

/** The supervisor supplies the complete, deduplicated device inventory. */
data class ManagedDeviceUi(
    val id:String,
    val serial:String,
    val name:String,
    val sessionId:String?=null,
    val boundAccountId:Long?=null,
    val connected:Boolean=false,
    val busy:Boolean=false,
    val status:String="未连接",
    val connecting:Boolean=false,
)

data class SharedRunShellCallbacks(
    val selectSession:(String)->Unit,
    val discover:suspend (Boolean)->Unit,
    val connect:(String)->Unit,
    val bindAccount:(String,Long?)->Unit,
    val disconnect:(String)->Unit,
    val closeSession:(String)->Unit,
    val reconnectSession:(String)->Unit,
    val stopAll:()->Unit,
    val onDirtyChanged:(String,Boolean)->Unit={_,_->},
)

/** Dirty state reported for the single shared editor, independently of device tabs. */
const val SHARED_UI_DIRTY_ID="shared-ui"
