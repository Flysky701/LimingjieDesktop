pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } }
dependencyResolutionManagement {
    repositories {
        google { content { includeGroupByRegex("androidx\\..*"); includeGroup("com.android.tools.build") } }
        mavenCentral()
    }
}
rootProject.name = "LimingjieDesktop"
include(":core", ":device", ":ocr", ":platform", ":app")
