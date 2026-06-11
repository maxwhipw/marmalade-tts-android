package app.marmalade.tts.data

/**
 * Static catalog of the third-party open-source components Marmalade ships
 * in — or downloads into — the device, surfaced by the in-app
 * "Open-source licenses" screen ([app.marmalade.tts.ui.screen.LicensesScreen]).
 *
 * This mirrors the per-component notices in `NOTICE.md` and the `LICENSES/`
 * folder. Those are the human-readable repo documents; this is the app-facing
 * data behind Settings → About → Open-source licenses. **Keep them in sync.**
 *
 * ## Copyright handling (why this is per-component, not per-license)
 *
 * MIT and BSD license texts embed the licensor's copyright line *as part of
 * the license* — MIT's required notice and BSD clause 1. So a single shared
 * "MIT" / "BSD" body would display the wrong copyright holder for every
 * component except the one it was written for. Therefore:
 *
 *  - **MIT / BSD components** each carry their own exact license text
 *    ([Component.textAsset]) with the correct holder — sourced verbatim where
 *    the repo vendors it (Open JTalk / MeCab `COPYING`) or from the canonical
 *    body + the authoritative copyright line (ONNX Runtime, Pocket).
 *  - **GPL-3.0 / Apache-2.0 / CC-BY-4.0** are standalone license bodies with
 *    no embedded licensor copyright (attribution lives in NOTICE files /
 *    source headers), so those share one body ([License.sharedAsset]) and the
 *    component's [Component.copyright] supplies the attribution.
 *
 * Full texts for the [License.sharedAsset] / [Component.textAsset] entries
 * live in `app/src/main/assets/licenses/`. Components with neither a shared
 * body nor a per-component text (bundle-only items we don't ship the text for)
 * fall back to the upstream [License.url].
 */
object LicenseCatalog {

    /**
     * A license family referenced by one or more [Component]s.
     *
     * @param id         SPDX-style identifier, also the display/group name.
     * @param sharedAsset Body shared across all components on this license —
     *                    only set for licenses with **no embedded licensor
     *                    copyright** (GPL/Apache/CC). Null for MIT/BSD, whose
     *                    components must each supply [Component.textAsset].
     * @param url        Canonical upstream URL (browser fallback / "view online").
     */
    data class License(
        val id: String,
        val sharedAsset: String?,
        val url: String,
    )

    /** One shipped/installable third-party component. */
    data class Component(
        /** Stable slug, used as the license-text screen's nav argument. */
        val key: String,
        val name: String,
        val role: String,
        /** Where it reaches the device: "APK", "Engine bundle", etc. */
        val shipsIn: String,
        /** Must match a [License.id] in [licenses]. */
        val licenseId: String,
        /** Exact copyright/attribution line(s) — always shown for this component. */
        val copyright: List<String>,
        /**
         * Per-component exact license text under `assets/licenses/`. Set for
         * MIT/BSD components (the copyright is embedded in the file). Null for
         * components that use their license family's [License.sharedAsset].
         */
        val textAsset: String? = null,
        /** Optional clarification (dual-licensed sub-parts, port provenance, …). */
        val note: String? = null,
    ) {
        /**
         * The bundled asset to display for this component, if any: the
         * per-component [textAsset] wins, else the license family's shared
         * body. Null means "no bundled text — open [License.url] instead".
         */
        fun resolvedAsset(): String? = textAsset ?: licenseFor(licenseId)?.sharedAsset

        /**
         * True when [resolvedAsset] is a per-component file that already
         * embeds the copyright line (MIT/BSD) — so the text screen must NOT
         * also prepend [copyright] (it's already in the body). False when the
         * shared family body is used (GPL/Apache/CC), where [copyright] is the
         * separate attribution to show above the body.
         */
        fun bodyEmbedsCopyright(): Boolean = textAsset != null
    }

    /** The app's own two-layer licensing posture, shown at the top of the screen. */
    object POSTURE {
        const val SOURCE = "Source code — MIT. Every source file in this " +
            "project is MIT-licensed."
        const val BINARY = "App binary — GPL-3.0-or-later. The APK " +
            "compiles in espeak-ng (GPL-3.0-or-later, built from source), " +
            "so the app as distributed is a GPL combined work. Everything " +
            "else in it is MIT-, Apache-2.0-, or BSD-licensed. Engine " +
            "bundles you download contain models and pronunciation data, " +
            "never executable code."
        const val CORRESPONDING_SOURCE_LABEL = "Source code"
        const val CORRESPONDING_SOURCE_URL =
            "https://github.com/maxwhipw/marmalade-tts-android"
    }

    val licenses: List<License> = listOf(
        // MIT / BSD: no shared body — each component supplies its own exact
        // text with the correct copyright holder.
        License("MIT", null, "https://opensource.org/license/mit"),
        License("BSD-3-Clause", null, "https://opensource.org/license/bsd-3-clause"),
        License("Modified BSD", null, "http://open-jtalk.sourceforge.net/"),
        // Standalone bodies with no embedded licensor copyright — shared.
        License(
            "GPL-3.0-or-later", "GPL-3.0.txt",
            "https://www.gnu.org/licenses/gpl-3.0.html",
        ),
        License(
            "Apache-2.0", "Apache-2.0.txt",
            "https://www.apache.org/licenses/LICENSE-2.0",
        ),
        License(
            "CC-BY-4.0", "CC-BY-4.0.txt",
            "https://creativecommons.org/licenses/by/4.0/legalcode.txt",
        ),
        License(
            "CC-BY-SA-4.0", "CC-BY-SA-4.0.txt",
            "https://creativecommons.org/licenses/by-sa/4.0/legalcode.txt",
        ),
        License(
            "CC0-1.0", "CC0-1.0.txt",
            "https://creativecommons.org/publicdomain/zero/1.0/legalcode.txt",
        ),
    )

    /**
     * Declaration order is display order. Components are grouped by
     * [Component.licenseId] in the UI, preserving this order.
     */
    val components: List<Component> = listOf(
        Component(
            key = "marmalade",
            name = "Marmalade", role = "This app", shipsIn = "APK (source)",
            licenseId = "MIT",
            copyright = listOf("Copyright (c) 2026 marmalade-tts contributors"),
            textAsset = "MIT.txt",
        ),
        Component(
            key = "espeak-ng",
            name = "espeak-ng", role = "Phonemizer (English / multi-language)",
            shipsIn = "APK (compiled from source); dictionaries in engine bundles",
            licenseId = "GPL-3.0-or-later",
            copyright = listOf("Copyright (c) The espeak-ng authors"),
        ),
        Component(
            key = "onnxruntime",
            name = "ONNX Runtime Mobile", role = "Inference runtime (direct engines)",
            shipsIn = "APK",
            licenseId = "MIT",
            copyright = listOf("Copyright (c) Microsoft Corporation"),
            textAsset = "onnxruntime.txt",
        ),
        Component(
            key = "commons-compress",
            name = "Apache Commons Compress", role = "Engine-bundle extraction",
            shipsIn = "APK",
            licenseId = "Apache-2.0",
            copyright = listOf("Copyright (c) The Apache Software Foundation"),
        ),
        Component(
            key = "open-jtalk",
            name = "Open JTalk", role = "Japanese phonemizer frontend",
            shipsIn = "APK (compiled in)",
            licenseId = "BSD-3-Clause",
            copyright = listOf(
                "Copyright (c) 2008-2016 Nagoya Institute of Technology, " +
                    "Department of Computer Science",
            ),
            textAsset = "open-jtalk.txt",
        ),
        Component(
            key = "mecab",
            name = "MeCab", role = "Morphological analyzer (bundled with Open JTalk)",
            shipsIn = "APK (compiled in)",
            licenseId = "BSD-3-Clause",
            copyright = listOf(
                "Copyright (c) 2001-2008 Taku Kudo",
                "Copyright (c) 2004-2008 Nippon Telegraph and Telephone Corporation",
            ),
            textAsset = "mecab.txt",
        ),
        Component(
            key = "misaki-cutlet",
            name = "misaki / cutlet (Kotlin port)",
            role = "Japanese G2P tables", shipsIn = "APK (source)",
            licenseId = "MIT",
            copyright = listOf(
                "Kotlin port: Copyright (c) 2026 marmalade-tts contributors",
                "Ported from misaki — Copyright (c) hexgrad (MIT)",
            ),
            textAsset = "MIT.txt",
            note = "Clean-room port — no upstream code copied; only the " +
                "algorithm and mapping tables are reimplemented.",
        ),
        Component(
            key = "kokoro",
            name = "Kokoro-82M", role = "Neural voice model",
            shipsIn = "Engine bundle",
            licenseId = "Apache-2.0",
            copyright = listOf("Copyright (c) hexgrad and contributors"),
        ),
        Component(
            key = "kittentts",
            name = "KittenTTS (nano / mini)", role = "Neural voice model",
            shipsIn = "Engine bundle",
            licenseId = "Apache-2.0",
            copyright = listOf("Copyright (c) KittenML contributors"),
        ),
        Component(
            key = "pocket-model",
            name = "Pocket TTS (Kyutai) — model code", role = "Neural voice model (English)",
            shipsIn = "Engine bundle",
            licenseId = "MIT",
            copyright = listOf("Copyright (c) Kyutai"),
            textAsset = "pocket.txt",
        ),
        Component(
            key = "pocket-voices-ccby",
            name = "Pocket TTS voices (CC-BY-4.0)", role = "Reference voice prompts",
            shipsIn = "Engine bundle",
            licenseId = "CC-BY-4.0",
            copyright = listOf(
                "CSTR VCTK Corpus — Centre for Speech Technology Research, " +
                    "University of Edinburgh (azelma, eponine, fantine)",
                "\"Alba Mackenna\" — Kyutai (alba)",
            ),
            note = "Voices: alba, azelma, eponine, fantine.",
        ),
        Component(
            key = "pocket-voices-cc0",
            name = "Pocket TTS voices (CC0-1.0)", role = "Reference voice prompts",
            shipsIn = "Engine bundle",
            licenseId = "CC0-1.0",
            copyright = listOf(
                "Unmute Voice Donation Project contributors — public-domain " +
                    "dedication, no attribution required (javert, marius)",
            ),
        ),
        Component(
            key = "openjtalk-dict",
            name = "open_jtalk dictionary", role = "Japanese MeCab dictionary",
            shipsIn = "Engine bundle",
            licenseId = "Modified BSD",
            copyright = listOf(
                "Copyright (c) 2009 Nara Institute of Science and Technology (NAIST)",
                "Copyright (c) 2011-2017 The UniDic Consortium",
                "Copyright (c) 2008-2016 Nagoya Institute of Technology",
            ),
            textAsset = "openjtalk-dict.txt",
        ),
        Component(
            key = "lexicon-zh",
            name = "lexicon-zh (Mandarin G2P table)",
            role = "Han→IPA Mandarin lexicon (Kokoro Chinese)",
            shipsIn = "Engine bundle",
            licenseId = "CC-BY-SA-4.0",
            copyright = listOf(
                "Pinyin data derived in part from CC-CEDICT — (c) MDBG, cc-cedict.org",
                "Table generated via misaki + pypinyin (MIT)",
            ),
            note = "Share-alike: inherits CC-BY-SA-4.0 from its CC-CEDICT-derived pinyin data.",
        ),
        Component(
            key = "androidx",
            name = "AndroidX / Compose / Kotlin / Hilt / Room", role = "App framework",
            shipsIn = "APK",
            licenseId = "Apache-2.0",
            copyright = listOf(
                "Copyright (c) The Android Open Source Project, JetBrains, and Google",
            ),
            note = "Includes their transitive dependencies (Okio, Guava, Commons " +
                "IO/Codec — all Apache-2.0; plus permissive annotation libraries " +
                "such as jsr305 (BSD) and jakarta.inject (EPL-2.0)).",
        ),
    )

    /** Look up a [License] by id, or null if unknown. */
    fun licenseFor(id: String): License? = licenses.firstOrNull { it.id == id }

    /** Look up a [Component] by its [Component.key], or null if unknown. */
    fun componentFor(key: String): Component? = components.firstOrNull { it.key == key }

    /**
     * Components grouped by license, preserving [licenses] declaration order
     * for the groups and [components] order within each group. Only licenses
     * that actually have components are returned.
     */
    fun groupedByLicense(): List<Pair<License, List<Component>>> =
        licenses.mapNotNull { license ->
            val members = components.filter { it.licenseId == license.id }
            if (members.isEmpty()) null else license to members
        }
}
