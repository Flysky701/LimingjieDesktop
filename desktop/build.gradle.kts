plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.ksp) apply false
}
allprojects { group = "com.landosol.toolbox.desktop"; version = "1.0.18+desktop.13" }

subprojects {
    tasks.withType<Test>().configureEach {
        if (name == "test" && !providers.gradleProperty("full").isPresent) {
            exclude("**/*IntegrationTest*", "**/*UiTest*",
                "**/WorkerTransportTest*", "**/WorkerLifecycleTest*", "**/DesktopTrayTest*",
                "**/InstanceInfrastructureTest*", "**/DeviceIdentityReaderTest*",
                "**/ProcessAdbExecutorTest*", "**/MaaTouchTest*")
        }
    }
}
