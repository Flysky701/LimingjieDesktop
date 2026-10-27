plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.ksp) apply false
}
allprojects { group = "com.landosol.toolbox.desktop"; version = "1.0.18+desktop.11" }
