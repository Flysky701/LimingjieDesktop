plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.ksp) apply false
}
allprojects { group = "com.landosol.toolbox.desktop"; version = "1.0.18+desktop.12" }

subprojects {
    tasks.withType<Test>().configureEach {
        if (!providers.gradleProperty("full").isPresent) {
            exclude("**/*IntegrationTest*", "**/*UiTest*", "**/*PackagedRuntimeTest*",
                "**/WorkerTransportTest*", "**/WorkerLifecycleTest*", "**/DesktopTrayTest*",
                "**/InstanceInfrastructureTest*", "**/DeviceIdentityReaderTest*",
                "**/ProcessAdbExecutorTest*", "**/MaaTouchTest*")
        }
    }
}
