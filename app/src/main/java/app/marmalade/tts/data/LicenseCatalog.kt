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
 *  - **MIT / BSD / Unicode components** each carry their own exact license
 *    text ([Component.textAsset]) with the correct holder — sourced verbatim
 *    where the repo vendors it (Open JTalk / MeCab `COPYING`, espeak-ng's
 *    `COPYING.UCD`) or from upstream at the shipped tag (cutlet, slf4j, ONNX
 *    Runtime's third-party notices), else the canonical body + the
 *    authoritative copyright line (ONNX Runtime, Pocket).
 *  - **GPL-3.0 / Apache-2.0 / CC-BY-4.0** are standalone license bodies with
 *    no embedded licensor copyright (attribution lives in NOTICE files /
 *    source headers), so those share one body ([License.sharedAsset]) and the
 *    component's [Component.copyright] supplies the attribution. Such a
 *    component must NOT set [Component.textAsset] to the shared body: that
 *    marks the body as embedding the copyright, and the attribution would
 *    never be shown.
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
        License("Modified BSD", null, "https://open-jtalk.sourceforge.net/"),
        // Embeds Unicode, Inc.'s copyright in the body, like MIT/BSD.
        License(
            "Unicode-DFS-2016", null,
            "https://www.unicode.org/license.txt",
        ),
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
        // OFL keeps the font's copyright notice separate from the license
        // body (the notice travels with the font files), so the body is
        // shareable like GPL/Apache and Component.copyright carries the
        // per-font attribution.
        License(
            "OFL-1.1", "OFL-1.1.txt",
            "https://openfontlicense.org",
        ),
        // Custom attribution — no standard body and no embedded copyright
        // line, so (like MIT/BSD) the component ships its own exact text.
        // Used by the English "Jenny (Dioco)" VITS pack, whose model weights
        // and training dataset each carry their own short custom-attribution
        // terms (quoted verbatim in the component's bundled text).
        License(
            "Custom attribution", null,
            "https://github.com/dioco-group/jenny-tts-dataset",
        ),
        // Not one license: the notices file Microsoft publishes for the
        // third-party code statically linked into libonnxruntime.so, each
        // entry carrying its own license and holder (MIT, BSD, Apache-2.0,
        // MPL-2.0 for Eigen, …). Reproduced verbatim as one text.
        License(
            "Third-party notices", null,
            "https://github.com/microsoft/onnxruntime/blob/v1.26.0/ThirdPartyNotices.txt",
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
            shipsIn = "APK (library and full espeak-ng-data, both built from " +
                "source); Kitten and Kokoro engine bundles also carry a copy " +
                "of espeak-ng-data",
            licenseId = "GPL-3.0-or-later",
            // Holders and years as stated in the headers of the pinned
            // third_party/espeak-ng sources the APK compiles.
            copyright = listOf(
                "Copyright (C) 2005-2015 Jonathan Duddington",
                "Copyright (C) 2012-2021 Reece H. Dunn",
                "Copyright (C) 2018-2022 Juho Hiltunen",
                "speechPlayer: Copyright 2014 NV Access Limited",
                "and the other espeak-ng contributors (see the source file headers)",
            ),
        ),
        Component(
            key = "unicode-ucd",
            name = "Unicode Character Database (via ucd-tools)",
            role = "Unicode character tables compiled into espeak-ng",
            shipsIn = "APK (compiled in)",
            licenseId = "Unicode-DFS-2016",
            copyright = listOf("Copyright © 1991-2018 Unicode, Inc."),
            textAsset = "unicode.txt",
            note = "espeak-ng's ucd-tools library (itself GPL-3.0-or-later, " +
                "part of espeak-ng) carries tables generated from the Unicode " +
                "Character Database; espeak-ng's COPYING.UCD is reproduced " +
                "verbatim.",
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
            key = "onnxruntime-notices",
            name = "ONNX Runtime third-party notices",
            role = "Libraries statically linked into ONNX Runtime " +
                "(XNNPACK, protobuf, Abseil, FlatBuffers, Eigen, …)",
            shipsIn = "APK (inside libonnxruntime.so)",
            licenseId = "Third-party notices",
            copyright = listOf(
                "Notices as published by Microsoft with ONNX Runtime 1.26.0 " +
                    "(ThirdPartyNotices.txt) — each entry names its own holder",
            ),
            textAsset = "onnxruntime-third-party-notices.txt",
        ),
        Component(
            key = "commons-compress",
            name = "Apache Commons Compress", role = "Engine-bundle extraction",
            shipsIn = "APK",
            licenseId = "Apache-2.0",
            copyright = listOf("Copyright (c) The Apache Software Foundation"),
        ),
        Component(
            key = "readability4j",
            name = "Readability4J", role = "Reader-mode article extraction",
            shipsIn = "APK",
            licenseId = "Apache-2.0",
            copyright = listOf(
                "Copyright 2017 dankito",
                "Kotlin port of Mozilla's Readability.js — Copyright (c) 2010 " +
                    "Arc90 Inc; Copyright (c) 2010-2026 Mozilla and Contributors",
            ),
            note = "Pulls the article text out of a web page the user shared. " +
                "Upstream's own NOTICE attribution for Readability.js is " +
                "reproduced above (Apache-2.0 §4(d)).",
        ),
        Component(
            key = "slf4j",
            name = "SLF4J API", role = "Logging facade (dependency of Readability4J)",
            shipsIn = "APK",
            licenseId = "MIT",
            copyright = listOf("Copyright (c) 2004-2017 QOS.ch"),
            textAsset = "slf4j.txt",
            note = "slf4j-api 1.7.25. No logging backend ships, so it " +
                "falls back to its no-op logger.",
        ),
        Component(
            key = "jsoup",
            name = "jsoup", role = "HTML parser (reader mode)",
            shipsIn = "APK",
            licenseId = "MIT",
            copyright = listOf("Copyright (c) 2009-2026 Jonathan Hedley"),
            textAsset = "jsoup.txt",
            note = "Parses fetched article HTML for Readability4J. Declared " +
                "directly so it also replaces Readability4J's own transitive " +
                "jsoup 1.11.2, which carries CVE-2021-37714.",
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
            key = "cutlet",
            name = "cutlet", role = "Japanese G2P tables (via misaki's cutlet.py)",
            shipsIn = "APK (source)",
            licenseId = "MIT",
            copyright = listOf("Copyright (c) 2020 Paul O'Leary McCann"),
            textAsset = "cutlet.txt",
            note = "misaki's cutlet.py, which the Kotlin port of the Japanese " +
                "G2P tables follows, is adapted from polm/cutlet.",
        ),
        Component(
            key = "kokoro",
            name = "Kokoro-82M", role = "Neural voice model",
            shipsIn = "Engine bundle",
            licenseId = "Apache-2.0",
            copyright = listOf("Copyright (c) hexgrad and contributors"),
            note = "The shipped model is Marmalade's selectively int8-" +
                "quantized build of the ONNX export from " +
                "github.com/thewh1teagle/kokoro-onnx, as packaged for " +
                "multilingual use by k2-fsa/sherpa-onnx. The per-node " +
                "quantization-sensitivity method follows Adrian Lyjak's " +
                "Kokoro quantization write-up (adrianlyjak.com).",
        ),
        Component(
            key = "kokoro-de",
            name = "Thorsten-Voice/Kokoro (German fine-tune)",
            role = "Neural voice model (German)",
            shipsIn = "Engine bundle",
            licenseId = "Apache-2.0",
            copyright = listOf(
                "Copyright (c) Thorsten Müller (Thorsten-Voice)",
                "Base model: Copyright (c) hexgrad and contributors",
            ),
            note = "A German fine-tune of hexgrad/Kokoro-82M, released by the " +
                "Thorsten-Voice project under Apache-2.0 \"consistent with the " +
                "base Kokoro-82M model and the CC0-licensed Thorsten-Voice " +
                "dataset used for fine-tuning\". The shipped model.onnx is " +
                "Marmalade's static-QDQ int8 build of the k2-fsa/sherpa-onnx " +
                "ONNX export of that checkpoint.",
        ),
        Component(
            key = "misaki-cutlet",
            name = "misaki / cutlet (Kotlin port)",
            role = "Japanese G2P tables", shipsIn = "APK (source)",
            licenseId = "Apache-2.0",
            copyright = listOf(
                "Kotlin port: Copyright (c) 2026 marmalade-tts contributors",
                "Ported from misaki's cutlet.py — Copyright (c) hexgrad (Apache-2.0)",
                "cutlet.py adapted from polm/cutlet — Copyright (c) 2020 " +
                    "Paul O'Leary McCann (MIT)",
            ),
            note = "Clean-room port — no upstream code copied; only the " +
                "algorithm and mapping tables are reimplemented.",
        ),
        Component(
            key = "misaki-de",
            name = "misaki (German G2P port)",
            role = "German phonemization tables", shipsIn = "APK (source)",
            licenseId = "Apache-2.0",
            copyright = listOf(
                "Kotlin port: Copyright (c) 2026 marmalade-tts contributors",
                "Ported from semidark/misaki, a fork of hexgrad/misaki (Apache-2.0)",
                "Override lexicon originates from kikiri-tts PR #28 (author dida-80b)",
            ),
            note = "Clean-room Kotlin port of the German DEG2P pipeline (text " +
                "normalizer + pronunciation-override lexicon) that drives the " +
                "app's espeak-de path for the native German Kokoro engine — no " +
                "upstream code copied, only the algorithm and tables.",
        ),
        Component(
            key = "kittentts",
            name = "KittenTTS (nano)", role = "Neural voice model",
            shipsIn = "APK (baked-in) and engine bundle",
            licenseId = "Apache-2.0",
            copyright = listOf("Copyright (c) KittenML contributors"),
        ),
        Component(
            key = "vits-jenny-dioco",
            name = "Jenny (Dioco) — English VITS voice pack",
            role = "Neural voice model (English)",
            shipsIn = "Voice pack (downloaded)",
            licenseId = "Custom attribution",
            copyright = listOf(
                "Model weights: Copyright (c) Bryce Beattie " +
                    "(https://brycebeattie.com/files/tts/)",
                "Training data — jenny-tts-dataset: Copyright (c) Jenny / " +
                    "dioco-group (https://github.com/dioco-group/jenny-tts-dataset)",
            ),
            textAsset = "jenny-dioco.txt",
            note = "An independent from-scratch VITS model for the VITS " +
                "Marmalade engine — NOT the rhasspy/piper-voices " +
                "jenny_dioco checkpoint (which is fine-tuned from a " +
                "restrictively-licensed base and is not used). The dataset's " +
                "attribution term requires the voice be credited \"Jenny " +
                "(Dioco)\", which is its in-app display name. Both the weights " +
                "and the dataset permit commercial use; the trainer imposes " +
                "\"no further license or restrictions\". Full verbatim terms " +
                "in the bundled text.",
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
                "IO/Codec, javax.inject / jakarta.inject — all Apache-2.0; plus " +
                "the jsr305 annotations (BSD)).",
        ),
        Component(
            key = "manrope",
            name = "Manrope", role = "Brand font (body / headings)",
            shipsIn = "APK",
            licenseId = "OFL-1.1",
            copyright = listOf(
                "Copyright 2019 The Manrope Project Authors " +
                    "(https://github.com/sharanda/manrope)",
            ),
        ),
        Component(
            key = "momo-trust-display",
            name = "Momo Trust Display", role = "Brand font (wordmark)",
            shipsIn = "APK",
            licenseId = "OFL-1.1",
            copyright = listOf(
                "Copyright 2024 The Momo Trust Project Authors " +
                    "(https://github.com/typeassociates/MomoTrustDisplay)",
            ),
        ),
        Component(
            key = "fredoka",
            name = "Fredoka", role = "Brand font (wordmark fallback)",
            shipsIn = "APK",
            licenseId = "OFL-1.1",
            copyright = listOf(
                "Copyright 2016 The Fredoka Project Authors " +
                    "(https://github.com/hafontia/Fredoka-One)",
            ),
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
