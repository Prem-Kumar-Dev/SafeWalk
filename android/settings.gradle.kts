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
        mavenCentral()
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
    }
}

rootProject.name = "SafeWalk"
include(":core")
// The Android app needs the Android SDK and Google's Maven repo. Pass -PcoreOnly to build
// and test just the routing core on a plain JVM.
if (!providers.gradleProperty("coreOnly").isPresent) include(":app")
