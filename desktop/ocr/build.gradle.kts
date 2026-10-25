import java.security.MessageDigest

plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies { implementation(libs.coroutines.core); implementation(libs.onnx); testImplementation(libs.junit) }

val modelHashes = mapOf(
    "ch_PP-OCRv4_det_mobile.onnx" to "d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9",
    "ch_PP-OCRv4_rec_mobile.onnx" to "48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b",
    "ppocr_keys_v1.txt" to "28b2362ad4ab2dc38769aa72feb535e3a9ddb3fd2a7585a05920e6393b1dc7f7",
)
val verifyModels by tasks.registering {
    val models = modelHashes.keys.map { layout.projectDirectory.file("src/main/resources/ocr/$it") }
    inputs.files(models)
    doLast {
        models.forEach { model ->
            val actual = MessageDigest.getInstance("SHA-256").digest(model.asFile.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(actual == modelHashes.getValue(model.asFile.name)) { "OCR model checksum mismatch: ${model.asFile.name}" }
        }
    }
}
tasks.named("processResources") { dependsOn(verifyModels) }
tasks.named("check") { dependsOn(verifyModels) }
apply(from = "evaluation.gradle.kts")
