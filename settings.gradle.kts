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
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Komorebi"
include(":app")
// settings.gradle.kts (Kotlin DSL 形式)
//include(":media-decoder-ffmpeg")
//project(":media-decoder-ffmpeg").projectDir = File("/Users/taichimaekawa/Documents/ffmpeg/media/libraries/decoder_ffmpeg")