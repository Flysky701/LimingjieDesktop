import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}
val upstreamVersion = providers.provider {
    Regex("versionName = \"([^\"]+)\"").find(rootProject.file("../android/app/build.gradle.kts").readText())!!.groupValues[1]
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":shared")); implementation(project(":device")); implementation(project(":ocr")); implementation(project(":platform"))
    implementation(compose.desktop.currentOs); implementation(compose.material3)
    testImplementation(libs.junit)
}
compose.desktop {
    application {
        mainClass = "com.landosol.toolbox.desktop.app.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-Djava.net.useSystemProxies=true", "-Xmx1536m")
        buildTypes.release.proguard.isEnabled = false
        nativeDistributions {
            packageName = "LimingjieAssistant"
            packageVersion = upstreamVersion.get()
            includeAllModules = true
            windows { iconFile.set(project.file("src/main/resources/desktop/app.ico")) }
        }
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
        val digest = MessageDigest.getInstance("SHA-256")
        archiveFile.get().asFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        portableChecksum.get().asFile.writeText("$hash  $portableName\n")
    }
}
