# Language support status

This page tracks where each requested language stands. Discussion happens in
[issue #1](https://github.com/maxwhipw/marmalade-tts-android/issues/1).

**What a language needs before it ships:**

1. **Phonemization** — a grapheme-to-phoneme front end (we use espeak-ng, which
   covers ~100 languages). This alone lets existing voices speak the language
   *with a foreign accent* — often intelligible, tested per-language below.
2. **An ethically-trained native model or dataset** — for a voice that actually
   sounds native. Our bar: clear permissive license (Apache/MIT/CC0/CC-BY), data
   recorded or donated for TTS with the speaker's consent (Common Voice, or a
   speaker publishing their own voice), no cloning of people without consent, no
   undisclosed training data. Models that fail this bar are noted so the research
   isn't repeated.
3. **Real-time on phone CPUs** — streaming synthesis at or below real time on a
   modern phone.

## Supported today (native voices)

English (US/GB), Spanish, French, Hindi, Italian, Portuguese, Japanese, Mandarin —
via the multilingual Kokoro engine's official voices.

## Requested languages

| Language | Phonemizer | Accented fallback tested? | Ethical dataset | Ethical native model | Status |
|---|---|---|---|---|---|
| **German** | ✅ espeak-ng (one symbol substitution needed) | ✅ rated by native speakers: "charming / tolerable" | ✅ [Thorsten-Voice](https://www.thorsten-voice.de/) (CC0, self-published) | ✅ validated: [Thorsten-Voice/Kokoro](https://huggingface.co/Thorsten-Voice/Kokoro) (Apache-2.0) | **Review passed — engine work underway.** Two German speakers rated the native candidate 4.5/5 naturalness and daily-usable ([samples](https://maxwhipw.github.io/marmalade-tts-android/german/)). Known issues to address: ordinal dates ("14. März"), some English loanwords pronounced as German, "Stadion" |
| **Bulgarian** | ✅ espeak-ng (one symbol substitution needed) | ✅ intelligible — ~10% WER floor-check | 🔍 [Common Voice bg](https://commonvoice.mozilla.org/) (CC0, small) | ❌ none clean found yet (one candidate's recording provenance under verification) | Accented fallback is the realistic near-term route; a native voice needs a dataset — see "How to help" |
| **Ukrainian** | ✅ espeak-ng + a grapheme-input model that needs no phonemizer | ✅ native voices built | ✅ [ukrainian-tts-datasets](https://github.com/egorsmkv/ukrainian-tts-datasets) (Apache-2.0, purpose-recorded in a professional studio) | ✅ **built**: 4 voices (Lada, Mykyta, Tetiana + a small Lada model) running on our on-device VITS engine | **Community review open** — Ukrainian speakers please [review the samples](https://maxwhipw.github.io/marmalade-tts-android/ukrainian/) |
| **Russian** | ✅ espeak-ng (vocab check pending) | ⏳ not yet tested | ✅ [Common Voice ru](https://commonvoice.mozilla.org/) (CC0, ~252 h validated); [NabuCasa voice-datasets](https://github.com/NabuCasa/voice-datasets) (CC0, donated for TTS) | ❌ the Piper `ru_RU-denis` / `ru_RU-dmitri` candidates were **withdrawn on closer review**: their model cards state they were fine-tuned from the English `lessac` voice, whose training data is under a restrictive research-only licence — so the base weights don't meet our bar even though the Russian fine-tune data is CC0. A native Russian voice needs a from-scratch training run on the clean datasets (feasible — see "How to help") | Clean datasets exist; no clean ready-made model |

**Community-suggested German datasets** (thanks @suuuehgi — license/provenance
review in progress before any are used for training):
[HUI-Audio-Corpus-German](https://opendata.iisys.de/dataset/hui-audio-corpus-german/) (note: no explicit license grant; LibriVox-derived),
[LibriVoxDeEn](https://www.cl.uni-heidelberg.de/statnlpgroup/librivoxdeen/),
[M-AILABS](https://github.com/i-celeste-aurora/m-ailabs-dataset),
[Common Voice de](https://mozilladatacollective.com/organization/cmfh0j9o10006ns07jq45h7xk),
[OpenSLR 94](https://www.openslr.org/94/).

## Languages being added now (community review open)

These run on our own on-device VITS engine ("VITS Marmalade" — no GPL Piper
runtime), with per-voice provenance verified. Each links to a sample page —
native speakers, please review:

| Language | Voices | Data provenance | Review |
|---|---|---|---|
| **Icelandic** | Búi, Salka, Steinn, Ugla | [Talrómur](http://hdl.handle.net/20.500.12537/104) — RÚV studio, national LT programme, CC BY 4.0, trained from scratch | [samples](https://maxwhipw.github.io/marmalade-tts-android/icelandic/) |
| **Swedish** | NST | NST synthesis corpus — professional voice actor recorded for TTS, CC0 (Språkbanken), trained from scratch by KBLab | [samples](https://maxwhipw.github.io/marmalade-tts-android/swedish/) |
| **Kazakh** | Iseke, Raya + 4 more | [KazakhTTS](https://github.com/IS2AI/Kazakh_TTS) — ISSAI/Nazarbayev University, informed-consent-documented professional narrators, CC BY 4.0, trained from scratch | [samples](https://maxwhipw.github.io/marmalade-tts-android/kazakh/) |
| **Norwegian (bokmål)** | 10 speakers, 5 dialect areas | [NVCC](https://www.nb.no/sprakbanken/ressurskatalog/oai-nb-no-sbr-75/) — National Library of Norway, CC0, trained from scratch. **Honest caveat:** recorded in meeting rooms, not a studio — these are rougher and will be labeled as such in the app | [samples](https://maxwhipw.github.io/marmalade-tts-android/norwegian/) |

### Evaluated and not adopted

| Model | Why not |
|---|---|
| kikiri-tts German family (kikiri-german-martin / -victoria / -bernd) and ONNX conversions | The base model's own card states it was trained entirely on synthetic (TTS-generated) audio; the generating model is not disclosed. Doesn't meet our training-data provenance bar. (The training *recipe* is excellent and is what the Thorsten-Voice candidate above was built with.) |
| MMS-TTS (Meta, 1100+ languages) | CC-BY-NC — non-commercial license. |
| Silero TTS (ru + Cyrillic bundle) | CC-BY-NC — non-commercial license (their `base` cis-tts excepted, but the quality voices are NC). |
| bg-tts-v5 | Model card discloses ~400 of ~700 training hours are another TTS system's synthetic output, plus audiobook audio from an undisclosed source; autoregressive architecture also unlikely to run real-time on phones. |
| Supertonic | OpenRAIL license. |
| Piper voices fine-tuned from `lessac` (74 of the 176 official voices, incl. `ru_RU-denis`/`dmitri`, `bg_BG-dimitar`, `de_DE-thorsten` medium/high) | The shared base voice was trained on the Blizzard-2013 "lessac" recordings, which are under a restrictive, individually-granted research licence. The fine-tune data is often clean (CC0), but the base weights carry the restriction, so these don't meet our bar for a shipped product. |
| Piper voices fine-tuned from `ryan` (~20 voices) | The `ryan` base voice's dataset is CC BY-NC-SA — non-commercial terms in the base weights. |
| Piper `lv_LV-aivars` | Its own model card states the released timbre is a voice-conversion clone of a LibriVox narrator who is not identified and did not consent to voice cloning. |
| Piper `cs_CZ-kasandra` | The author's own training-checkpoint repository is tagged CC-BY-NC-4.0, contradicting the CC-BY-4.0 on the voice card; training data and initialization are not documented. |

## How to help

- **Speak a requested language?** Review the sample pages when they're linked above
  and comment on issue #1 — tell us which samples sound wrong and why.
- **Know a dataset?** Link it on issue #1 — what we need: clear license (CC0 /
  CC-BY / public domain), consenting speaker(s), ideally 20+ hours of one clean
  voice. The gold standard is the
  [Thorsten-Voice pattern](https://www.thorsten-voice.de/): a native speaker who
  recorded and published their own voice for exactly this purpose.
- **Want your language added to the accented-fallback tier?** If espeak-ng
  supports it, that's usually a small change — ask on issue #1.
