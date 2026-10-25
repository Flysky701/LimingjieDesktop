plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies { implementation(libs.coroutines.core); implementation(libs.onnx); testImplementation(libs.junit) }
