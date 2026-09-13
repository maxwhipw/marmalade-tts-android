# German voice review — marmalade-tts

We're working on adding **German** to [marmalade-tts](https://github.com/maxwhipw/marmalade-tts-android)

> **▶ [Listen to the samples with embedded players](https://maxwhipw.github.io/marmalade-tts-android/german/)** — the file links below go to GitHub's viewer, which downloads WAVs instead of playing them.
(see [issue #1](https://github.com/maxwhipw/marmalade-tts-android/issues/1)). None of the
maintainers speak German, so before we build the engine we'd like German speakers to
listen to the candidates and tell us what's good enough.

**How to give feedback:** comment on
[issue #1](https://github.com/maxwhipw/marmalade-tts-android/issues/1). Most useful:
which sample ids sound wrong and *what* is wrong (mispronounced word, weird rhythm,
wrong stress), and an overall 1–5 naturalness rating for each candidate.

## Candidate A — native German voice ("Thorsten")

[Thorsten-Voice/Kokoro](https://huggingface.co/Thorsten-Voice/Kokoro): a German
fine-tune of Kokoro-82M speaking with [Thorsten Müller](https://www.thorsten-voice.de/)'s
own voice, which he recorded and published himself (CC0 dataset, Apache-2.0 model).
This is the quality bar. Click a sample — GitHub plays it inline.

| # | Sentence | Sample |
|---|---|---|
| 01 | Die Bundesregierung hat heute ein neues Gesetz zum Klimaschutz verabschiedet. | [▶ listen](samples/native/de_01_thorsten.wav) |
| 02 | Nach Angaben der Polizei wurden bei dem Unfall auf der Autobahn drei Personen leicht verletzt. | [▶ listen](samples/native/de_02_thorsten.wav) |
| 03 | Der Deutsche Wetterdienst warnt vor schweren Gewittern im Süden des Landes. | [▶ listen](samples/native/de_03_thorsten.wav) |
| 04 | Das neue Modell kostet 2499 Euro und wiegt nur 1,3 Kilogramm. | [▶ listen](samples/native/de_04_thorsten.wav) |
| 05 | Im Stadion waren gestern genau 74213 Zuschauer, das sind 8 Prozent mehr als sonst. | [▶ listen](samples/native/de_05_thorsten.wav) |
| 06 | Die Strecke ist 42 Kilometer lang und wir starten bei Kilometer 17. | [▶ listen](samples/native/de_06_thorsten.wav) |
| 07 | Am 14. Mai 2026 beginnt die Konferenz pünktlich um 9 Uhr. | [▶ listen](samples/native/de_07_thorsten.wav) |
| 08 | Der Vertrag läuft vom 1. Januar 2025 bis zum 31. Dezember 2027. | [▶ listen](samples/native/de_08_thorsten.wav) |
| 09 | Die Donaudampfschifffahrtsgesellschaft sucht dringend neue Mitarbeiter. | [▶ listen](samples/native/de_09_thorsten.wav) |
| 10 | Die Geschwindigkeitsbegrenzung auf der Kraftfahrzeugstraße wurde gestern aufgehoben. | [▶ listen](samples/native/de_10_thorsten.wav) |
| 11 | Für die Krankenversicherung benötigen wir Ihre Sozialversicherungsnummer. | [▶ listen](samples/native/de_11_thorsten.wav) |
| 12 | Könntest du mir bitte sagen, wann der nächste Zug nach München fährt? | [▶ listen](samples/native/de_12_thorsten.wav) |
| 13 | Warum ist der Himmel am Tag eigentlich blau und abends manchmal rot? | [▶ listen](samples/native/de_13_thorsten.wav) |
| 14 | Hast du schon gehört, dass die Ausstellung bis Sonntag verlängert wurde? | [▶ listen](samples/native/de_14_thorsten.wav) |
| 15 | Am Ende des langen Tages war er einfach nur müde und ziemlich ärgerlich. | [▶ listen](samples/native/de_15_thorsten.wav) |
| 16 | Der kleine See im Wald war an diesem Morgen spiegelglatt und frisch. | [▶ listen](samples/native/de_16_thorsten.wav) |
| 17 | Ich habe das Meeting mit dem Team auf morgen Nachmittag verschoben. | [▶ listen](samples/native/de_17_thorsten.wav) |
| 18 | Bitte lade die neueste Software herunter und starte danach den Computer neu. | [▶ listen](samples/native/de_18_thorsten.wav) |
| 19 | Nach dem Workshop gehen wir noch zum Brainstorming und danach zum Lunch. | [▶ listen](samples/native/de_19_thorsten.wav) |
| 20 | Der Professor sagte: Bitte reichen Sie Ihre Hausarbeit bis Freitag um 18 Uhr ein. | [▶ listen](samples/native/de_20_thorsten.wav) |

*Objective floor-check: faster-whisper large-v3 transcribes these back at
0.9% word error rate (intelligibility only — naturalness
is what we're asking you about).*

## Candidate B (experimental) — existing voices speaking German with an accent

The unmodified multilingual Kokoro model speaking German through phonemization only —
no German training. This would let *every* existing voice speak German (and other
languages), at the cost of a foreign accent. We'd like to know: **is this intelligible,
and would the accent be tolerable as a fallback** for voices that have no native German
version?

| # | Voice | Sentence | Sample |
|---|---|---|---|
| 01 | ef_dora | Die Bundesregierung hat heute ein neues Gesetz zum Klimaschutz verabschiedet. | [▶ listen](samples/experimental/de_01_ef_dora.wav) |
| 12 | af_heart | Könntest du mir bitte sagen, wann der nächste Zug nach München fährt? | [▶ listen](samples/experimental/de_12_af_heart.wav) |
| 12 | am_michael | Könntest du mir bitte sagen, wann der nächste Zug nach München fährt? | [▶ listen](samples/experimental/de_12_am_michael.wav) |
| 12 | ef_dora | Könntest du mir bitte sagen, wann der nächste Zug nach München fährt? | [▶ listen](samples/experimental/de_12_ef_dora.wav) |
| 17 | af_heart | Ich habe das Meeting mit dem Team auf morgen Nachmittag verschoben. | [▶ listen](samples/experimental/de_17_af_heart.wav) |
| 17 | am_michael | Ich habe das Meeting mit dem Team auf morgen Nachmittag verschoben. | [▶ listen](samples/experimental/de_17_am_michael.wav) |
| 17 | ef_dora | Ich habe das Meeting mit dem Team auf morgen Nachmittag verschoben. | [▶ listen](samples/experimental/de_17_ef_dora.wav) |
| 20 | ef_dora | Der Professor sagte: Bitte reichen Sie Ihre Hausarbeit bis Freitag um 18 Uhr ein. | [▶ listen](samples/experimental/de_20_ef_dora.wav) |

*Floor-check word error rates: 4.5–9% depending on voice.*

## Questions for reviewers

1. Candidate A: naturalness 1–5? Any mispronounced words (give the sample #)?
2. Candidate A: would you use this as a daily screen-reader / read-aloud voice?
3. Candidate B: intelligible? How bad is the accent (charming / tolerable / unusable)?
4. Anything German-specific we should test that these sentences miss?

## Credits & licenses

All audio here is synthesized output, provided for evaluation.

- **Thorsten-Voice/Kokoro** — Apache-2.0, by Thorsten Müller ([thorsten-voice.de](https://www.thorsten-voice.de/)), fine-tuned on his own CC0 dataset.
- **Kokoro-82M** — Apache-2.0, by hexgrad ([hexgrad/Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M)).
- German fine-tuning recipe — [kikiri-tts](https://github.com/semidark/kikiri-tts) by semidark, Apache-2.0.
- Phonemization — [espeak-ng](https://github.com/espeak-ng/espeak-ng) (GPL-3.0, invoked as a tool).
