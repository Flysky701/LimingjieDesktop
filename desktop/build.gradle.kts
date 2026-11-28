plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.ksp) apply false
}
allprojects { group = "com.landosol.toolbox.desktop"; version = "2.0.1" }

subprojects {
    tasks.withType<Test>().configureEach {
        // Limit parallel forks to the audited standard suites.
        if (name == "test" && project.path in setOf(":app", ":ocr", ":platform")) {
            maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        }
        if (name == "test" && project.path != ":core" && !providers.gradleProperty("full").isPresent) {
            exclude("**/*IntegrationTest*", "**/*UiTest*",
                "**/WorkerTransportTest*", "**/WorkerLifecycleTest*", "**/DesktopTrayTest*",
                "**/InstanceInfrastructureTest*", "**/DeviceIdentityReaderTest*",
                "**/ProcessAdbExecutorTest*", "**/MaaTouchTest*")
        }
    }
}
