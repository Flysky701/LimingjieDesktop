pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } }
dependencyResolutionManagement {
    repositories {
        google { content { includeGroupByRegex("androidx\\..*"); includeGroup("com.android.tools.build") } }
        mavenCentral()
    }
}
rootProject.name = "LimingjieDesktop"
include(":shared", ":device", ":ocr", ":platform", ":app")
