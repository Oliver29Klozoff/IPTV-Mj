pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // android-youtube-player (PierfrancescoSoffritti) is only published on JitPack, not
        // Maven Central/Google — needed for the embedded VOD/series trailer player.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "IPTV Native"
include(":app")
 