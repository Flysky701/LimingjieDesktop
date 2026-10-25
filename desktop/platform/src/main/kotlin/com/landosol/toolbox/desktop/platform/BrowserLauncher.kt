package com.landosol.toolbox.desktop.platform

import java.awt.Desktop
import java.net.URI

object BrowserLauncher {
    /** FB-05: Desktop.browse, then the Windows URL handler; the caller shows the URL if both fail. */
    fun open(url: String): Boolean {
        val uri = URI(url)
        require(uri.scheme == "http" || uri.scheme == "https") { "只支持 HTTP 或 HTTPS 浏览器地址" }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri)
                return true
            }
        } catch (_: Exception) { /* The documented Windows URL-handler fallback follows. */ }
        if (!WindowsDpapi.available) return false
        return try {
            ProcessBuilder("rundll32.exe", "url.dll,FileProtocolHandler", uri.toASCIIString()).start()
            true
        } catch (_: java.io.IOException) { false }
    }
}
