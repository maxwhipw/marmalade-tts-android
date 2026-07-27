# Language robustness audit — 2026-07-27

Scope: the phonemizer and token-encoding layer of `KokoroDirectEngine`
(9 languages) and `KittenDirectEngine` (en-us only). Everything below is
measured on a desktop replica of the shipping code path, not on device —
see [What could not be tested](#what-could-not-be-tested).

Working artifacts (not in the repo): `~/coding/scratch/lang-audit/`.

---

## 1. Language support and phonemizer routing

Kokoro v1.0 ships 53 voices in 9 languages. Every voice reaches one of
three front ends:

| Language | Voice prefix | Front end | Reference G2P the model was trained with |
|---|---|---|---|
| American English | `af_` `am_` | espeak-ng `en-us` | `misaki.en.G2P` (lexicon; espeak only as OOV fallback) |
| British English | `bf_` `bm_` | espeak-ng `en-gb` | `misaki.en.G2P` (british=True) |
| Spanish | `ef_` `em_` | espeak-ng `es` | `misaki.espeak.EspeakG2P('es')` |
| French | `ff_` | espeak-ng `fr-fr` | `misaki.espeak.EspeakG2P('fr-fr')` |
| Hindi | `hf_` `hm_` | espeak-ng `hi` | `misaki.espeak.EspeakG2P('hi')` |
| Italian | `if_` `im_` | espeak-ng `it` | `misaki.espeak.EspeakG2P('it')` |
| Brazilian Portuguese | `pf_` `pm_` | espeak-ng `pt-br` | `misaki.espeak.EspeakG2P('pt-br')` |
| Japanese | `jf_` `jm_` | **Open JTalk** (NJD frontend) → `CutletJaG2P` | `misaki.ja.JAG2P()` (= cutlet) |
| Mandarin | `zf_` `zm_` | **`lexicon-zh.txt`** greedy longest-prefix match | `misaki.zh.ZHG2P(version='1.0')` |

Where the decision is made:

- `data/KokoroDirectVoiceCatalog.kt:155-169` — `espeakVoiceFor(voiceKey)`
  maps the voice-key first letter to a language string. `'z' -> "en-us"`
  because Mandarin does not go through espeak at all; the espeak voice is
  only used for the Latin fragments between Han runs.
- `engine/kokoro/KokoroDirectEngine.kt:404-406` — an explicit
  `phonemizationLanguage` argument overrides the catalog; otherwise the
  voice decides.
- `engine/kokoro/KokoroDirectEngine.kt:539-579` — `encodeTextToTokens` is
  the actual fork:
  - `lang == "ja"` **and** the Open JTalk dict is installed → Open JTalk
    + `CutletJaG2P` (`:546-550`).
  - text contains a `[一-鿿]+` run **and** `lexicon-zh.txt` is loaded →
    Han runs through `LexiconZh.match`, everything between through
    espeak (`:568-577`).
  - otherwise → espeak (`:554`).
- Both fallbacks are silent-degrade: a pre-v18 bundle without
  `openjtalk_dic` sends Japanese to espeak; a pre-v17 bundle without
  `lexicon-zh.txt` sends Mandarin to espeak. Both are logged at WARN
  (`:304`, `:317`, `:320`) and both produce badly wrong audio.
- Native: `cpp/espeak_jni.c:130-292` (clause loop + punctuation
  re-injection), `cpp/openjtalk_jni.c` (text2mecab → NJD chain, stopping
  before njd2jpcommon / HTS_engine).
- Token encoding for **all** languages funnels through one table:
  `engine/kitten/IpaTokenVocab.kt:44-157`.

`KittenDirectEngine` is en-us only (`:367`, `:661`) and shares
`encodePhonemes` from the same table, so every vocab-table defect below
applies to it as well.

---

## 2. The Japanese pitch-accent question

**Does JA reach Open JTalk on the Android path?** Yes. `lang == "ja"`
routes to `OpenJtalkPhonemizer.analyze()`, which runs the real NJD
frontend chain and returns `string / read / pron / acc / mora_size /
chain_flag / pos` per morpheme.

**Does pitch accent survive to the model?** No — and that is correct for
this model. Evidence:

1. `kokoro/pipeline.py:117` constructs `ja.JAG2P()` with no arguments.
2. `misaki/ja.py:251` defaults `version='cutlet'`, and `:272-274` short
   circuits to `self.cutlet(text)`. The pyopenjtalk branch with pitch
   (`:275-356`) never runs for Kokoro.
3. `misaki/cutlet.py` is segmental only. It emits no pitch marker.
4. Even if the pyopenjtalk branch were used, it returns `result + pitch`
   where `pitch` is a parallel string of `_` `-` `^` `j`
   (`misaki/ja.py:310, 352`). **None of `_`, `-`, `^` exist in Kokoro
   v1.0's `tokens.txt`.** That path targets a different model.

So `CutletJaG2P.kt` parsing `acc` and `chainFlag` and then discarding
`acc` is the right call for Kokoro v1.0. This *narrows* the wiki note
[[japanese-pitch-accent-required]]: per-mora pitch markers are required
for Japanese TTS quality in general, and plain espeak-ja remains
disqualified, but Kokoro v1.0 specifically cannot consume them — it
learned Japanese pitch implicitly from training audio. Marmalade is
using the right frontend for the model it ships.

**Two code comments say the opposite and are wrong:**

- `phonemizer/OpenJtalkPhonemizer.kt:15-17` — "This produces the NJD
  features — kanji→kana reading, mora segmentation, pitch accent — that
  CutletJaG2P turns into Kokoro's IPA **+ pitch-marker token string**".
- `cpp/openjtalk_jni.c:22-24` — "CutletJaG2P.kt (Kotlin) maps the
  katakana `pron` + `acc` to Kokoro's IPA **+ pitch markers**".

`engine/kokoro/KokoroDirectEngine.kt:542-543` has it right ("segmental,
no pitch markers"). Fix the two stale ones.

`chainFlag`, however, *is* parsed and *should* be used — see defect D3.

---

## 3. Method

Three arms, all run on the exact bundle the app installs
(`~/coding/scratch/engines-respin/work/kokoro-direct-v1_0`) and the exact
espeak-ng the APK compiles (1.52.0, submodule `4870adfa`):

- **A — phoneme-string diff (no audio, no STT).** For each sentence,
  produce the IPA the Android path builds and the IPA the model's
  training G2P builds, and take a normalised character edit distance
  (`levenshtein / max(len)`). This is the primary instrument: it is
  deterministic and independent of any listener or recogniser.
- **B — vocab ablation.** Hold the IPA fixed and count characters that
  `IpaTokenVocab.kt` maps to `PAD_TOKEN(0)` vs characters the bundle's
  own `tokens.txt` covers. Isolates a table defect from a G2P defect.
- **C — STT round trip.** Synthesize through the ONNX model, transcribe
  with `faster-whisper-large-v3-turbo`, score WER (space-delimited,
  NFKC-lowercased, punctuation stripped) and CER against the source
  text. **This arm is weak** — Whisper is robust to bad prosody, so a
  near-zero WER says "intelligible", not "natural". It is only useful
  where it is non-zero.

Corpus: 3 sentences per espeak language (21), 4 Japanese, 3 Mandarin.
Small. Treat per-language means as indicative, not tight.

Scripts: `analyze.py` (arms A/B/C on the prior run's data), `analyze2.py`
(digraph accounting), `analyze3.py` (real Open JTalk + real misaki ja/zh
references), all in `~/coding/scratch/lang-audit/`.

---

## 4. Results

### 4.1 Token table vs the model's own `tokens.txt`

`IpaTokenVocab.kt` has 114 entries; Kokoro's `tokens.txt` has 114; they
are **not the same 114**.

| Symbol | Codepoint | Kokoro id | Kitten id | Kotlin id | Verdict |
|---|---|---|---|---|---|
| `ᵻ` | U+1D7B | 177 | 177 | *absent* | **missing → PAD** |
| `ɯ` | U+026F | 110 | 110 | *absent* | **missing → PAD** |
| `ʔ` | U+0294 | 148 | 148 | *absent* | **missing → PAD** |
| `ᴻ` | U+1D3B | — | — | 177 | **wrong glyph** (comment says `ᵻ`) |
| `g` | U+0067 | — | 49 | 49 | Kitten-only; latent on Kokoro |
| `$` | U+0024 | — | 0 | 0 | harmless (PAD either way) |
| `A` | U+0041 | 24 | **17** | 24 | Kitten mis-encoded; latent |
| `"` | U+0022 | 11 | **15** | 11 | Kitten mis-encoded; latent |

`ᴻ` at 177 is a transcription typo for the visually similar `ᵻ` — the
in-file comment on `IpaTokenVocab.kt:139` literally reads `// ᵻ`.

The file header's claim (`IpaTokenVocab.kt:7-8`) that "Both Kokoro and
Kitten consume the same 178-symbol vocabulary" is false: the two vocabs
differ by 61 symbols in one direction, 12 in the other, and disagree on
two IDs.

### 4.2 PAD-substitution rate on the shipping path

Chars encoded as `PAD_TOKEN(0)` — i.e. silently blanked — per language,
holding the IPA fixed and swapping only the table:

| lang | chars | PAD @ `IpaTokenVocab.kt` | PAD @ bundle `tokens.txt` | offending |
|---|---|---|---|---|
| en-us | 253 | 3 (1.19 %) | 0 | `ᵻ` ×3 |
| en-gb | 184 | 0 | 0 | — |
| es | 192 | 0 | 0 | — |
| fr-fr | 190 | 4 (2.11 %) | 4 (2.11 %) | `-` ×4 |
| it | 187 | 0 | 0 | — |
| pt-br | 228 | 0 | 0 | — |
| hi | 216 | 0 | 0 | — |
| **ja** | 229 | **12 (5.24 %)** | **0** | `ɯ` ×8, `ʔ` ×4 |
| cmn | 155 | 0 | 0 | — |

A wider en-us probe (5 sentences chosen to trigger reduced vowels) gives
**11 `ᵻ` in 208 IPA chars — 5.3 %**. The 1.19 % above is corpus luck;
`ᵻ` is espeak-ng's reduced `/ɪ/` and it is common in American English
(`roses`, `houses`, `cabbages`, `judges`, `oranges` all carry one). It
does **not** appear in espeak `en-gb` output.

The French `-` is not a table defect: espeak emits `lə-`, `ʒə-`, `də-`
for elidable clitics and Kokoro's vocab has no hyphen either. misaki
strips it (`misaki/espeak.py:104`, `ps.replace('-','')`); Marmalade does
not.

### 4.3 IPA divergence, Android vs training G2P

Normalised character edit distance, mean over sentences. Lower is
better; 0 would mean Marmalade builds exactly the string the model was
trained on.

| lang | raw | stress marks stripped | n |
|---|---|---|---|
| **en-us** | **0.298** | 0.324 | 3 |
| **en-gb** | **0.179** | 0.189 | 3 |
| **cmn** | **0.163** | — | 3 |
| it | 0.084 | 0.094 | 3 |
| pt-br | 0.089 | 0.102 | 3 |
| **ja** | 0.076 | — | 4 |
| fr-fr | 0.074 | 0.084 | 3 |
| es | 0.058 | 0.066 | 3 |
| hi | 0.011 | 0.012 | 3 |

### 4.4 Untranslated espeak digraphs

`misaki.espeak.EspeakG2P` runs espeak with `tie='^'` and then rewrites
tied digraphs into Kokoro's dedicated single-character tokens
(`misaki/espeak.py:71-79`). Marmalade's `espeak_jni.c` does no such
rewrite, so each of these enters the model as **two** tokens for the
constituent monophthongs instead of the **one** token the model trained
on:

| lang | IPA chars | digraph hits | per 100 chars | breakdown |
|---|---|---|---|---|
| en-us | 253 | 14 | **5.53** | `aʊ`→`W`×3, `dʒ`→`ʤ`×3, `aɪ`→`I`×3, `oʊ`→`O`×2, `eɪ`→`A`×2, `tʃ`→`ʧ`×1 |
| en-gb | 184 | 10 | **5.43** | `dʒ`→`ʤ`×3, `aʊ`→`W`×2, `əʊ`→`Q`×2, `eɪ`→`A`×1, `tʃ`→`ʧ`×1, `aɪ`→`I`×1 |
| pt-br | 228 | 6 | 2.63 | `eɪ`→`A`×2, `tʃ`→`ʧ`×2, `aɪ`→`I`×1, `aʊ`→`W`×1 |
| it | 187 | 3 | 1.60 | `tʃ`→`ʧ`×1, `dʒ`→`ʤ`×1, `ss`→`S`×1 |
| es, fr-fr, hi | — | 0 | 0.00 | — |

The same count on the misaki reference strings is **0 for every
language**, confirming the mapping direction.

Concretely, `en-us_0`:

```
android: ðə kwˈɪk bɹˈaʊn fˈɑːks dʒˈʌmps ˌoʊvɚ ðə lˈeɪzi dˈɑːɡ .
misaki : ðə kwˈɪk bɹˈWn  fˈɑks  ʤˈʌmps  ˈOvəɹ ðə lˈAzi  dˈɔɡ.
```

### 4.5 Japanese, against the real pipeline

The prior run's Japanese arm hand-wrote the Open JTalk readings in
`ja_texts.py`. Driving `CutletJaG2P`'s exact algorithm from **real**
`pyopenjtalk.run_frontend` output instead changes the picture — see
[Soundness](#6-soundness-of-the-prior-run). Real results:

```
text   : 学校に行って、切符を買って、日本語を勉強しました。
android: ɡaʔkoː ɲi iʔ te, kʲiʔpɯ o kaʔ te, ɲihoŋɡo o beŋkʲoː ɕi maɕi ta.
misaki : ɡaʔkoː ɲi iʔte    kʲiʔpɯ o kaʔte  ɲiʔpoŋɡo o beŋkʲoː ɕi maɕita
```

Per sentence, norm edit distance 0.033 / 0.067 / 0.127 / 0.075 (mean
0.076). Most of that is **spurious word spaces**, not wrong phonemes.

Re-synthesized and re-transcribed with the real IPA, android table vs
bundle table:

| id | PAD | CER (android) | CER (bundle table) | hypothesis (android) |
|---|---|---|---|---|
| 0 | 1 | 0.053 | 0.053 | 早い茶色の… (homograph, not a defect) |
| 1 | 0 | 0.000 | 0.000 | — |
| **2** | **5** | **0.182** | **0.000** | 学校に**行いて**、**キープ**を買って… |
| 3 | 0 | 0.000 | 0.000 | — |
| mean | | **0.059** | **0.013** | |

`切符` = キップ = `kʲiʔpɯ`. With `ʔ`→PAD and `ɯ`→PAD it becomes
`kʲi_p_` and the model renders "キープ". `行って` = イッテ = `iʔte` →
`i_te` → "行いて". This is a real, audible, lexical-meaning-changing
failure on the shipping path.

Three divergences from misaki are **Marmalade being right and misaki
being wrong** (dictionary differences, naist-jdic vs unidic):
`私` → ワタシ not ワタクシ; `日本語` → ニホンゴ not ニッポンゴ;
`月曜日` → …ビ not …ヒ. Do not "fix" these.

Latin and numeric input in Japanese was probed separately and is fine:
Open JTalk resolves `Ｇｏｏｇｌｅ`→グーグル, `Ｗｉ−Ｆｉ`→ワイファイ,
`2026年3月14日`→ニセンニジューロクネン…, so `CutletJaG2P.kt:139`'s ASCII
pass-through branch rarely fires.

### 4.6 Mandarin, against the real reference

No reference arm existed. `misaki.zh.ZHG2P(version='1.0')` run for the
first time here:

```
text   : 你好，你好吗？我叫王小明，住在北京。
android: ni↓xau↓ni↓xau↓mawo↓ʨjau↘wa↗ŋɕjau↓mi↗ŋꭧu↘ʦai↘pei↓ʨi→ŋ
misaki : ni↓xau↓, ni↓xau↓ ma? wo↓ ʨjau↘ wa↗ŋɕjau↓mi↗ŋ, ꭧu↘ ʦai↘ pei↓ʨi→ŋ.
```

Norm edit distance 0.157 / 0.188 / 0.143 (mean **0.163**) — second worst
of any language, and every character of the difference is **spacing and
punctuation**. Zero phoneme-level PADs; the lexicon's 38-symbol
inventory is fully covered by the Kotlin table (verified over all 68 004
entries).

Two independent causes:

1. `LexiconZh.match` (`:97-135`) concatenates matched entries with **no
   separator at all**, so a Mandarin utterance reaches the model as one
   unbroken run with zero space tokens.
2. Fullwidth CJK punctuation is dropped. `。，？、` are outside
   `[一-鿿]`, so they go to espeak `en-us`, which returns the empty
   string for them (verified directly), and `espeak_jni.c:203-236` only
   re-injects ASCII `,.;:!?` plus U+2013/U+2014/U+2026. Nothing is
   emitted. `TextChunker` does split on `。！？`, so *sentence* pauses
   survive as chunk gaps — but intra-sentence `，` and `、` vanish
   entirely.

STT confirms the lost structure (`你好，你好吗？我叫王小明…` →
`你好你好 卯叫王小明住在北京`, CER 0.143), though CER is not the right
instrument for a prosody defect.

### 4.7 STT round trip (weak evidence — read with 4.3 and 4.4)

Mean over 3 sentences per language, except ja (4) and cmn (3). WER is
meaningless for ja/cmn (not space-delimited).

| lang | variant | WER | CER |
|---|---|---|---|
| en-us | android | 0.121 | 0.033 |
| en-us | misaki | 0.099 | 0.041 |
| en-gb | android | 0.048 | 0.000 |
| en-gb | misaki | 0.000 | 0.000 |
| es | both | 0.000 | 0.000 |
| it | both | 0.000 | 0.000 |
| pt-br | both | 0.000 | 0.000 |
| fr-fr | android | 0.026 | 0.014 |
| fr-fr | misaki | 0.051 | 0.043 |
| hi | android | 0.119 | 0.073 |
| hi | misaki | 0.083 | 0.045 |
| ja | android | — | 0.059 |
| ja | bundle table | — | **0.013** |
| cmn | android | — | 0.075 |

Read this as: **nothing is unintelligible**. Spanish, Italian and
Portuguese round-trip perfectly on both arms. The only place STT
separates the arms cleanly is Japanese, and it does so decisively.
It cannot see the prosody defects in §4.4 and §4.6 at all.

---

## 5. Ranked defects

**D1 — `ᵻ` (U+1D7B) is missing from `IpaTokenVocab.kt`; `ᴻ` (U+1D3B)
sits at its ID.** *Severity: high. Affects: American English, on both
Kokoro and Kitten.*
espeak-ng emits `ᵻ` for the reduced `/ɪ/` at ~5 % of en-us IPA
characters. Every one becomes PAD — a blank slot the model has to render
as nothing. Evidence: `IpaTokenVocab.kt:139` (`'ᴻ' to 177, // ᵻ`) vs
bundle `tokens.txt` (`ᵻ 177`) and Kitten `tokens.txt` (`ᵻ 177`); 11
occurrences in a 208-char en-us probe. Fix: one character.

**D2 — `ɯ` (110) and `ʔ` (148) are missing from `IpaTokenVocab.kt`.**
*Severity: high. Affects: Japanese.*
5.24 % of Japanese IPA characters. Measured end-to-end: mean CER 0.059
→ 0.013 with the bundle table, and `切符`/`行って` are rendered as
different words. Both symbols are present in *both* upstream vocabs.

**D3 — `CutletJaG2P` inserts a space between every NJD node, ignoring
`chainFlag`.** *Severity: medium-high. Affects: Japanese.*
Open JTalk splits inflected verbs and numerals into stem + suffix nodes
(`行っ|て`, `まし|た`, `二|千|二|十|六|年`). `CutletJaG2P.convert`
(`:110-121`) unconditionally emits a space token between them, so
`iʔte`→`iʔ te`, `maɕita`→`maɕi ta`, and a date becomes six prosodic
words. Upstream cutlet regroups nodes before spacing
(`misaki/cutlet.py:306-323`); misaki's pyopenjtalk branch uses
`chain_flag` for exactly this (`misaki/ja.py:284`). `NjdNode.chainFlag`
is already parsed and already documented — it is simply never read.

**D4 — Mandarin loses all word/phrase spacing.** *Severity: medium-high.
Affects: Mandarin.*
`LexiconZh.match` concatenates matched token arrays with no separator
(`:118-124`), so the model sees one continuous run with no token-16
anywhere. misaki emits spaces at prosodic-word boundaries. Note: the
code comment at `LexiconZh.kt:93-95` claims token-for-token parity with
sherpa-onnx's matcher — **this audit did not verify that claim** (no
sherpa source on disk). If sherpa does the same, Marmalade is
sherpa-faithful and training-G2P-unfaithful at the same time.

**D5 — CJK and Devanagari punctuation never reaches the model.**
*Severity: medium. Affects: Mandarin, Hindi.*
`espeak_jni.c:203-236` recognises ASCII `,.;:!?` and U+2013/2014/2026
only. `。`(U+3002) `，`(U+FF0C) `？`(U+FF1F) `、`(U+3001) `।`(U+0964) are
all skipped, and espeak itself returns an empty phoneme string for them.
Verified directly. Japanese is unaffected — `CutletJaG2P.nodeToIpa`
(`:130-137`) folds fullwidth punctuation itself.

**D6 — no digraph→single-token rewrite on the espeak path.**
*Severity: medium, but expensive to fix and possibly deliberate. Affects:
English most, then pt-br and it.*
5.5 % of English phoneme characters are `aɪ aʊ dʒ eɪ oʊ əʊ tʃ ɔɪ` pairs
that Kokoro has dedicated trained tokens for (`I W ʤ A O Q ʧ Y`).
Marmalade sends the constituents. This is the single largest contributor
to the en-us 0.298 divergence. Caveat: this may match sherpa-onnx's
behaviour, in which case fixing it is a deliberate divergence from
sherpa toward upstream Kokoro, and should be A/B'd by ear before
shipping — STT cannot adjudicate it (§4.7).

**D7 — Hindi has no sentence boundary.** *Severity: medium. Affects:
Hindi. Code-evident, not measured.*
`TextChunker.SENTENCE_END`/`CLAUSE_END` (`:34-36`) list `.!?:;` and
`。！？` but not `।` (U+0964). A Hindi paragraph punctuated only with
dandas is therefore a single chunk; with Kokoro's `allowWordSplits =
false` it falls through to `TextChunker.kt:136` and emits oversize, then
`KokoroDirectEngine.kt:455-459` truncates at 500 tokens and **drops the
tail**. Combined with D5 (the danda is also dropped from the IPA), long
Hindi input should be expected to lose text. Not reproduced here — the
corpus is single sentences.

**D8 — French clitic hyphens become PAD.** *Severity: low. Affects:
French.*
espeak emits `lə-`, `ʒə-`, `də-`; 2.1 % of French IPA characters.
Neither Kokoro's vocab nor Kotlin's has `-`. misaki strips it
(`misaki/espeak.py:104`). One `replace`.

**D9 — espeak drops word boundaries between cliticised function words.**
*Severity: low. Affects: all espeak languages.*
`espeak_TextToPhonemes` returns `ɪnðə` for "in the" (verified in both
en-us and en-gb); misaki's phonemizer backend preserves word separators
and gives `ɪn ðə`. Observed, not systematically quantified — the raw
space *count* on the Android arm is higher than misaki's because of
punctuation padding, so this shows up only per-site.

**D10 — Kitten's `A` and `"` are encoded with Kokoro's IDs.**
*Severity: latent (currently unreachable). Affects: Kitten.*
`A` is 17 in Kitten's `tokens.txt` and 24 in Kokoro's; the shared table
uses 24. Same story for `"` (15 vs 11). Neither symbol is reachable on
the current Kitten path (raw espeak never emits `A`; the JNI never
re-injects `"`), so this fires only if D6 is ever fixed. Fixing D6
without splitting the vocab table would silently corrupt Kitten.

**D11 — stale comments claim Japanese pitch markers are emitted.**
*Severity: documentation.*
`phonemizer/OpenJtalkPhonemizer.kt:15-17` and `cpp/openjtalk_jni.c:22-24`
say `CutletJaG2P` produces "IPA + pitch markers". It does not, and for
Kokoro v1.0 it should not (§2). Also stale:
`KokoroDirectVoiceCatalog.kt:144-147` says "Hindi has no espeak-ng 'hi'
entry, so we fall through to en-us" — the code three lines down maps
`'h' -> "hi"`, and espeak-ng 1.52 has a working `hi` voice.
`IpaTokenVocab.kt:7-8` claims Kokoro and Kitten share one 178-symbol
vocab; they share neither the symbol set nor two of the IDs.

---

## 6. Soundness of the prior run

Two problems with the pre-existing `audio_meta.json`, both found and
worked around here:

- **The `android` and `misaki` arms differ in two variables at once.**
  `build_audio.py` pairs the espeak IPA with the Kotlin vocab and the
  misaki IPA with the bundle vocab, so an A/B between them cannot
  attribute a difference to either. §4.2 de-conflates it by re-running
  the encode offline with the IPA held fixed.
- **The Japanese arm did not exercise Open JTalk.** `ja_texts.py`
  hand-writes the katakana readings, and those readings differ from real
  Open JTalk output in **all four** sentences — the hand-written version
  merges verb stem + suffix into single tokens (`トビコエマス` where
  Open JTalk gives `トビコエ`+`マス`), which is precisely the
  segmentation that exposes D3. The prior JA numbers therefore
  *understate* the problem. §4.5 re-ran the whole Japanese arm from real
  `pyopenjtalk.run_frontend` output.

One smaller issue: `build_audio.espeak_ipa`'s punctuation re-injection
pairs clause *i* with source-punctuation *i* positionally, whereas
`espeak_jni.c` takes the first break punctuation *inside* each clause's
consumed byte range. Checking clause count against punctuation count
across all 21 sentences, only **`en-us_2`** ("Doctor Smith arrived at
3:45 p.m. …", 3 clauses / 6 punctuation marks) actually diverges — its
0.306 divergence and 0.077 WER are partly replica artifact and should be
discounted. The three Hindi mismatches are the app's real behaviour
faithfully reproduced (see D5).

Everything else in the prior artifacts checks out: `tokens_truth.json`
is byte-exact against the bundle's `tokens.txt`, `cutlet_port.py`
extracts `HEPBURN` from the live Kotlin source at import so it cannot
drift, and `kokoro_replica.py` uses the shipped `model.onnx`,
`voices.bin` and the APK's espeak version.

---

## 7. What could not be tested

- **Anything on device.** Every number here comes from a desktop replica
  (`kokoro_replica.py`) using the same ONNX, `voices.bin` and espeak
  version. Not covered: ORT-Android/XNNPACK numerics, `SilenceCompressor`,
  real `TextChunker` behaviour across a stream, and the real
  `espeak_jni.c` clause loop (only re-implemented). `connectedAndroidTest`
  is off-limits — it uninstalls the app and wipes Max's engines.
- **Naturalness and prosody.** The whole of D3, D4, D6 and D9 is prosody.
  STT is blind to it and no human listened. These defects are established
  by string comparison against the training G2P, which is strong evidence
  that the input is wrong and *no* evidence about how much worse it
  sounds. A listening A/B is the missing step.
- **sherpa-onnx parity.** Several code comments claim token-for-token
  parity with sherpa (`LexiconZh.kt:93-95`, `KokoroDirectEngine.kt:62`).
  No sherpa source was available; unverified either way.
- **Long-form input.** Corpus is single sentences, 45–70 IPA characters.
  Chunking, the 500-token truncation, cross-chunk prosody and D7 are all
  untested by measurement.
- **Coverage breadth.** 3 sentences per language is enough to find
  systematic defects and not enough to bound their rate. The en-us `ᵻ`
  rate in particular moved from 1.19 % to 5.3 % just by picking different
  sentences.
- **`hi` reference quality.** misaki's Hindi output drops the danda
  entirely and its Spanish output contains `¿`, which Kokoro's own vocab
  has no token for. The reference arm is not flawless; small divergences
  in those two languages may be misaki's fault, not Marmalade's.
- **Voice-level variation.** One voice per language (`af_bella`,
  `bf_emma`, `ef_dora`, `ff_siwis`, `hf_alpha`, `if_sara`, `pf_dora`,
  `jf_alpha`, `zf_xiaoxiao`). The other 44 were not exercised.
- **Kitten and Pocket engines.** Only the shared vocab table was audited
  for Kitten (D1, D10 apply). Pocket has its own tokenizer and was out of
  scope.

---

## 8. Reproducing

`~/coding/scratch/lang-audit/` (has its own `.venv` with onnxruntime +
numpy). Three venvs are involved:

```
.venv                                  # synthesis (onnxruntime)
/home/max/voicebox/backend/venv        # pyopenjtalk, fugashi+unidic, jieba,
                                       # pypinyin, cn2an, misaki, kokoro
/home/max/coding/marmalade-stt-cli/.venv  # faster-whisper
```

```
.venv/bin/python analyze.py    # vocab diff, PAD rates, IPA divergence, WER/CER
.venv/bin/python analyze2.py   # digraph accounting
/home/max/voicebox/backend/venv/bin/python analyze3.py   # real ja + zh references
```

Added by this pass: `analyze.py`, `analyze2.py`, `analyze3.py`,
`ja_real_ipa.json`, `ja_real_meta.json`, `wav/jareal_*.wav`. No app code
was modified.
