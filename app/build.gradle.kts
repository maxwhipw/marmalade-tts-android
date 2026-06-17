import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// Release signing: keystore.properties at the repo root (gitignored; see
// docs/release/SIGNING.md and scripts/make-keystore-properties.sh). When the
// file is absent — fresh clones, F-Droid's buildserver — release builds are
// simply unsigned, which is also correct for F-Droid (they sign their own).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}

android {
    namespace = "app.marmalade.tts"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.marmalade.tts"
        minSdk = 28
        targetSdk = 35
        versionCode = 33
        versionName = "1.0.0-beta.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Real Android devices are arm64-v8a (modern) or armeabi-v7a (older
        // 32-bit ARM). x86 / x86_64 are emulator-only; shipping their native
        // libs costs APK size with zero real-device benefit. Drop them.
        // Anyone running the app in an x86 emulator can do a from-source build.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        // CMake build: espeak-ng compiled from source (pinned submodule at
        // third_party/espeak-ng) into libespeak-ng.so, plus our JNI shims.
        // Play forbids downloading executable code, so the espeak lib must
        // ship in the APK; building it from source also satisfies F-Droid.
        // Shipping it makes the distributed APK a GPL-3.0-or-later combined
        // work — the source tree stays MIT. See cpp/espeak-ng/CMakeLists.txt,
        // cpp/espeak_jni.c + NOTICE.md.
        externalNativeBuild {
            cmake {
                cppFlags += ""
                // 16 KB page-size compliance (required by Google Play for apps
                // targeting SDK 35+ submitted/updated after 2025-11-01). NDK
                // 26.3 (r26) does NOT align to 16 KB by default — that only
                // landed in r28 — so we force it via the linker. Affects this
                // project's JNI libs (espeak-jni, openjtalk-jni, espeak-ng).
                // Verify with `readelf -lW` → LOAD segments at 0x4000.
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "26.3.11579264"

    // Two distribution flavors sharing one applicationId + signing config:
    //   - `play`   : adds Google Play Billing; paywall sheet gates per-app
    //                voices + custom effect creation behind `marmalade_pro`.
    //   - `fdroid` : every feature unlocked, no Google classes whatsoever.
    // Flavor source sets live under `src/play/` and `src/fdroid/`; the
    // `playImplementation` config keeps billing-client out of the F-Droid
    // APK at the dependency-graph level. See docs/release/PAYWALL-PLAN.md.
    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "BILLING_ENABLED", "false")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "BILLING_ENABLED", "true")
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        // Pick the first copy if two dependencies ever bundle the same ORT
        // native lib (harmless with the single onnxruntime-android dep).
        jniLibs {
            pickFirsts += "lib/*/libonnxruntime.so"
            pickFirsts += "lib/*/libonnxruntime4j_jni.so"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Force transitive dependencies to versions available in the offline Gradle cache.
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        force("androidx.lifecycle:lifecycle-livedata:2.8.7")
        force("androidx.lifecycle:lifecycle-livedata-core:2.8.7")
        force("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
        force("androidx.fragment:fragment:1.5.4")
        force("androidx.appcompat:appcompat:1.7.0")
        force("androidx.core:core:1.15.0")
        force("androidx.core:core-ktx:1.15.0")
        force("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
        force("com.google.guava:guava:33.0.0-jre")
        force("com.google.devtools.ksp:symbol-processing-api:2.1.0-1.0.29")
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-android-compiler:2.54")

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore (Preferences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Microsoft onnxruntime-android (MIT). The inference runtime for every
    // shipping engine — Kokoro Direct, Kitten Direct, and Pocket each run
    // their ONNX graphs directly on ORT (no sherpa-onnx wrapper).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")

    // Media session (lock-screen + BT transport controls in MarmaladeSynthService).
    // Provides MediaSessionCompat / PlaybackStateCompat / MediaButtonReceiver.
    implementation("androidx.media:media:1.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Engine bundle extraction (tar.bz2). EngineInstaller streams the
    // single-archive engine download through BZip2CompressorInputStream
    // + TarArchiveInputStream. Apache-2.0.
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Testing — JVM
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("org.robolectric:robolectric:4.13")
    // Provides ApplicationProvider — used by Robolectric tests (Room DAO + TTS service).
    testImplementation("androidx.test:core:1.5.0")

    // Testing — Instrumented
    // Requested 1.2.1; 1.1.5 is the latest version in the offline Gradle cache.
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    // Requested 1.6.2; 1.5.0 is the latest version in the offline Gradle cache.
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
