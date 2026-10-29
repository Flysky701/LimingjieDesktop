import java.security.MessageDigest

plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies { implementation(libs.coroutines.core); implementation(libs.onnx); testImplementation(libs.junit) }

val modelHashes = mapOf(
    "ch_PP-OCRv5_det_mobile.onnx" to "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
    "ch_PP-OCRv5_rec_mobile_ctc.onnx" to "3f650d0b5b20e6e059c15b590fbf0bcc8459adc088592bc0435f74eae4015cf5",
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
if (providers.gradleProperty("full").isPresent) tasks.named("check") { dependsOn("evaluate") }

// Explicit diagnostics preserve transformed observations without counting them as test cases
// or adding repeated variants to the accuracy manifest.
tasks.test { exclude("**/OcrFailureDiagnosisTest*") }
tasks.register<Test>("diagnosePreprocessing") {
    description = "Inspect fixed-model responses to real failed ROI preprocessing variants"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/OcrFailureDiagnosisTest*")
    outputs.upToDateWhen { false }
}
