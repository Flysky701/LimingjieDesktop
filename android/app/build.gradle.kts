import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Keep local signing paths out of source control. Unconfigured checkouts use the normal SDK
// debug key; distributing an update requires the same debug key as the previously shipped APK.
val localBuildProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val debugKeystorePath = localBuildProperties.getProperty("debug.keystore")

// Host timing/allocation diagnostics have platform and worker-count prerequisites.
// Keep them available, but do not treat them as portable correctness checks in Ubuntu CI.
// Opt in to a compatible benchmark explicitly; legacy benchmarks require their documented host/worker setup.
// Example: ./gradlew -PincludeBenchmarks=true testDebugUnitTest --tests "*LabyrinthAdaptiveThreadBenchmarkTest"
val includeBenchmarks = providers.gradleProperty("includeBenchmarks")
    .map { it.toBoolean() }
    .orElse(false)
tasks.withType<Test>().configureEach {
    if (!includeBenchmarks.get()) {
        filter { excludeTestsMatching("*BenchmarkTest") }
    }
}

android {
    namespace = "com.landosol.toolbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.landosol.toolbox"
        // Debug/test builds can install next to a shipped package via -PcoexistSuffix=.mod.test.
        (project.findProperty("coexistSuffix") as String?)?.takeIf { it.isNotBlank() }?.let {
            applicationIdSuffix = it
        }
        minSdk = 26
        targetSdk = 35
        // major*10000 + minor*100 + patch, so the code stays monotonic as the name moves.
        versionCode = 10090
        versionName = "1.0.18-mod.V10.9-nodefix-auto46-log3000"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            if (!debugKeystorePath.isNullOrBlank()) {
                storeFile = rootProject.file(debugKeystorePath)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        getByName("test").resources.srcDir("$rootDir/test-fixtures/protocol")
    }

    testOptions.unitTests.all {
        it.systemProperty("test.coexistSuffix", project.findProperty("coexistSuffix") ?: "")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mlkit.text.recognition.chinese)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
