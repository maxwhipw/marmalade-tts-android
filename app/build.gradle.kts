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

// Reproducible builds: pin the entire resolved dependency graph in
// app/gradle.lockfile so a rebuild weeks later (F-Droid's verification
// server) cannot pick up a newer transitive than the CI build did.
// After any dependency change, regenerate with
//   ./gradlew :app:dependencies --write-locks
dependencyLocking {
    lockAllConfigurations()
}

android {
    namespace = "app.marmalade.tts"
    compileSdk = 36

    // No Google-encrypted dependency blob in the APK signing block:
    // F-Droid's scanner rejects it as an opaque extra signing block,
    // and only Google can decrypt it anyway. The AAB keeps it for Play
    // Console's dependency insights.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    defaultConfig {
        applicationId = "app.marmalade.tts"
        minSdk = 28
        targetSdk = 36
        // G4 versioning (internal release notes):
        // versionCode = MAJOR*10_000_000 + MINOR*10_000 + PATCH*10 + ABI.
        // The trailing ABI digit is reserved for possible future split
        // APKs (0=universal, 1=armv7, 2=arm64, 3=x86, 4=x86_64); a split
        // build must give every ABI a distinct, ascending versionCode.
        // Safe headroom: 32-bit int caps at ~214.7.0.0.
        // Written as a plain literal (not the formula as arithmetic):
        // F-Droid's checkupdates parses this line with a regex and only
        // sees the first number of an expression.
        versionCode = 10000000
        versionName = "1.0.0"

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
                // Reproducible builds: without these, the same source built from
                // two different checkout paths produces different .so bytes, which
                // fails F-Droid's reproducible-build verification.
                //   -ffile-prefix-map  strips the absolute checkout path out of
                //     __FILE__ strings (openjtalk/mecab embeds a dozen of them).
                //     Those strings live in a merge-strings .rodata section, so a
                //     one-character path difference reorders the whole section and
                //     cascades into .rela.dyn addends and .text address literals —
                //     ~37 KB of diff from 12 strings.
                //   --build-id=none  drops the GNU build-id note, which the NDK
                //     derives from something path-sensitive: libespeak-ng.so was
                //     otherwise byte-identical across two checkouts and still
                //     differed in exactly those 20 bytes.
                // Verified 2026-08-09 by building the same commit from two paths
                // and comparing every APK zip entry.
                val prefixMapRoot = rootProject.layout.projectDirectory.asFile.absolutePath
                cFlags += listOf("-ffile-prefix-map=$prefixMapRoot=.")
                cppFlags += listOf("-ffile-prefix-map=$prefixMapRoot=.")
                // 16 KB page-size compliance (required by Google Play for apps
                // targeting SDK 35+ submitted/updated after 2025-11-01). NDK
                // 26.3 (r26) does NOT align to 16 KB by default — that only
                // landed in r28 — so we force it via the linker. Affects this
                // project's JNI libs (espeak-jni, openjtalk-jni, espeak-ng).
                // Verify with `readelf -lW` → LOAD segments at 0x4000.
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,--build-id=none",
                    "-DCMAKE_EXE_LINKER_FLAGS=-Wl,--build-id=none",
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

    // Two distribution flavors sharing one applicationId, signing config and
    // feature set. Every feature is free in both — the Pro paywall was removed
    // in 1.0.0-beta.1 (see the internal release notes for why). The split
    // survives for one difference: `src/fdroid/` carries a GitHub Sponsors
    // link in About, and `src/play/` deliberately doesn't, because Google's
    // payments policy is unsettled on out-of-app donation links for a
    // developer who isn't a registered charity.
    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
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
        // Dedicated distribution key for the F-Droid/GitHub releases
        // (decided 2026-08-09, internal release notes, R-track): the
        // permanent F-Droid signing identity must not share fate with
        // the Play upload key, which Google can reset. Optional dist*
        // quartet in the same keystore.properties; absent → the fdroid
        // flavor falls back to "release" (or unsigned), which is fine
        // everywhere except a real distribution build.
        if (keystoreProps.getProperty("distStoreFile") != null) {
            create("dist") {
                storeFile = file(keystoreProps.getProperty("distStoreFile"))
                storePassword = keystoreProps.getProperty("distStorePassword")
                keyAlias = keystoreProps.getProperty("distKeyAlias")
                keyPassword = keystoreProps.getProperty("distKeyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            // Emulator testing: debug builds also carry x86_64 (espeak-ng
            // builds from source, ORT ships x86_64 in its AAR). Release
            // keeps the arm-only filter from defaultConfig.
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Reproducible builds: AGP embeds META-INF/version-control-info
            // whose content depends on the checkout's shape (git worktrees
            // yield NO_VALID_GIT_FOUND, real clones the revision) — the one
            // entry that differed between an fdroid-build and a host build
            // of the same commit. Omit it entirely.
            vcsInfo { include = false }
            signingConfig = signingConfigs.findByName("release")
            // On-device R8 smoke testing: `-PsmokeRelease` builds this same
            // minified release variant but installable side-by-side as
            // app.marmalade.tts.rc, signed with the debug key so adb can
            // install it without keystore.properties. Never distribute a
            // smokeRelease artifact.
            if (project.hasProperty("smokeRelease")) {
                applicationIdSuffix = ".rc"
                signingConfig = signingConfigs.getByName("debug")
            }
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
    androidResources {
        // Shipped UI languages (keep in sync with res/xml/locales_config.xml).
        // Also strips unused locales from library resources.
        localeFilters += listOf("en", "es", "fr", "hi", "it", "ja", "pt-rBR", "zh-rCN")
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
        // Robolectric tests resolve real string resources (e.g. the engine
        // catalog's license disclosures), which needs the merged resources
        // on the unit-test classpath.
        unitTests.isIncludeAndroidResources = true
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

// The fdroid release variant signs with the dedicated distribution key
// when one is configured (see the signingConfigs comment). Variant API
// because signingConfig set on a buildType can't differ per flavor.
// smokeRelease keeps its debug-key override — never ship that variant.
androidComponents {
    onVariants(selector().withFlavor("distribution" to "fdroid").withBuildType("release")) { variant ->
        // Single line on purpose: F-Droid's remove_signing_keys() strips
        // gradle lines matching `android.signingConfigs.` with no `{` on
        // the line. A multi-line form leaves a dangling reference behind
        // and the stripped script no longer compiles on their builder.
        if (!project.hasProperty("smokeRelease")) {
            android.signingConfigs.findByName("dist")?.let { variant.signingConfig.setConfig(it) }
        }
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

// -----------------------------------------------------------------------------
// Shared espeak-ng-data — build-time generation (FULL language set).
//
// espeak is one process-global instance behind every engine, and it takes
// exactly one data path — so the app ships ONE full espeak-ng-data (every
// language dict, ~19 MB) at an app-level asset path, seeded once to filesDir
// by SharedEspeakData and used by every espeak engine (Kokoro's es/fr/hi/it/pt
// voices, Kitten's en, and any alias phonemization-language override).
// Per-bundle espeak data is ignored legacy. The GPL data is NOT committed —
// it's compiled here from the pinned third_party/espeak-ng submodule by
// tools/espeak-hostgen, exactly the way libespeak-ng.so is compiled-in but
// never committed. The repo stays espeak-free; the APK is already GPL via the
// linked .so, so the espeak *data* changes nothing license-wise.
//
// Output lands in build/generated/espeakAssets/ (gitignored) and is merged
// into the APK assets. Offline + F-Droid-reproducible: the host CMake
// deliberately avoids the submodule's deps.cmake (which network-fetches
// libsonic). See tools/espeak-hostgen/CMakeLists.txt.
// -----------------------------------------------------------------------------
run {
    val hostgenSrc = rootProject.file("tools/espeak-hostgen")
    val submodule = rootProject.file("third_party/espeak-ng")
    val cmakeBuildDir = layout.buildDirectory.dir("espeak-hostgen")
    val seedAssetsDir = layout.buildDirectory.dir("generated/espeakAssets")
    val bakedEngineRel = "espeak/espeak-ng-data"

    val generateEspeakData = tasks.register("generateEspeakData") {
        group = "build"
        description = "Compile the full espeak-ng-data set from the espeak-ng submodule for the app-level shared phonemizer data."
        // Incrementality: rerun only when the generator or the espeak sources change.
        inputs.dir(hostgenSrc).withPropertyName("hostgen")
        inputs.dir(File(submodule, "dictsource")).withPropertyName("dictsource")
        inputs.dir(File(submodule, "phsource")).withPropertyName("phsource")
        inputs.dir(File(submodule, "espeak-ng-data")).withPropertyName("dataSrc")
        val outDir = seedAssetsDir.get().dir(bakedEngineRel).asFile
        outputs.dir(outDir).withPropertyName("espeakData")
        doLast {
            if (!File(submodule, "src/libespeak-ng/CMakeLists.txt").exists()) {
                throw GradleException(
                    "espeak-ng submodule missing at $submodule — run: git submodule update --init",
                )
            }
            val buildDir = cmakeBuildDir.get().asFile.apply { mkdirs() }
            // Prefer the SDK's own cmake (same version externalNativeBuild pins):
            // a bare PATH `cmake` doesn't exist on minimal build hosts like the
            // F-Droid buildserver, where only the SDK package is guaranteed.
            val sdkCmake = android.sdkDirectory.resolve("cmake/3.22.1/bin/cmake")
            val cmakeBin = if (sdkCmake.isFile) sdkCmake.absolutePath else "cmake"
            exec {
                commandLine(
                    cmakeBin, "-S", hostgenSrc.absolutePath, "-B", buildDir.absolutePath,
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
            // Dict compiles share one dictsource working dir, so the espeakdata
            // target must run single-job: parallel dict jobs race on that dir
            // and make the output byte-order depend on host core count —
            // poison for F-Droid reproducible-build verification.
            exec {
                commandLine(
                    cmakeBin, "--build", buildDir.absolutePath, "--target", "espeakdata",
                    "-j", "1",
                )
            }
            val generated = File(buildDir, "espeak-ng-data")
            // Every language Kokoro actually feeds through espeak, plus a
            // couple of sentinels (de/ru) that only exist when the full
            // dict loop ran — a silently-trimmed set must fail the build,
            // not ship an English-only APK again.
            val requiredDicts = listOf(
                "en_dict", "es_dict", "fr_dict", "hi_dict", "it_dict",
                "pt_dict", "de_dict", "ru_dict",
            )
            val missing = requiredDicts.filterNot { File(generated, it).exists() }
            if (missing.isNotEmpty()) {
                throw GradleException("espeak data generation incomplete at $generated — missing: $missing")
            }
            outDir.parentFile.mkdirs()
            if (outDir.exists()) outDir.deleteRecursively()
            copy {
                from(generated)
                into(outDir)
            }
            // Pre-1.0 this task emitted an English-only tree under the Kitten
            // seed path. A stale build dir would keep shipping it alongside
            // the shared tree — purge it so incremental builds self-heal.
            File(seedAssetsDir.get().asFile, "engines-seed").deleteRecursively()
        }
    }

    // The generated dir is an extra assets source root; AGP unions it with
    // src/main/assets (disjoint paths — engines-seed/ committed, espeak/
    // generated — so no collision).
    android.sourceSets.getByName("main").assets.srcDir(seedAssetsDir)

    // Every flavor×buildType's asset merge must wait for generation.
    tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
        dependsOn(generateEspeakData)
    }
    // Lint reads the asset source roots too, so its model/analysis tasks consume
    // this task's output. Without the edge, `assembleFdroidRelease` fails
    // Gradle's implicit-dependency validation before it ever reaches packaging.
    // Matched by name rather than by type: the task class
    // (com.android.build.gradle.internal.lint.LintModelWriterTask) is AGP-internal.
    tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach {
        dependsOn(generateEspeakData)
    }
}

// -----------------------------------------------------------------------------
// In-app privacy policy
// -----------------------------------------------------------------------------
// The canonical policy is the repo-root PRIVACY.md. It's copied into the APK
// assets at build (build/generated/privacyAssets/PRIVACY.md, gitignored under
// build/) and rendered by PrivacyPolicyScreen — one source of truth, no
// committed duplicate to drift. full_description.txt promises this in-app policy.
run {
    val privacyAssetsDir = layout.buildDirectory.dir("generated/privacyAssets")
    val copyPrivacyPolicy = tasks.register<Copy>("copyPrivacyPolicy") {
        group = "build"
        description = "Bundle the canonical repo-root PRIVACY.md into app assets for the in-app privacy screen."
        from(rootProject.file("PRIVACY.md"))
        into(privacyAssetsDir)
    }
    android.sourceSets.getByName("main").assets.srcDir(privacyAssetsDir)
    tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
        dependsOn(copyPrivacyPolicy)
    }
    // Same implicit-dependency problem as generateEspeakData above.
    tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach {
        dependsOn(copyPrivacyPolicy)
    }
}
