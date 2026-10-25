package com.landosol.toolbox.desktop.platform

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt

object WindowsDpapi {
    val available: Boolean get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    fun protect(plaintext: ByteArray, entropy: ByteArray): ByteArray {
        require(available) { "凭据存储仅支持 Windows" }
        return Crypt32Util.cryptProtectData(plaintext, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "LimingjieAssistant", null)
    }
    fun unprotect(blob: ByteArray, entropy: ByteArray): ByteArray {
        require(available) { "凭据存储仅支持 Windows" }
        return Crypt32Util.cryptUnprotectData(blob, entropy, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
    }
}
