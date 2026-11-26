plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.ksp)
    `java-library`
}
kotlin { jvmToolchain(21) }
val generateBuildConfig by tasks.registering {
    val versionName = project.version.toString()
    inputs.property("versionName", versionName)
    val output = layout.buildDirectory.dir("generated/version")
    outputs.dir(output)
    doLast {
        output.get().file("com/landosol/toolbox/BuildConfig.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("""
                package com.landosol.toolbox

                object BuildConfig {
                    const val VERSION_NAME = "$versionName"
                    const val VERSION_CODE = 20000
                    const val APPLICATION_ID = "com.landosol.toolbox"
                    const val DEBUG = false
                }
            """.trimIndent() + "\n")
        }
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateBuildConfig) }

val mainResources = layout.projectDirectory.dir("src/main/resources")
val assets = mainResources.dir("assets")
val generateAssetsMetadata by tasks.registering {
    inputs.dir(assets)
    val output = layout.buildDirectory.dir("generated/assets-metadata")
    outputs.dir(output)
    doLast {
        val files = assets.asFile.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(mainResources.asFile).invariantSeparatorsPath }.sorted().toList()
        val metadata = output.get().dir("desktop").asFile.apply { mkdirs() }
        metadata.resolve("assets-manifest.txt").writeText(files.joinToString("\n", postfix = "\n"))
        val sample = files.first { it.endsWith(".png", ignoreCase = true) }
        val image = javax.imageio.ImageIO.read(mainResources.file(sample).asFile)
        metadata.resolve("sample.properties").writeText("path=$sample\nwidth=${image.width}\nheight=${image.height}\n")
    }
}

val compileCharacterIcons by tasks.registering(JavaExec::class) {
    val compiler = layout.projectDirectory.file("src/iconCompiler/CompileCharacterIcons.java")
    val output = layout.buildDirectory.dir("generated/character-icons")
    inputs.file(compiler)
    inputs.dir(assets)
    outputs.dir(output)
    // Use compiled classes and the raw assets directly. The main jar/resources consume this task.
    classpath(sourceSets.main.get().output.classesDirs, configurations.runtimeClasspath, mainResources)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    mainClass.set(compiler.asFile.absolutePath)
    args(output.get().asFile.absolutePath, temporaryDir.absolutePath)
}
sourceSets.main { resources.srcDir(generateAssetsMetadata); resources.srcDir(compileCharacterIcons) }
tasks.processResources {
    from("schemas/com.landosol.toolbox.data.local.AppDatabase/1.json") {
        into("desktop")
        rename { "schema-v1.json" }
    }
}
dependencies {
    api(compose.desktop.currentOs); api(compose.material3)
    api(libs.coroutines.core); api(libs.coroutines.swing); api(libs.serialization.json)
    api(libs.room.runtime); api(libs.sqlite.bundled); ksp(libs.room.compiler)
    api(libs.lifecycle.viewmodel); api(libs.lifecycle.runtime)
    api(libs.okhttp); api(libs.json)
    testImplementation(libs.junit); testImplementation(libs.coroutines.test); testImplementation(libs.mockwebserver)
}
val roomSchemas = layout.buildDirectory.dir("schemas")
ksp { arg("room.schemaLocation", roomSchemas.get().asFile.path) }
// Room writes outside KSP's generated-source directories; cache this output with the processor.
tasks.matching { it.name == "kspKotlin" }.configureEach {
    outputs.dir(roomSchemas).withPropertyName("roomSchemas")
    doLast {
        check(roomSchemas.get().file("com.landosol.toolbox.data.local.AppDatabase/5.json").asFile.isFile) {
            "Room did not export the v5 schema"
        }
    }
}
tasks.test {
    if (!providers.gradleProperty("full").isPresent) {
        exclude(
            "**/*Replay*Test*", "**/*FixtureTest*", "**/*EvidenceTest*",
            "**/*TwoRoundHandoffTest*", "**/*LayoutAuditTest*", "**/*RecordingTest*",
            "**/*Screenshot*Test*", "**/ResourcePackValidatorTest*", "**/LabyrinthCharacterIconGoldenTest*",
        )
    }
    maxHeapSize = "2g"
    // Planning latency and frame handoff regressions share the host CPU.
    maxParallelForks = 1
    inputs.dir("schemas")
    inputs.dir("src/main/kotlin")
    inputs.dir(assets)
}
