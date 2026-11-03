package com.landosol.toolbox.desktop.app.control

/** Same launch policy as jpackage; workers launched from a development JVM need it too. */
internal object DesktopJvmOptions {
    val arguments: List<String> = checkNotNull(javaClass.getResourceAsStream("/desktop/jvm-options.txt")) {
        "缺少桌面运行参数"
    }.bufferedReader(Charsets.UTF_8).useLines { lines ->
        lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
    }
}
