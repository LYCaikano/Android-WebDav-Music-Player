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
        // 仅用于 com.github.AdrienPoupa:jaudiotagger（Maven Central 无此维护版）
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "WebDavMusic"
include(":app")
