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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Komorebi"
include(":app")

val mediaDir = file("media")
if (!mediaDir.exists()) {
    error("media/ ディレクトリが見つかりません。README の「ビルド前の準備」を参照してください。")
}
(gradle as ExtensionAware).extra["androidxMediaModulePrefix"] = "media3-"
apply(from = file("${mediaDir.canonicalPath}/core_settings.gradle"))