plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(libs.coroutines.core); implementation(libs.serialization.json); implementation(libs.apksig)
    testImplementation(libs.junit); testImplementation(libs.coroutines.test)
}
