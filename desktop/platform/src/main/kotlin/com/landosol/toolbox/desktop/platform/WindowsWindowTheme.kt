package com.landosol.toolbox.desktop.platform

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

/** Native decorations retain Windows window controls while following the app palette. */
object WindowsWindowTheme {
    private val dwm by lazy { Native.load("dwmapi", DwmApi::class.java) }

    fun apply(window: Window, dark: Boolean, captionArgb: Int, textArgb: Int) {
        if (!Platform.isWindows() || !window.isDisplayable) return
        val handle = Native.getWindowPointer(window)
        // Unsupported attributes return an HRESULT; older Windows keeps its native colors.
        dwm.DwmSetWindowAttribute(handle, 20, IntByReference(if (dark) 1 else 0), Int.SIZE_BYTES)
        dwm.DwmSetWindowAttribute(handle, 35, IntByReference(colorRef(captionArgb)), Int.SIZE_BYTES)
        dwm.DwmSetWindowAttribute(handle, 36, IntByReference(colorRef(textArgb)), Int.SIZE_BYTES)
    }

    private fun colorRef(argb: Int): Int =
        ((argb ushr 16) and 0xff) or (argb and 0xff00) or ((argb and 0xff) shl 16)
}

// https://learn.microsoft.com/windows/win32/api/dwmapi/ne-dwmapi-dwmwindowattribute
internal interface DwmApi : StdCallLibrary {
    fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: IntByReference, size: Int): Int
}
