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
| **German** | ✅ espeak-ng (one symbol substitution needed) | ✅ intelligible — 4.5–9% WER floor-check | ✅ [Thorsten-Voice](https://www.thorsten-voice.de/) (CC0, self-published) | ✅ candidate: [Thorsten-Voice/Kokoro](https://huggingface.co/Thorsten-Voice/Kokoro) (Apache-2.0) | **Community review open** — German speakers please [review the samples](https://maxwhipw.github.io/marmalade-tts-android/german/) |
| **Bulgarian** | ✅ espeak-ng (one symbol substitution needed) | ✅ intelligible — ~10% WER floor-check | 🔍 [Common Voice bg](https://commonvoice.mozilla.org/) (CC0, small) | ❌ none clean found yet (one candidate's recording provenance under verification) | Accented fallback is the realistic near-term route; a native voice needs a dataset — see "How to help" |
| **Ukrainian** | ✅ espeak-ng (vocab check pending) | ⏳ not yet tested | ✅ [ukrainian-tts-datasets](https://github.com/egorsmkv/ukrainian-tts-datasets) (Apache-2.0, 5 speakers, ~35 h, purpose-recorded); [Common Voice uk](https://commonvoice.mozilla.org/) (CC0, ~108 h validated) | 🔍 candidates: Piper `uk_UA` voices (small VITS, MIT/Apache, provenance traced to the dataset above); integration path under evaluation | Clean dataset + model candidates identified |
| **Russian** | ✅ espeak-ng (vocab check pending) | ⏳ not yet tested | ✅ [Common Voice ru](https://commonvoice.mozilla.org/) (CC0, ~252 h validated); [NabuCasa voice-datasets](https://github.com/NabuCasa/voice-datasets) (CC0, donated for TTS) | 🔍 candidates: Piper `ru_RU-denis` / `ru_RU-dmitri` (small VITS, MIT, trained on the CC0 donated set — provenance stated on their model cards); integration path under evaluation | Clean dataset + model candidates identified |

### Evaluated and not adopted

| Model | Why not |
|---|---|
| kikiri-tts German family (kikiri-german-martin / -victoria / -bernd) and ONNX conversions | The base model's own card states it was trained entirely on synthetic (TTS-generated) audio; the generating model is not disclosed. Doesn't meet our training-data provenance bar. (The training *recipe* is excellent and is what the Thorsten-Voice candidate above was built with.) |
| MMS-TTS (Meta, 1100+ languages) | CC-BY-NC — non-commercial license. |
| Silero TTS (ru + Cyrillic bundle) | CC-BY-NC — non-commercial license (their `base` cis-tts excepted, but the quality voices are NC). |
| bg-tts-v5 | Model card discloses ~400 of ~700 training hours are another TTS system's synthetic output, plus audiobook audio from an undisclosed source; autoregressive architecture also unlikely to run real-time on phones. |
| Supertonic | OpenRAIL license. |

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
