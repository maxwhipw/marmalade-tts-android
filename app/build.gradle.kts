plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "app.marmalade.tts"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.marmalade.tts"
        minSdk = 28
        targetSdk = 35
        versionCode = 27
        versionName = "0.3.0-alpha.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Real Android devices are arm64-v8a (modern) or armeabi-v7a (older
        // 32-bit ARM). x86 / x86_64 are emulator-only; shipping their
        // libsherpa-onnx-jni + libonnxruntime cost ~58 MB of APK with zero
        // real-device benefit. Drop them. Anyone running the app in an x86
        // emulator can do a from-source build.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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
        // sherpa-onnx bundles libonnxruntime.so; pick first copy if a future
        // dependency also bundles it (matches marmalade-android convention).
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

    // Sherpa-ONNX (TTS inference, vendored AAR). 1.13.2 is the first
    // release with KittenTTS v0.8 support — anything older crashes mid-
    // synthesis against our v0.8 int8 bundle.
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.2.aar"))

    // Microsoft onnxruntime-android (MIT). Used directly by the Pocket TTS
    // engine (v0.3.0+) — Pocket isn't a sherpa-onnx pipeline, it's a
    // 5-graph LSD model we run ourselves. Coexists with sherpa-onnx: the
    // vendored AAR statically links its own ORT 1.13.2 so this dep
    // doesn't replace it. The `jniLibs.pickFirsts` block below absorbs
    // the duplicate `libonnxruntime.so` / JNI shim that both providers
    // bundle for `arm64-v8a` and `armeabi-v7a`.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

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
