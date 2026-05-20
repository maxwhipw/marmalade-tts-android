package app.marmalade.tts.install

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   EngineCatalog.KITTEN.files  ◄── concatenates KITTEN_TOP_LEVEL with FILES
//                                       │
//                                       ▼
//                                EngineInstaller.install("kitten")
//                                       │
//                                       └── for each file: GET → verify → write
// -----------------------------------------------------------------------------

/**
 * Static manifest of the espeak-ng phonemizer data files that the Kitten
 * engine needs at runtime.
 *
 * This file is **machine-generated** — see
 * `scripts/generate-kitten-manifest.py`. The generator walks a locally
 * extracted `espeak-ng-data/` directory, computes SHA-256 hashes, and
 * emits the Kotlin source below.
 *
 * Why a separate file from [EngineCatalog]:
 *
 *  - Regenerating the manifest is a one-line script invocation; isolating
 *    the long list keeps `git diff EngineCatalog.kt` reviewable when the
 *    semantic API changes.
 *  - Future engines that need their own phonemizer data drop their own
 *    `<Engine>Manifest.kt` next to this one rather than bloating the
 *    catalog file.
 *
 * v0.1 ships with only a representative seed list — the full ~355-file
 * enumeration is a STUBS.md item. The installer handles whatever entries
 * are present without caring about absolute count; treating the manifest
 * as authoritative means "if your manifest is short, your install is
 * incomplete" — surfaced via `KittenEngine.isInstalled()` returning
 * false on the next launch.
 *
 * Regeneration command (run from project root, requires the tarball on
 * disk first):
 *
 * ```bash
 * python3 scripts/generate-kitten-manifest.py \
 *     --espeak-data /tmp/kitten-nano-en-v0_1-fp16/espeak-ng-data \
 *     > app/src/main/java/app/marmalade/tts/install/KittenEspeakDataManifest.kt
 * ```
 */
internal object KittenEspeakDataManifest {

    /**
     * Base URL for individual file fetches. The path on the HF mirror
     * matches the relative path under `espeak-ng-data/`, e.g.
     * `intonations` lives at `<base>/espeak-ng-data/intonations`.
     */
    private const val BASE: String =
        "https://huggingface.co/csukuangfj/sherpa-onnx-kitten-nano-en-v0_1-fp16/resolve/main"

    /**
     * Seed list of the espeak-ng files that the en-US phonemizer
     * specifically needs. The phonemizer also pulls in shared tables
     * (`phontab`, `phonindex`, `phondata`, `intonations`) regardless of
     * which language voice is active — those are the most critical.
     *
     * Sizes are approximate; sha256 is PENDING until the generator runs.
     */
    val FILES: List<EngineFile> = listOf(
        // Shared phonemizer tables — required for every language.
        espeakFile("phontab", 60_000L),
        espeakFile("phonindex", 24_000L),
        espeakFile("phondata", 5_500_000L),
        espeakFile("phondata-manifest", 30_000L),
        espeakFile("intonations", 8_000L),

        // English voice descriptor + dictionary.
        espeakFile("voices/!v/Mr serious", 200L),
        espeakFile("voices/!v/f1", 200L),
        espeakFile("voices/!v/f2", 200L),
        espeakFile("voices/!v/f3", 200L),
        espeakFile("voices/!v/f4", 200L),
        espeakFile("voices/!v/f5", 200L),
        espeakFile("voices/!v/m1", 200L),
        espeakFile("voices/!v/m2", 200L),
        espeakFile("voices/!v/m3", 200L),
        espeakFile("voices/!v/m4", 200L),
        espeakFile("voices/!v/m5", 200L),
        espeakFile("voices/!v/m6", 200L),
        espeakFile("voices/!v/m7", 200L),
        espeakFile("lang/gmw/en", 2_000L),
        espeakFile("lang/gmw/en-US", 1_000L),

        // English dictionary tables — espeak-ng compiles these on install
        // upstream, but the precompiled forms ship with the Sherpa-ONNX
        // bundle.
        espeakFile("en_dict", 4_500_000L),
        espeakFile("en_extra", 100_000L),
    )

    private fun espeakFile(relativeInDataDir: String, approxSize: Long): EngineFile = EngineFile(
        relativePath = "espeak-ng-data/$relativeInDataDir",
        url = "$BASE/espeak-ng-data/$relativeInDataDir",
        sha256 = EngineCatalog.SHA256_PENDING,
        sizeBytes = approxSize,
    )
}
