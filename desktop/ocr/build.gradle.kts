import java.security.MessageDigest

plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies { implementation(libs.coroutines.core); implementation(libs.onnx); testImplementation(libs.junit) }

val modelHashes = mapOf(
    "ch_PP-OCRv5_det_mobile.onnx" to "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
    "ch_PP-OCRv5_rec_mobile.onnx" to "5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5",
    "ppocrv5_dict.txt" to "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b",
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
tasks.named("check") { dependsOn("evaluate") }
