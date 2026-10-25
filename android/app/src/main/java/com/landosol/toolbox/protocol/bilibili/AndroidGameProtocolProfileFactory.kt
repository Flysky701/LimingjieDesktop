package com.landosol.toolbox.protocol.bilibili

import android.content.Context
import android.os.Build

/**
 * 协议层的游戏包不再固定为 B 服：由调用方传入已确认的渠道包名
 * （账号所属服务器的客户端，见 GameServer），使渠道服客户端也能提供 APP-VER。
 */
class AndroidGameProtocolProfileFactory(
    private val context: Context,
    private val gamePackageName: String,
    /** 资源密钥：B 服与渠道服不同。 */
    private val resKey: String = RES_KEY,
    /** PLATFORM-ID：B 服 2，渠道服 4。 */
    private val platformId: String = "2",
) {
    fun create(): GameProtocolProfile {
        val packageInfo = context.packageManager.getPackageInfo(gamePackageName, 0)
        val appVersion = packageInfo.versionName.orEmpty()
            .ifBlank { error("无法读取游戏版本：$gamePackageName") }
        val model = listOf(Build.BRAND, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
        return GameProtocolProfile(
            appVersion = appVersion,
            headers = linkedMapOf(
                "Accept-Encoding" to "gzip",
                "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android ${Build.VERSION.RELEASE})",
                "X-Unity-Version" to UNITY_VERSION,
                "APP-VER" to appVersion,
                "BATTLE-LOGIC-VERSION" to "4",
                "BUNDLE-VER" to "",
                "DEVICE" to "2",
                "DEVICE-NAME" to model.ifBlank { "Android" },
                "EXCEL-VER" to "1.0.0",
                "GRAPHICS-DEVICE-NAME" to "Android",
                "IP-ADDRESS" to "",
                "KEYCHAIN" to "",
                "LOCALE" to "CN",
                "PLATFORM-OS-VERSION" to "Android ${Build.VERSION.RELEASE} / API-${Build.VERSION.SDK_INT}",
                "REGION-CODE" to "",
                "RES-KEY" to resKey,
                "RES-VER" to DEFAULT_RES_VERSION,
                "SHORT-UDID" to "0",
                "PLATFORM" to "2",
                "PLATFORM-ID" to platformId,
                "CHANNEL-ID" to "1",
            ),
        )
    }

    private companion object {
        const val UNITY_VERSION = "2021.3.20f1c1"
        const val RES_KEY = "ab00a0a6dd915a052a2ef7fd649083e5"
        const val DEFAULT_RES_VERSION = "10002200"
    }
}
