package com.landosol.toolbox.desktop.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalControlVisible=staticCompositionLocalOf{true}
internal val LocalControlDirty=staticCompositionLocalOf<(String,Boolean)->Unit>{{_,_->}}
internal data class ControlKeyActions(val start:()->Unit,val pause:()->Unit,val stop:()->Unit,val search:()->Unit)
internal val LocalControlKeyActions=staticCompositionLocalOf<(ControlKeyActions)->Unit>{{}}

