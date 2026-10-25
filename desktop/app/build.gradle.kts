plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
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
            packageVersion = "1.0.18"
            includeAllModules = true
        }
    }
}
