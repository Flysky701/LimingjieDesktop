package com.landosol.toolbox.desktop.device

import kotlinx.coroutines.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Explicit manual L1 check. Only Android Settings is launched or touched; no game/account data. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 4 && args.take(3).all(String::isNotBlank)) { "Specify probeAdb, probeSerial and probeOutput explicitly" }
    val output = Path.of(args[2]).also(Files::createDirectories)
    val mode = args[3]
    require(mode in setOf("inspect", "actions", "settings", "compatibility", "soak", "connect-only", "identity-only", "discovery-only"))
    val messages = mutableListOf<String>()
    fun record(value: String) { synchronized(messages) { messages += value }; println(value) }
    val adb = ProcessAdbExecutor(args[0], server = if(mode in setOf("connect-only", "identity-only")) AdbServerClient() else null)
    suspend fun shell(vararg command: String) = adb.shell(args[1], command.toList()).requireSuccess().text()
    if (mode == "discovery-only") {
        try {
            MuMuDeviceDiscovery.registerRunning(checkNotNull(AdbLocator.find(args[0])), ::record)
            AdbDeviceService(adb, output.resolve("isolated-data"), emptySet()).use { service ->
                record("discovered=" + service.discover().map { it.serial to it.label })
            }
        } finally { adb.close(); Files.writeString(output.resolve("discovery-only.log"), synchronized(messages) { messages.joinToString("\n") }) }
        return@runBlocking
    }
    val packages = System.getProperty("limingjie.probe.packages", "").split(',').filter(String::isNotBlank).toSet()
    if (mode == "identity-only") {
        // Read public installation metadata only. Existing device/worker ownership is untouched.
        try {
            val size = DeviceParsers.screenSize(shell("wm", "size"))
            repeat(2) { index ->
                val start = System.nanoTime()
                val identity = DeviceIdentityReader(adb, output.resolve("isolated-data"), packages, ::record).read(args[1], size)
                record("identityRead=${index+1} millis=${(System.nanoTime()-start)/1_000_000} packages=${identity.packages.size}")
            }
        } finally { adb.close(); Files.writeString(output.resolve("identity-only.log"), synchronized(messages) { messages.joinToString("\n") }) }
        return@runBlocking
    }
    val service = AdbDeviceService(adb, output.resolve("isolated-data"), packages, ::record, { record("LOST: $it") })
    try {
        record("mode=$mode timestamp=${java.time.Instant.now()}")
        val located = AdbLocator.find(args[0])
        record("locator=${located?.source}; version=${located?.version?.lineSequence()?.firstOrNull()}")
        record("discovered=${service.discover().map { it.serial to it.state }}")
        service.connect(args[1], if (mode == "compatibility") TouchMode.Compatibility else TouchMode.MaaTouch)
        val ready = (service.state.value as? DeviceConnectionState.Ready)?.device
            ?: error("Connect failed: ${service.state.value}")
        record("READY Android=${ready.identity.release}/API${ready.identity.sdkInt}; touch=${ready.touchReady}; awake=${ready.awake}; size=${ready.width}x${ready.height}; mode=${ready.selectedMode}; timing=${ready.timing}")
        check(ready.touchReady)
        if (mode == "connect-only") return@runBlocking // No launch, gesture, UI dump or saved frame.
        // Only package metadata is read; neither accounts nor application storage are queried.
        record("installedGamePackages=" + shell("pm", "list", "packages").lineSequence().filter { it.contains("priconne") || it.contains("princessconnect") }.toList())
        if (mode != "soak") {
            check(service.launch("com.android.settings"))
            delay(1200)
            check(service.queryForeground() == "com.android.settings")
        }
        suspend fun snapshot(name: String): List<UiNode> {
            val frame = service.capture()
            val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, frame.width, frame.height, frame.pixels, 0, frame.width)
            ImageIO.write(image, "png", output.resolve("$name.png").toFile())
            val tree = service.uiTree()
            fun flatten(nodes: List<UiNode>): List<UiNode> = nodes.flatMap { listOf(it) + flatten(it.children) }
            Files.writeString(output.resolve("$name-nodes.txt"), flatten(tree).joinToString("\n") { "${it.bounds}\tclickable=${it.clickable}\t${it.text.orEmpty()}\t${it.description.orEmpty()}" })
            record("snapshot=$name size=${frame.width}x${frame.height} nodes=${flatten(tree).size}")
            return tree
        }
        if (mode == "inspect") {
            snapshot("settings-before")
            for ((name, command) in mapOf("window-settings.txt" to listOf("dumpsys", "window"), "power.txt" to listOf("dumpsys", "power"),
                "display-size.txt" to listOf("wm", "size"))) {
                val text = shell(*command.toTypedArray())
                val safe = when (name) {
                    "window-settings.txt" -> text.lineSequence().filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }.joinToString("\n")
                    "power.txt" -> text.lineSequence().filter { it.contains("mWakefulness=") }.joinToString("\n")
                    else -> text
                }
                Files.writeString(output.resolve(name), safe)
            }
            val durations = (1..20).map { val start = System.nanoTime(); check(service.queryForeground() == "com.android.settings"); (System.nanoTime() - start) / 1_000_000.0 }.sorted()
            record("foreground 20 queries p50=${durations[9]}ms p95=${durations[18]}ms")
        } else if (mode == "actions") {
            val before = snapshot("tap-before")
            fun hasDisplay(node: UiNode): Boolean = node.text in setOf("显示", "Display", "声音", "Sound") || node.children.any(::hasDisplay)
            fun display(nodes: List<UiNode>): UiNode? = nodes.firstNotNullOfOrNull { node -> display(node.children) ?: node.takeIf { it.clickable && hasDisplay(it) } }
            val target = checkNotNull(display(before)) { "No visible Display/Sound entry; inspect recorded tree before selecting another safe entry" }
            val b = target.bounds
            check(service.tap((b.left + b.right) / 2f, (b.top + b.bottom) / 2f, "com.android.settings") == TouchResult.Completed)
            delay(600)
            val afterTap = snapshot("tap-after")
            check(afterTap != before) { "Tap did not change UI tree" }
            check(service.back("com.android.settings") == TouchResult.Completed)
            delay(400)
            snapshot("back-after")
            val frame = service.capture()
            val x = frame.width * .75f
            check(service.swipe(x, frame.height * .8f, x, frame.height * .35f, 400, 0, "com.android.settings") == TouchResult.Completed)
            delay(500); val afterSwipe = snapshot("swipe-after")
            check(afterSwipe != before) { "Swipe did not change UI tree" }
            check(service.swipe(x, frame.height * .35f, x, frame.height * .8f, 400, 250, "com.android.settings") == TouchResult.Completed)
            delay(500); snapshot("hold-after")
            check(service.home())
            val change = System.nanoTime()
            withTimeout(2000) { while (service.foregroundPackage == "com.android.settings") delay(20) }
            record("foreground cache changed after Home in ${(System.nanoTime() - change) / 1_000_000}ms")
            check(service.tap(100f, 100f, "com.android.settings") == TouchResult.Rejected("游戏不在前台"))
            check(service.launch("com.android.settings")); delay(400)
            check(service.forceStop("com.android.settings"))
            record("settings forceStop=true; tap/swipe/hold/back completed; inspect tree counts and images separately")
        } else if (mode == "settings") {
            val before = snapshot("storage-before")
            fun hasStorage(node: UiNode): Boolean = node.text in setOf("存储", "Storage") || node.children.any(::hasStorage)
            fun storage(nodes: List<UiNode>): UiNode? = nodes.firstNotNullOfOrNull { node -> storage(node.children) ?: node.takeIf { it.clickable && hasStorage(it) } }
            val b = checkNotNull(storage(before)) { "No visible Storage entry" }.bounds
            val x = (b.left + b.right) / 2f; val y = (b.top + b.bottom) / 2f
            check(service.tap(x, y, "com.android.settings") == TouchResult.Completed); delay(1000)
            val after = snapshot("storage-after")
            check(after.isNotEmpty() && after != before) { "Storage tap did not change a readable UI tree" }
            check(service.back("com.android.settings") == TouchResult.Completed); delay(400)
            check(snapshot("storage-back").isNotEmpty())
            var empty = 0
            repeat(20) { if (service.uiTree().isEmpty()) empty++ }
            record("uiautomator Settings root: dumps=20 empty=$empty")
            check(empty == 0)
            Files.writeString(output.resolve("ui-settings.xml"), shell("cat", "/data/local/tmp/ui.xml"))
            Files.writeString(output.resolve("window-settings.txt"), shell("dumpsys", "window").lineSequence().filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }.joinToString("\n"))
            service.disconnect(); service.connect(args[1], TouchMode.Compatibility)
            check(service.state.value is DeviceConnectionState.Ready)
            service.capture()
            check(service.tap(x, y, "com.android.settings") == TouchResult.Completed); delay(500)
            check(snapshot("compatibility-tap").isNotEmpty())
            check(service.back("com.android.settings") == TouchResult.Completed); delay(400)
            val frame = service.capture()
            check(service.swipe(frame.width * .75f, frame.height * .8f, frame.width * .75f, frame.height * .35f, 400, 0, "com.android.settings") == TouchResult.Completed)
            delay(400); snapshot("compatibility-swipe")
            check(service.swipe(100f, 100f, 100f, 300f, 400, 250, "com.android.settings") == TouchResult.Rejected("兼容模式不支持按住拖拽，请在设置中切换到 MaaTouch"))
            check(service.forceStop("com.android.settings"))
            record("MaaTouch Storage tap/back UI trees verified; compatibility tap/swipe and hold refusal passed")
        } else if (mode == "compatibility") {
            val frame = service.capture()
            check(frame.width == 1280 && frame.height == 720) { "Recorded Settings bounds only apply to the inspected 1280x720 fixture" }
            snapshot("compatibility-before")
            // Storage row centre from the actual settings-before-nodes.txt captured in inspect mode.
            check(service.tap(640f, 449f, "com.android.settings") == TouchResult.Completed)
            delay(500); snapshot("compatibility-storage")
            check(service.back("com.android.settings") == TouchResult.Completed); delay(400)
            check(service.swipe(960f, 576f, 960f, 252f, 400, 0, "com.android.settings") == TouchResult.Completed)
            delay(400); snapshot("compatibility-scroll")
            check(service.swipe(960f, 252f, 960f, 576f, 400, 250, "com.android.settings") == TouchResult.Rejected("兼容模式不支持按住拖拽，请在设置中切换到 MaaTouch"))
            var idle = 0; var noRoot = 0; var success = 0
            repeat(20) {
                val result = adb.shell(args[1], listOf("uiautomator", "dump", "/data/local/tmp/ui.xml"))
                val text = if (result is AdbResult.Success) result.text() + result.stderr else result.toString()
                when { "could not get idle state" in text -> idle++; "null root node" in text -> noRoot++; "UI hierchary dumped" in text || "UI hierarchy dumped" in text -> success++ }
            }
            record("twenty UI dumps: success=$success idle=$idle nullRoot=$noRoot")
            if (success > 0) Files.writeString(output.resolve("ui-settings.xml"), shell("cat", "/data/local/tmp/ui.xml"))
            Files.writeString(output.resolve("window-settings.txt"), shell("dumpsys", "window").lineSequence().filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }.joinToString("\n"))
            check(service.forceStop("com.android.settings"))
            record("compatibility commands passed; effects require recorded image review when UI dump is unavailable")
        } else {
            val started = System.nanoTime()
            var count = 0
            val intervals = mutableListOf<Double>()
            var previous = started
            while (System.nanoTime() - started < 600_000_000_000L) {
                val tick = System.nanoTime()
                service.capture(); count++
                intervals += (tick - previous) / 1_000_000.0; previous = tick
                if (count % 100 == 0) { System.gc(); record("frames=$count heap=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}") }
                delay((300 - (System.nanoTime() - tick) / 1_000_000).coerceAtLeast(0))
            }
            val sorted = intervals.drop(1).sorted()
            record("soak frames=$count interval p50=${sorted[sorted.size / 2]}ms p95=${sorted[(sorted.size * .95).toInt()]}ms")
        }
    } finally {
        service.disconnect()
        val processList = runCatching { shell("ps", "-A", "-o", "PID,ARGS") }.getOrDefault("")
        record("maatouchRemaining=" + processList.lineSequence().filter { it.contains("com.shxyke.MaaTouch.App") && !it.contains("sh -c") }.count())
        service.close()
        Files.writeString(output.resolve("$mode.log"), synchronized(messages) { messages.joinToString("\n") })
    }
}
