buildscript {
    configurations.all {
        resolutionStrategy {
            // Offline-cache: javapoet 1.10.0 jar not cached; force to 1.13.0 which is cached.
            force("com.squareup:javapoet:1.13.0")
        }
    }
}

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    id("com.google.dagger.hilt.android") version "2.54" apply false
}
