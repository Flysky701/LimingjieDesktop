plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
    `java-library`
}
kotlin { jvmToolchain(21) }
val upstream = rootProject.file("../android/app")
fun exclusions(name: String) = file(name).readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
val prepareUpstreamMain by tasks.registering(Sync::class) {
    val excluded = exclusions("upstream-excludes.txt")
    inputs.files("upstream-excludes.txt", "upstream-derived.txt")
    from(upstream.resolve("src/main/java")) { exclude(excluded) }
    into(layout.buildDirectory.dir("generated/upstream/main"))
    doFirst { excluded.forEach { check(upstream.resolve("src/main/java/$it").isFile) { "Stale exclusion: $it" } } }
    doLast {
        file("upstream-derived.txt").readLines().filter { it.isNotBlank() && !it.startsWith('#') }.forEach {
            val parts = it.split('|')
            val target = destinationDir.resolve(parts[0])
            val source = target.readText()
            check(source.windowed(parts[1].length).count { text -> text == parts[1] } == parts[3].toInt()) { "Derived source drift: ${parts[0]}" }
            target.writeText(source.replace(parts[1], parts[2]))
        }
    }
}
val prepareUpstreamTest by tasks.registering(Sync::class) {
    val excluded = exclusions("upstream-test-excludes.txt")
    inputs.files("upstream-test-excludes.txt", "upstream-test-derived.txt")
    inputs.property("desktopVersion", project.version.toString())
    from(upstream.resolve("src/test/java")) { exclude(excluded) }
    into(layout.buildDirectory.dir("generated/upstream/test"))
    doFirst { excluded.forEach { check(upstream.resolve("src/test/java/$it").isFile) { "Stale test exclusion: $it" } } }
    doLast {
        file("upstream-test-derived.txt").readLines().filter { it.isNotBlank() && !it.startsWith('#') }.forEach {
            val parts = it.split('|')
            val target = destinationDir.resolve(parts[0])
            val source = target.readText()
            check(source.windowed(parts[1].length).count { text -> text == parts[1] } == parts[3].toInt()) { "Derived test drift: ${parts[0]}" }
            target.writeText(source.replace(parts[1], parts[2].replace("@DESKTOP_VERSION@", project.version.toString())))
        }
    }
}
val prepareUpstreamAssets by tasks.registering(Sync::class) {
    from(upstream.resolve("src/main/assets")) { into("assets") }
    from(upstream.resolve("schemas/com.landosol.toolbox.data.local.AppDatabase/1.json")) { into("desktop"); rename { "schema-v1.json" } }
    into(layout.buildDirectory.dir("generated/upstream/assets"))
    doLast {
        val names = destinationDir.resolve("assets").walkTopDown().filter { it.isFile }.map { it.relativeTo(destinationDir).invariantSeparatorsPath }.sorted().toList()
        destinationDir.resolve("desktop/assets-manifest.txt").writeText(names.joinToString("\n", postfix = "\n"))
        val sample = names.first { it.endsWith(".png", ignoreCase = true) }
        val image = javax.imageio.ImageIO.read(destinationDir.resolve(sample))
        destinationDir.resolve("desktop/sample.properties").writeText("path=$sample\nwidth=${image.width}\nheight=${image.height}\n")
    }
}
val generateBuildConfig by tasks.registering {
    inputs.file(upstream.resolve("build.gradle.kts"))
    inputs.property("desktopVersion", project.version.toString())
    val output = layout.buildDirectory.dir("generated/version")
    outputs.dir(output)
    doLast {
        val source = upstream.resolve("build.gradle.kts").readText()
        val name = project.version.toString()
        val code = Regex("versionCode = (\\d+)").find(source)!!.groupValues[1]
        output.get().file("com/landosol/toolbox/BuildConfig.kt").asFile.apply {
            parentFile.mkdirs()
            val applicationId = Regex("applicationId = \"([^\"]+)\"").find(source)!!.groupValues[1]
            writeText("package com.landosol.toolbox\nobject BuildConfig { const val VERSION_NAME = \"$name\"; const val VERSION_CODE = $code; const val APPLICATION_ID = \"$applicationId\"; const val DEBUG = false }\n")
        }
    }
}
kotlin.sourceSets.main { kotlin.srcDir(prepareUpstreamMain); kotlin.srcDir(generateBuildConfig) }
kotlin.sourceSets.test { kotlin.srcDir(prepareUpstreamTest) }
sourceSets.main { resources.srcDir(prepareUpstreamAssets) }
sourceSets.test { resources.srcDir(upstream.resolve("src/test/resources")); resources.srcDir(upstream.resolve("../test-fixtures/protocol")) }
dependencies {
    api(compose.desktop.currentOs); api(compose.material3)
    api(libs.coroutines.core); api(libs.coroutines.swing); api(libs.serialization.json)
    api(libs.room.runtime); api(libs.sqlite.bundled); ksp(libs.room.compiler)
    api(libs.lifecycle.viewmodel); api(libs.lifecycle.runtime)
    api(libs.okhttp); api(libs.json)
    testImplementation(libs.junit); testImplementation(libs.coroutines.test); testImplementation(libs.mockwebserver)
}
ksp { arg("room.schemaLocation", layout.buildDirectory.dir("schemas").get().asFile.path) }
tasks.test {
    workingDir(upstream)
    maxHeapSize = "2g"
    doFirst { check(upstream.resolve("src/test/resources/character-icon-golden.txt").isFile) { "Missing upstream golden; refusing regeneration" } }
}
apply(from = "validation.gradle.kts")
