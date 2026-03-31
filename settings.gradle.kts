pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // [修正] Kotlin DSL 必須使用 uri() 函數
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Temiapp"
include(":app")