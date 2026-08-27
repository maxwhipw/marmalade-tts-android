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
        // Reader mode: Readability4J is only published on JitPack.
        // Restricted so nothing else can resolve from an unaudited source.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.dankito") }
        }
    }
}

rootProject.name = "MarmaladeTTS"
include(":app")
