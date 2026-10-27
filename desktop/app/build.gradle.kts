import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}
// jpackage requires a numeric base version; display labels and ZIPs keep the desktop suffix.
val desktopPackageVersion = project.version.toString().substringBefore('+')
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared")); implementation(project(":device")); implementation(project(":ocr")); implementation(project(":platform"))
    implementation(compose.desktop.currentOs); implementation(compose.material3)
    implementation(kotlin("reflect"))
    testImplementation(libs.junit)
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.withType<Test>().configureEach {
    systemProperty("limingjie.test.classpath", sourceSets["test"].runtimeClasspath.asPath)
    providers.gradleProperty("packagedWorkerExecutable").orNull?.let { systemProperty("limingjie.test.packagedWorker", it) }
}
compose.desktop {
    application {
        mainClass = "com.landosol.toolbox.desktop.app.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-Djava.net.useSystemProxies=true", "-Xmx1536m")
        buildTypes.release.proguard.isEnabled = false
        nativeDistributions {
            packageName = "LimingjieAssistant"
            packageVersion = desktopPackageVersion
            includeAllModules = true
            windows { iconFile.set(project.file("src/main/resources/desktop/app.ico")) }
        }
    }
}

fun runtimeEntry(source: ZipEntry) = ZipEntry(source.name).also { it.time = source.time }

/** Keep the original Java API, notices and Windows x64 native code in multi-platform JARs. */
fun windowsRuntimeJar(source: File, destination: File): Int = ZipFile(source).use { jar ->
    val entries = jar.entries().asSequence().toList()
    val nativeExtensions = listOf(".dll", ".so", ".dylib", ".jnilib")
    val otherJnaRoots = entries.map { it.name }.filter { name ->
        name.startsWith("com/sun/jna/") && nativeExtensions.any(name::endsWith) &&
            !name.startsWith("com/sun/jna/win32-x86-64/")
    }.map { it.substringBeforeLast('/') + "/" }.toSet()
    val removed = entries.filter { entry ->
        val name = entry.name
        name.endsWith(".pdb", ignoreCase = true) ||
            (name.startsWith("ai/onnxruntime/native/") && name != "ai/onnxruntime/native/" &&
                !name.startsWith("ai/onnxruntime/native/win-x64/")) ||
            (name.startsWith("natives/") && name != "natives/" &&
                !name.startsWith("natives/windows_x64/")) ||
            otherJnaRoots.any(name::startsWith)
    }.map { it.name }.toSet()
    if (removed.isEmpty()) return@use 0
    check(entries.none { entry -> listOf(".SF", ".RSA", ".DSA", ".EC").any { entry.name.uppercase().endsWith(it) } }) {
        "Cannot filter a signed runtime JAR: ${source.name}"
    }
    ZipOutputStream(destination.outputStream().buffered()).use { output ->
        output.setLevel(Deflater.BEST_COMPRESSION)
        entries.filterNot { it.name in removed }.forEach { entry ->
            output.putNextEntry(runtimeEntry(entry))
            if (!entry.isDirectory) jar.getInputStream(entry).use { it.copyTo(output) }
            output.closeEntry()
        }
    }
    removed.size
}

fun windowsPortableArchive(archive: File, temporary: File) {
    val rebuilt = temporary.resolve("windows-portable.zip")
    val originalJar = temporary.resolve("native-source.jar")
    val filteredJar = temporary.resolve("native-windows.jar")
    try {
        ZipFile(archive).use { source ->
            ZipOutputStream(rebuilt.outputStream().buffered()).use { output ->
                output.setLevel(Deflater.BEST_COMPRESSION)
                source.entries().asSequence().forEach { entry ->
                    output.putNextEntry(runtimeEntry(entry))
                    val name = entry.name.substringAfterLast('/')
                    val runtime = listOf("onnxruntime-", "sqlite-bundled-jvm-", "jna-").any(name::startsWith)
                    if (!entry.isDirectory && entry.name.contains("/app/") && name.endsWith(".jar") && runtime) {
                        source.getInputStream(entry).use { input -> originalJar.outputStream().use { input.copyTo(it) } }
                        val removed = windowsRuntimeJar(originalJar, filteredJar)
                        val selected = if (removed > 0) filteredJar else originalJar
                        selected.inputStream().use { it.copyTo(output) }
                        if (removed > 0) println("Windows runtime: $name, ${originalJar.length()} -> ${selected.length()} bytes ($removed unused entries removed)")
                    } else if (!entry.isDirectory) {
                        source.getInputStream(entry).use { it.copyTo(output) }
                    }
                    output.closeEntry()
                }
            }
        }
        Files.move(rebuilt.toPath(), archive.toPath(), REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(originalJar.toPath())
        Files.deleteIfExists(filteredJar.toPath())
        Files.deleteIfExists(rebuilt.toPath())
    }
}

val portableName = "LimingjieAssistant-desktop-${project.version}-win-x64.zip"
val portableChecksum = layout.buildDirectory.file("dist/$portableName.sha256")
tasks.register<Zip>("packagePortableZip") {
    group = "distribution"
    description = "Builds the Windows app image and portable ZIP with runtime, notices, and checksum."
    dependsOn("createReleaseDistributable")
    archiveFileName.set(portableName)
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    isZip64 = true
    from(layout.buildDirectory.dir("compose/binaries/main-release/app/LimingjieAssistant")) { into("LimingjieAssistant") }
    from(listOf(rootProject.file("../README-desktop.md"), rootProject.file("../LICENSE"), rootProject.file("../THIRD_PARTY_NOTICES.md"))) { into("LimingjieAssistant") }
    from(rootProject.file("../docs/desktop/USER_CHECKLIST.md")) { into("LimingjieAssistant"); rename { "用户验收清单.md" } }
    from(rootProject.file("../docs/desktop/reports/BASELINE-DESKTOP8.md")) { into("LimingjieAssistant"); rename { "基础版说明.md" } }
    from(rootProject.file("../docs/desktop/reports/ADB-DAEMON-DESKTOP9.md")) { into("LimingjieAssistant"); rename { "ADB修复说明.md" } }
    from(rootProject.file("../docs/desktop/reports/PREVIEW-DEMAND-DESKTOP10.md")) { into("LimingjieAssistant"); rename { "预览优化说明.md" } }
    from(rootProject.file("licenses")) { into("LimingjieAssistant/licenses") }
    from(rootProject.file("device/licenses")) { into("LimingjieAssistant/licenses/MaaTouch") }
    from(rootProject.file("ocr/licenses")) { into("LimingjieAssistant/licenses/OCR") }
    from(rootProject.file("ocr/MODELS.md")) { into("LimingjieAssistant/licenses/OCR") }
    from(project.file("src/main/resources/desktop/portable.flag")) { into("LimingjieAssistant") }
    outputs.file(portableChecksum)
    doFirst {
        check(System.getProperty("os.name").startsWith("Windows")) { "Portable Windows ZIP must be built on Windows" }
        check(layout.buildDirectory.file("compose/binaries/main-release/app/LimingjieAssistant/LimingjieAssistant.exe").get().asFile.isFile) { "jpackage app-image is missing" }
        listOf("../README-desktop.md", "../LICENSE", "../THIRD_PARTY_NOTICES.md", "../docs/desktop/USER_CHECKLIST.md", "licenses/DEPENDENCIES.md", "ocr/MODELS.md").forEach {
            check(rootProject.file(it).isFile) { "Missing distribution notice: $it" }
        }
        val inventory = rootProject.file("licenses/DEPENDENCIES.md").readText()
        configurations.getByName("runtimeClasspath").resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
            val module = artifact.moduleVersion.id
            if (module.group != "com.landosol.toolbox.desktop") {
                check(inventory.contains("${module.group}:${artifact.name}:${module.version}")) {
                    "Missing runtime license inventory entry: ${module.group}:${artifact.name}:${module.version}"
                }
            }
        }
    }
    doLast {
        windowsPortableArchive(archiveFile.get().asFile, temporaryDir)
        val digest = MessageDigest.getInstance("SHA-256")
        archiveFile.get().asFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        portableChecksum.get().asFile.writeText("$hash  $portableName\n")
    }
}
