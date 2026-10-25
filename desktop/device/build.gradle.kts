import java.security.MessageDigest

plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization) }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(libs.coroutines.core); implementation(libs.serialization.json); implementation(libs.apksig)
    testImplementation(libs.junit); testImplementation(libs.coroutines.test)
}
val verifyMaaTouch by tasks.registering {
    val asset = layout.projectDirectory.file("src/main/resources/device/maatouch")
    inputs.file(asset)
    doLast {
        val actual = MessageDigest.getInstance("SHA-256").digest(asset.asFile.readBytes()).joinToString("") { "%02x".format(it) }
        check(actual == "4ea8590cd0349ce900f39ab16ef3751dad2356286b465b4293f80f9858c995d0") { "MaaTouch asset checksum mismatch" }
    }
}
tasks.processResources {
    dependsOn(verifyMaaTouch)
    from("licenses") { into("META-INF/licenses") }
}
