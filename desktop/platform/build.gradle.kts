plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { jvmToolchain(21) }
dependencies { implementation(libs.coroutines.core); implementation(libs.serialization.json); implementation(libs.jna); testImplementation(libs.junit) }
