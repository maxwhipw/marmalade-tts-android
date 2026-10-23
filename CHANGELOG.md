# Changelog

All notable changes to **marmalade-tts-android** will be documented here.
This project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed
- **Swiping away "Loading article…" cancels the article**: on Android 14 and
  later it could be swiped away, and the article still started reading once
  it had loaded.
- **Hindi reads in whole sentences**: long Hindi text is now split at the
  danda (। and ॥), Hindi's full stop, instead of at commas or in the middle
  of a sentence, and an article's short opening Hindi sentence is no longer
  mistaken for page clutter.

## [1.3.2] - 2026-10-18

### Fixed
- **Voices no longer sound shaky at other speeds**: at any speed other than
  1.0×, set by another app's speech rate (for example ReadEra, Moon+ Reader
  or Android's text-to-speech settings) or in the reader, voices took on a
  fast vibrato and sounded robotic. Speeding up and slowing down now keeps
  the voice smooth.

## [1.3.1] - 2026-10-17

### Changed
- **Better with TalkBack and large text**: voice lists and the alias sheet
  say which choice is selected, engine speed meters are read out ("Speed:
  Fast"), and decorative marks are no longer read aloud. Alias chips and
  the theme mode chips wrap instead of being cut off at large font sizes,
  and the reader's background swatches are easier to tap.
- **Easier-to-read buttons in the dark Midnight and Berry themes**: their
  labels are dark on the light blue and red, where white was hard to read.

### Fixed
- **F-Droid build**: the F-Droid build of 1.3.0 did not match ours, so F-Droid
  could not publish it. The F-Droid APK no longer carries the startup
  profile that made the two builds differ, so F-Droid users get updates
  again.

## [1.3.0] - 2026-10-16

### Added
- **Hear a voice before you pick it**: in the alias editor's voice picker,
  each voice on your phone has a speaker button that speaks a short sample
  in that voice. Tap it again, pick a voice or close the picker to stop it.
  Cloud voices have no sample button, so nothing is sent to a provider
  unless you choose that voice.
- **Setup lets you hear the voices**: the last part of setup no longer asks
  you to fill in an alias form. It lists the voices on your phone, each with
  a speaker button to hear it, with one already picked for you: voices in
  your phone's language first, from the engine the phone check recommends.
  Tap "Use" and that voice becomes your first alias, named after it. Speed
  and effects are in Aliases whenever you want them.

### Changed
- **A more polished look**: cards, sheets and menus sit on soft layered
  surfaces with gentle shadows, and lists, sheets and selections move
  smoothly. Dark mode uses warmer stone greys, and secondary text and
  dividers are quieter in both modes. The navigation bar, Speak, Aliases,
  the reader, Settings and setup all get the new look; the Marmalade
  wordmark uses its font's own weight.
- **Shorter engine names**: engines are called by their short names, such
  as "Pocket TTS" and "Kokoro", across the app. An engine's version is
  still listed under About on its details screen.
- **Cleaner sliders**: the speed and expressiveness sliders no longer draw
  a row of dots along the track. They still stop at the same values, with
  a light tap at each one.
- **Voice samples on the Voices screen**: the sample button is a speaker
  that turns into a stop button while it plays, and only one voice plays at
  a time. Cloud voices no longer have one, so a sample never spends your
  provider credit.
- **Effect sliders in light mode**: the effect editor's sliders show their
  track again against the light background.
- **Background step for everyone**: setup asks everyone about running in
  the background, which keeps long reads going with the screen off. It is
  still one tap to skip.

## [1.2.0] - 2026-10-13

### Added
- **Choose who reads an article**: the reader's speed button is now a
  voice and speed button, a speaking-person icon with the speed on a small
  badge whenever it isn't 1×. Tap it to pick another of your aliases or
  another speed in one place; the play controls sit in the middle of the
  bar. The paragraph being read starts again in the new voice, and your
  pick stays for the rest of the article even if you change your primary
  alias. The speed is set with the same slider as in the alias editor. A
  new alias reads at its own speed, unless you chose a speed in the reader:
  that speed stays until you slide back to the alias's own speed.
- **"What's new" after an update**: the first time Marmalade opens after
  an update, a short pop-up lists what changed. It shows once per update
  and never on a fresh install; Settings → About → What's new opens it
  again any time.
- **Expressiveness for Pocket voices**: an alias that uses a Pocket voice
  now has an Expressiveness slider, from Steady to Lively. Steady suits a
  screen reader; Lively suits an audiobook. Each alias keeps its own
  setting, and "Reset to default" goes back to the engine's own.
- **Credits in the voice picker**: voices whose recordings come from a
  named person, donor or corpus now show who, and the licence, under
  their name (for example "Voice donor · CC0" or "Dioco · model by Bryce
  Beattie").
- **Five new Pocket voices**: Mary, Rita, Stephen, Ramone and Jenny join
  the Pocket voices in its next download. Mary, Rita, Stephen and Ramone
  are people who gave their voices to Kyutai's voice-donation project for
  anyone to use; Jenny is the Irish voice of the Jenny (Dioco) recordings.
  They appear once Pocket is updated on the Engines screen.

### Changed
- **Engine quality is a meter**: in setup and on the Engines tab, each
  engine's quality now shows as a four-segment meter like its speed,
  instead of a word. Pocket and Kokoro fill all four segments, Kitten
  three and VITS two. TalkBack reads the grade (for example "Audio
  quality: Excellent").
- **Reader contents open from the side**: the reader's table of contents
  now slides in from the right edge of the screen (the left in
  right-to-left languages) instead of rising from the bottom, opens on the
  section being read, and closes with a tap outside it, a swipe back or
  Back.
- **Pocket finishes installing sooner**: once its download is done, Pocket
  now unpacks several times faster. The download holds the same files,
  packed in a format phones unpack much faster; it grows from 100 MB to
  113 MB. A Pocket you already have needs no update: its files are the
  same.
- **No more stutter when a voice can't keep up**: when an engine can't
  render as fast as it speaks at the chosen speed (a slower phone, or a
  high speed), Marmalade now plays each sentence or clause whole once
  enough of it is ready, so any wait falls between sentences instead of
  breaking one up, including after you speed up the reader mid-article.
  Where the engine keeps up, speech starts as quickly as before. On a Pixel 6a, Kokoro at 2× through system text-to-speech now
  pauses once for about a second before a long sentence, where it used
  to stop several times inside it. The pause allows for one part of the
  sentence rendering about twice as slowly as usual, which happens now and
  then on a phone and would otherwise still break the sentence up.
- **The speed warning says what you'll hear**: it now reads "Your phone may
  have a hard time generating speech at this speed which will show as
  pauses between sentences." It shows at exactly the speeds where Marmalade
  switches to playing whole sentences, and the speed it measured for an
  engine starts over when you read in another language instead of mixing
  the two.
- **Reading in the app keeps up with the screen off**: the reader, the
  Speak screen and shared text now run on the phone's fastest cores while
  they speak, as Marmalade already did for other apps (Android 12 and
  later). On a Pixel 6a with the screen off, Kokoro renders about twice
  as fast and no longer pauses mid-sentence at normal speed, and a long
  read uses less battery per hour of listening, not more.
- **Speed estimates match**: on Android 12 and later, setup and the speed
  warning now expect every engine to render at that faster rate. On phones like the Pixel 6a, the
  speed warning no longer shows for Kokoro at normal speed (it starts just
  above 1.25×), and setup recommends Pocket on somewhat slower phones than
  before.
- **Setup lists the recommended engine first**: the engine marked
  Recommended for your phone now leads the setup list and is ticked to
  install, and the rest keep their usual order.
- **Setup waits for the phone check**: the setup engine list now appears
  once Marmalade has finished checking your phone's speed, already sorted
  with the recommended engine at the top. Until then it shows "Checking
  your phone…". If the check takes longer than 20 seconds, the list shows
  anyway.
- **The two Jenny voices have names that tell them apart**: the VITS
  Marmalade voice is now "Jenny (Dioco, VITS)" and Pocket's is "Jenny
  (Dioco)", whose credit line now reads "Dioco". Aliases using either keep
  working. Voice search names an on-device engine once instead of twice.
- **Pocket voices sound steadier by default**: Pocket now speaks at
  Kyutai's recommended setting, near the Steady end of the slider, with
  fewer slips. To get the livelier sound of earlier versions, move an
  alias's Expressiveness slider all the way to Lively.
- **Pocket uses Kyutai's September 2026 English model**, retrained to
  cope better with text that has no full stop at the end. The new Pocket
  download also sizes the model's working memory to each sentence. On a
  Pixel 6a, Pocket starts speaking sooner (about 0.37 s instead of
  0.55 s), renders about 18% faster and needs about 200 MB less memory at
  its peak. An installed Pocket offers the update on the Engines screen.
- **Pocket voices are ready on first use**: the new Pocket download carries
  every voice already prepared, so the first sentence in a voice you
  haven't used before no longer waits while the phone prepares it. On a
  Pixel 6a it starts speaking in under a second (it took up to several
  seconds) and uses about 0.4 GB of memory instead of 1.1 to 2.6 GB.
- **No pause between paragraphs**: when Marmalade reads an article or a
  long shared text, one paragraph now runs straight into the next instead
  of stopping briefly at every paragraph break.
- **Kitten starts speaking sooner**: the built-in Kitten voices now
  stream each sentence in short slices (half a second, then one, two and
  four seconds) instead of rendering the whole sentence or clause before
  the first sound. The model is Kitten Nano 0.8, cut into two parts so the
  voice part can render piece by piece. Stopping or skipping cuts the
  render in flight short, as before.
- An installed Kitten from an earlier version is replaced from the copy
  inside the app the first time the new version starts (no download),
  and installing Kitten from the Engines screen copies it from the app
  instead of downloading it.
- **Kitten plans each sentence faster, and the app is about 9 MB
  smaller**: Kitten's text-reading step now uses a small model trained
  for Marmalade (3.4 million weights instead of ALBERT's 5.8 million) that
  gives Kitten the same timing and pitch to within a few milliseconds and
  hertz. On a Pixel 6a, planning a sentence takes about 200 ms instead of
  350 ms, the first sound comes about 190 ms sooner (360 ms instead of
  550 ms), Kitten loads in about 0.6 s instead of 1.0 s, and it renders
  about 13% faster. An installed Kitten is updated from the copy inside
  the app the first time the new version starts, once (no download).
- **Kokoro starts speaking much sooner**: Kokoro voices now stream each
  sentence in short slices, the way Kitten does, instead of rendering a
  whole sentence (or a short first piece of it) before any sound. Kokoro
  also reads text with a lighter model: English with a small one trained
  for Marmalade, the other languages with a lighter copy of Kokoro's own.
  On a Pixel 6a, the wait before the first sound fell from about 2.1 s to
  about 0.8 s on everyday English sentences (the slowest sentence from
  about 8.5 s to under 4 s), from about 3.1 s to about 1 s at the start of
  long paragraphs, and from about 3.7 s to about 1.5 s in Spanish. Kokoro
  loads in about 1 s instead of 3 s and needs about 110–230 MB less memory
  at its peak. In exchange it keeps the processor busier while speaking:
  about 35–70% more processor time per minute of speech on that phone.
  This needs the updated Kokoro download (v26): an installed Kokoro keeps
  working as before and shows an update on the Engines screen.
- **Kitten uses much less memory**: about 230 MB less once loaded (on a
  Pixel 8a, about 110 MB instead of 340 MB), at the same speed.
- **Kokoro gives memory back between sentences**: about 180 MB less is
  held while reading.
- **Faster on phones with one extra-fast core**: with the screen off,
  Android often keeps apps off a phone's single fastest core, and the
  engines used to plan a thread for it anyway. They now leave it out
  when the phone has at least two other fast cores. On a Pixel 8a,
  Kitten renders about 25% faster and uses about 40% less processor
  time; Kokoro about 4% faster. The thread setting in Advanced settings
  still overrides this.
- **Kokoro German starts speaking much sooner**: the German voice
  (Thorsten) now streams each sentence in short slices instead of
  rendering it whole before any sound, and reads text with a lighter copy
  of its own text model. On a Pixel 6a with the screen off (Android 12 and
  later, where the app reads on the phone's fastest cores), the wait
  before the first sound fell from about 1.2 s to about 0.4 s, it loads in
  about 0.6 s instead of 1.7 s, and it renders about as fast as before.
  It needs up to about 130 MB less memory at its peak, and about 30% more
  processor time per minute of speech. On Android 11 and older the app
  reads on slower cores; measured on the same phone with that placement,
  the first sound also comes about three times sooner, but it renders only
  just faster than it speaks. This needs the updated Kokoro German
  download (v28): an installed Kokoro German keeps working as before and
  shows an update on the Engines screen.
- Kokoro loads about 0.7 s faster: its Mandarin dictionary now loads the
  first time a text has Chinese characters, not every time.
- **Pocket TTS starts speaking in about half a second and renders faster
  than real time**: it streams each sentence in short
  slices while it renders, decoding on a second thread, and no longer
  copies its whole voice state twice for every 80 ms of audio. On a
  Pixel 6a with the screen off, the wait before the first sound went from
  over a minute to about half a second, and a minute of speech takes
  about 36 s to render instead of about 23 minutes, almost never with a
  gap (one 0.02 s pause in 28 test sentences, none in six long
  paragraphs): it keeps a few seconds of audio ready in case the phone
  briefly slows the decoder down.
  Same model; the audio is unchanged apart from the fix
  below. Pocket also reads its voice sample once per voice instead of
  before every sentence.
- **Setup recommends Pocket on most phones**: the setup guide now marks
  Pocket TTS as Recommended on any phone fast enough to keep up with it at
  normal speed with room to spare (a Pixel 6a is), and the built-in Kitten
  on slower phones. Kokoro is still offered, just not marked Recommended.
  If you install the recommended engine during setup, your first voice
  uses it.
- **Speed bars updated** from a new test of every engine on the same phone
  (a Pixel 6a): Pocket now shows three bars instead of one, Kokoro and
  Kokoro German two instead of three; Kitten and Jenny keep four.

### Removed
- **The Pocket voice Marius**, which sounded much worse than the others.
  Voices and settings that used Marius now use Alba.
- **The Kokoro voice Sky** is no longer offered; voices and settings that
  used Sky now use Heart.

### Fixed
- **Setup in light mode**: a ticked engine card now has a deeper tint than
  the others. It used to turn the same colour as the page, so the engine you
  picked looked like the odd one out.
- **The reader follows a new primary alias**: an article already open in
  the reader kept the voice it started with after you made another alias
  primary, so coming back to it (or sharing the same link again) still
  read in the old voice. Changing the primary alias, or its voice, speed
  or effect, now moves the open article to it: the paragraph being read
  starts again in the new voice.
- **German text is recognised as German**: Marmalade's language detection
  now knows German. A German article in reader mode, or German text shared
  to Marmalade, was taken for English and read by an English voice; it now
  stays on, or moves to, a German voice such as Thorsten. With no German
  voice installed, Kokoro voices that detect each sentence's language now
  read German sentences with German pronunciation rather than English.
- **System text-to-speech hands German to Kokoro German**: an alias on a
  Kitten or Pocket voice with language detection on sent other languages
  only to a Kokoro voice of that language, and Kokoro has no German voice,
  so German stayed on the English voice. German now goes to Thorsten when
  Kokoro German is installed, the same choice reader mode and shares make.
- **System text-to-speech's language switch works for ordinary apps**: an
  alias with language detection on moved other languages to a voice of
  that language only for apps that set neither a voice nor a language.
  Android fills in the default voice for every app that sets a language,
  which is most of them, and that blocked the switch. Only an app that
  picks a different voice on purpose now keeps it.
- **No stray digits reach the voices from espeak**: espeak's dictionaries
  leave a few digits in their pronunciations, for example in the German
  "aneinander-geklebt" or in a lone Cyrillic letter such as "л" in English
  text. Marmalade now removes them for every engine; no voice has a sound
  for them.
- **Hindi voices pause at the end of a sentence**: Kokoro's Hindi voices
  ran from one sentence into the next at a danda (।), Hindi's full stop.
  It now gets the same pause as a full stop.
- **The buttons that open Android's settings land on the right page on
  every supported Android version**: "Open system TTS settings" in setup
  and "Set as system TTS engine" in Settings open Text-to-speech output,
  and "Allow background activity" in setup opens Android's prompt. Each page is checked before it opens, so no
  "Open with" chooser appears; if a phone lacks the page, or it closes at
  once, the next nearest page opens instead, ending at Settings' home. The
  old fallback for Text-to-speech, the language list, is no longer used.
- **Kokoro German no longer pauses after a short first sentence**: when a
  short sentence ("Guten Morgen.") came before a long one, Kokoro German
  rendered the whole long sentence before playing any of it, a pause of
  about 1.2 s on a Pixel 6a (up to 2 s at faster speeds). It now starts a
  long sentence with its first phrase, cut at a comma, whenever the audio
  still playing won't cover the wait. On that phone the pause is gone up
  to 1.5× speed. Kokoro voices still on a download from before v26 get the
  same fix (about 1.9 s down to about 0.4 s at normal speed); the current
  Kokoro download didn't pause there.
- **Voices stay available while an engine update waits**: when a newer
  download of an installed engine was published, its voices disappeared
  from the voice picker, the alias editor and first-run setup until the
  user took the update, and the engine page's Voices button was greyed
  out. The old download still works, so its voices now stay pickable
  until you update. A Pocket still on its previous download lists only the
  voices it has.
- **A Pocket voice missing from an older download no longer fails**: an
  app asking for one of the new Pocket voices (Mary, Rita, Stephen, Ramone
  or Jenny) by name while Pocket is still on its previous download now
  hears the default voice, Alba, instead of an error. Until Pocket is
  updated, other apps are offered only the voices it actually has.
- **Pauses follow the punctuation that ends a phrase**: when a phrase
  held a decimal number, a time or a range ("3.5 dollars, not 4",
  "10:30; then", "Seiten 10–20 lesen."), the Kokoro, Kitten and VITS
  Marmalade voices paused for the mark inside it (the point, colon or
  dash) instead of the comma, semicolon or full stop that ended it, so
  sentence-end pauses were lost. They now pause for the mark that ends the phrase.
- **German "aneinander" no longer has a stray sound**: the word reached
  the German voices with an extra unknown symbol in the middle.
- **A spaced en dash is read as a pause**: an en dash between words
  ("Hallo – wie geht's?"), common in German typing, reached the Kokoro and
  Kitten voices as an unknown symbol mid-sentence. They now pause there as
  they do at an em dash, and VITS Marmalade voices pause at either dash
  too.
- **Speed warning forgets old measurements after an update**: the warning
  for fast speeds now ignores how fast a voice ran on the previous app
  version, or before its engine was updated, so an engine that got faster
  is no longer flagged until it has been used for a while.
- **Spanish no longer reads "á" as "a acentuada"**: older Spanish books
  write the one-letter words "a", "e", "o" and "u" with an accent ("fué á
  la casa ó al campo"). On-device voices now read them as the plain words
  instead of naming the accented letter.
- **German speaks again**: since 1.1.0 the Kokoro German engine, and
  German text read by Kokoro, failed on every phone before saying anything.
- **Pocket no longer muddles part of long sentences**: in long sentences
  (the two test sentences of 7 seconds or more), about two seconds of
  audio starting half a second in was decoded slightly wrong, because
  decoding 64 frames at once overflowed the decoder's memory of recent
  audio. It now decodes in batches small enough to be exact.
- **Pocket reads long web addresses instead of failing**: a single very
  long word, such as a web address of a few hundred characters, could
  stop Pocket partway through the text with a cloned voice made from a
  long recording. Pocket now reads such a word in pieces.
- **Pocket no longer adds a stray period at line ends**: a line ending in
  a comma, a colon or a closing quote got an extra "." added, so
  `a,` then a new line read as "a,. b". Pocket now keeps the line's own
  punctuation and only adds a sentence break where there is none.
- After text you share interrupts an article, the article picks up again
  at the start of the sentence it was in (on-device voices), not partway
  through a word. With a cloud voice it picks up at the start of the
  passage it was reading (up to about 1000 characters, usually the whole
  paragraph).

## [1.1.0] - 2026-10-03

### Added
- **Reader mode**: share a web page to Marmalade and it extracts the
  article and reads it aloud — paragraph-by-paragraph playback with the
  current block highlighted and auto-scrolled, a player notification
  with transport controls that reopens the article at the position you
  left, a table of contents, a typography sheet (font size stepper,
  line width), a reading-speed sheet that starts at your alias's speed
  and changes speed live without restarting the paragraph, tap-a-paragraph
  to play from it, and junk-block filtering (site menus, related-article
  lists, infoboxes and citation markers are skipped) with an honest
  "short extraction" banner when a page resists extraction. The next
  block synthesizes while the current one plays, so paragraph gaps are
  short. Article extraction runs entirely on-device (Readability4J); the
  page is fetched once, and nothing about your reading is sent anywhere.
- **German, for real**: a native German voice engine (**Kokoro German
  v1.0**, downloadable). It speaks with Thorsten Müller's voice — the
  Thorsten-Voice dataset was recorded and donated by him specifically
  for open TTS — fine-tuned on the Kokoro architecture, quantized to
  int8 with the pitch/prosody predictors deliberately kept at full
  precision after they proved intolerant to quantization. Word-error
  rate matches the full-precision reference exactly, and two native
  speakers rated the candidate 4.5/5 and daily-usable (issue #1 — thank
  you!). Comes with an on-device German pronunciation pipeline: number,
  date and unit normalization, an override lexicon, and a German G2P
  that matches the reference phonemization.
- **German and Bulgarian partial support** on the existing Kokoro
  voices: pick the language explicitly and any Kokoro voice will speak
  de or bg — with a noticeable accent, which community reviewers called
  charming and tolerable. Honest tiering: automatic language detection
  and the system-TTS language claims still only advertise the languages
  with native support, so nothing routes to the accented tier unless
  you ask for it.
- **A new engine: VITS Marmalade** — Marmalade's own on-device runtime
  for Piper-class VITS voices, organized as per-language downloadable
  voice packs. It debuts with one English voice, **Jenny (Dioco)**: a
  ~58 MB pack of a voice Jenny recorded herself expressly for TTS,
  independently trained from scratch with permissively licensed weights
  and data (her attribution ships in the pack and the in-app licenses
  screen). It's light enough to run well on very slow devices. More
  language packs are already built and will ship as they pass community
  review.
- **Language sample pages** for community review — recorded samples of
  candidate voices (German, Ukrainian, Russian, Icelandic, Swedish,
  Kazakh, Norwegian) are published on the project's GitHub Pages so
  native speakers can grade them before anything ships. Ukrainian is
  currently held after exactly that feedback: the reviewed voices
  aren't daily-usable yet, so they stay out until they are.
- **Language-aware voices**: when your default voice doesn't speak an
  article's or a shared text's language, Marmalade reads it with an
  installed on-device voice that does. Cloud voices are never picked
  automatically.
- The **Sponsors** row in Settings now ships in both flavors.

### Changed
- **Speed is now a true time-stretch on every on-device engine.** Kokoro,
  Kitten and Pocket used to feed your speed into the model (Pocket dropped
  it entirely — issue #7); model-side speed audibly degraded articulation
  and silently saturated around 1.85–2.2× no matter what you asked for.
  Every on-device engine now renders at its natural pace and the requested
  speed is applied as a tempo stage afterwards, so 2.0× actually plays in
  half the time — on the Speak screen, the reader, and system TTS
  (screen readers included). Sentence pauses scale with the speed too.
  Cloud voices still pass your speed to the provider, which renders it.
- **Speed-aware buffering**: at high speeds the app now pre-buffers
  enough synthesized audio to avoid mid-text stalls where playback
  outruns synthesis. The alias editor and the reader's speed sheet warn
  you when a speed is likely to stutter, based on how fast the chosen
  engine actually runs on your device.
- **Kokoro starts speaking sooner.** Text is chunked by how much work
  each piece is for the model rather than by character count; long
  Chinese and Japanese sentences are cut at clause marks and played as
  each piece is ready; and when nothing is playing, a long first sentence
  starts with a short opening piece, cut at its first comma or similar
  break. A long Chinese run-on sentence that took about 30 seconds to
  start on a Pixel 8a now starts in about two.
- The themed (single-colour) app icon on Android 13+ is now a plain jar
  silhouette with the sound waves, without the cut-out face.
- The Google Play build no longer shows developer options, or voices of
  engines that are only offered on F-Droid.

### Fixed
- **Offline / hardened-Android crashes** (issue #12): opening the Cloud
  voices screen with the Network permission revoked (GrapheneOS's
  per-app toggle) crashed the app — network errors now surface
  gracefully. Sharing a URL while offline no longer crashes the reader,
  and a share speak request that the system refuses to start now
  shows an error toast instead of failing silently.
- **Word endings are no longer swallowed at high speeds** (issue #8):
  Kokoro and Kitten trimmed a fixed slice off each chunk's tail, which
  ate final consonants at 2×. The trim is now amplitude-aware — it only
  removes actual silence.
- **Text preprocessing** got a sweep of pronunciation fixes:
  thousands-separated numbers and currency amounts verbalize correctly
  ("$1,234.56" no longer reads digit-by-digit), "¥" amounts say "yen"
  (not "yens"), over-long decimals no longer half-match, scene-break
  symbol lines (asterisks, dinkuses, ■) are treated as breaks instead
  of being read out, superscript footnote markers are dropped, and
  hard-wrapped text (emails, plain-text notes) is re-joined so
  line breaks mid-sentence don't become sentence breaks. Parenthetical
  asides get a natural spoken pause, ~30 words espeak mispronounces
  ("yeah", "gauge", …) are respelled, and common heteronyms ("lead",
  "tear", …) pick the right reading from context — ported from the
  Marmalade TTS CLI so both stay in lockstep.
- Kokoro's espeak-backed languages no longer read stray language-switch
  markers aloud when a foreign word appears mid-sentence.
- The reader recognizes sentence endings before CJK closing brackets.
- The email preprocessing rule was accidentally quadratic — very long
  texts with many @-signs preprocessed slowly. Now linear.
- A cloud voice's offline fallback now actually takes over when the
  provider can't be reached. Before, the fallback was never used and you
  got an error instead of speech.
- Very long sentences with little or no punctuation are no longer cut
  off partway. They're split at a natural break instead.
- **Stop, Next and tapping a paragraph respond quickly**: cancelled
  speech stops rendering at once instead of finishing in the background,
  and Next plays the paragraph that's already prepared.
- A paused read is no longer resumed by another app's notification
  sound, and media-button Stop/Pause work even before the first audio.
  Android 13+ media controls now show a Stop button.
- Speeds above 2× no longer crash the speed stage, and the speed stage
  no longer clips the last few milliseconds of speech.
- **Cloud provider safety**: an updated provider list can no longer move
  a built-in provider's address to a different site. If that ever
  happens the app shows "Update the app to keep using it" instead of
  sending your API key to the new address. A key saved for a provider
  that comes only from the downloaded list stays tied to the address it
  was saved for.

### Removed
- The Quick Settings "Speak clipboard" tile, for now. Share text to
  Marmalade or use the system "read selection" action instead.

## [1.0.0] - 2026-08-10

### Removed
- **Kitten Mini (v0.8)** is gone. Upstream KittenML only ever published
  the mini and micro 0.8 models as dynamic-int8 ONNX exports, and on
  listening they are audibly *worse* than the fp32 Kitten Nano they were
  advertised as a step up from — and slower to synthesize as well. Kitten
  Nano is now the only Kitten engine. If you had Mini installed, the app
  moves your aliases (and per-app routes) onto the matching Nano voice —
  the same eight speakers, same names — and reclaims the ~100 MB bundle
  on next launch.

### Added
- **Cloud voices (hosted TTS)**: a new bundle-less engine that
  synthesizes over any OpenAI-compatible `/audio/speech` provider with
  true streaming (first audio ≈ one network round trip). Providers are
  described as *data* (`cloud-providers.json`, bundled + remotely
  updatable from the engines repo) and Venice's model/voice lineup is
  discovered live from its `/models?type=tts` endpoint — so new
  providers, models, or voices arrive without an app update. Configure
  per-provider API keys from the Engines tab's "Cloud voices" card
  (Configure where local engines have Install); voices appear in the
  picker/aliases only once a key is set. Ships with Venice (Kokoro) and
  OpenAI (GPT-4o mini TTS, TTS-1) descriptors.
- Per-engine voice browsing: each engine's detail page has a "Browse
  voices" entry opening the picker scoped to that engine.

### Fixed
- **Primary alias routing**: TTS clients auto-fill the request's voice
  from the engine's advertised default, and that echo outranked the
  primary alias — a Kitten primary could never fire ("keeps speaking
  Bella Kokoro"). The advertised default now follows the primary alias,
  and an auto-filled echo of it no longer beats the alias; deliberate
  per-app voice picks still win.
- Editing the alias that's active on the Speak screen now also follows a
  **voice** change immediately (speed/effect/language already re-synced);
  previously the new voice only applied after deselecting and re-tapping
  the chip.

### Changed
- Bottom navigation is now **Speak / Aliases / Effects / Engines /
  Settings**: Engines is a tab again (with its old wrench icon back;
  Effects moved to a star), and Voices left the bottom bar — it's a
  detail screen reached from Speak or from an engine's detail page.
  Settings lost its "Manage engines" row accordingly.
- **Per-app voice routing moved onto the Aliases tab.** It used to be a
  separate screen buried in Settings → "Per-app voices"; now each alias
  card shows which apps speak with it ("Used by 2 apps"), and tapping
  that strip opens an app picker scoped to that alias. The primary
  alias's card finally states the fallback rule out loud — "…and
  everything you haven't routed". Ticking an app already routed
  elsewhere shows its current alias, so re-routing is deliberate.
  Existing routes are untouched (no schema change, no migration).
- **Alias rows no longer carry edit and delete icons.** Tapping the card
  opens the editor, which is now a bottom sheet, and Delete lives inside
  it behind the same confirmation as before.
- Brand typography per marmalade-design-scheme-v0: Manrope across the
  app, and the lowercase "marmalade tts" wordmark (Fredoka 600, orange
  in light / cream in dark) on the Speak screen top bar. Both fonts are
  bundled (OFL-1.1; see `LICENSES/fonts.md` and the in-app licenses
  screen).

## [1.0.0-beta.1] — 2026-06-07

First public beta — feature-complete and production-ready; held in beta
until validated across a range of devices, then promoted to `1.0.0`.
(Continues the `0.3.0-alpha` line; see below for earlier history.)

### Changed
- Engine names dropped the internal "Direct" label — production engines are
  now **Kokoro (v1.0)**, **Kitten Nano (v0.8)**, **Kitten Mini (v0.8)**, and
  **Pocket TTS**.
- **sherpa-onnx removed entirely** (engines, catalogs, and the vendored
  AAR). Every engine now runs directly on ONNX Runtime. DB migration
  v7→v8 cleans up the old engines' voice rows; the sherpa engines live
  on in the `experimental/executorch` branch.
- **espeak-ng is now compiled from source into the APK** (pinned
  submodule, tag 1.52.0) instead of being downloaded inside engine
  bundles — Google Play forbids runtime download of executable code.
  The distributed APK is therefore a GPL-3.0-or-later combined work;
  all Marmalade source files remain MIT. Engine bundles now carry only
  models and pronunciation data.
- In-app **Open-source licenses** screen (Settings → About) with
  per-component license texts bundled in the APK.
- The alias editor only offers engines you've actually installed.

### Added
- Onboarding asks for notification permission (Android 13+) so the
  speaking / keep-warm notices can appear.
- Debug benchmark screen shows a live device-load readout (RAM / zram / CPU
  / thermal) and releases each engine between runs for honest numbers.

### Fixed
- Changing the ONNX thread count now takes effect on the next Speak
  (previously a silent no-op until the app was force-stopped).
- Cleared a stale "Tap to install" banner that lingered after switching to
  an installed engine.
- Pocket TTS: faster autoregressive decode (LSD Euler steps 4 → 1) at no
  quality cost.

## [0.3.0-alpha.12] — 2026-06-04

### Fixed
- **Pocket TTS chunk-start "bitcrush" glitch** — the long-running intermittent
  distorted-word artifact. Root cause (confirmed by a frozen-latent decode sweep):
  the exported `mimi_decoder` ONNX graph is **not window-size-invariant** — every
  batched `run()` corrupts its own leading edge, and the corruption *length* scales
  with batch size + how cold the codec state is. The P-AI graduated decode window
  merely *relocated* the seam (frame 0 → frame 8). Replaced it with **P-AL segmented
  overlap-discard**: walk the chunk in capped 64-frame batches; for each, per-frame-
  decode the first 8 frames (clean + warms the state), snapshot/restore mimi state at
  the batch boundary, and keep only the batch *interior* — so no emitted frame ever
  sits on a corrupt leading edge. Per-frame decode quality at near-batched speed.
  (`PocketEngine.runMimiDecoder`, `PocketStateManager.snapshot/restore`.)

### Added
- **Pocket preprocessing:** newline runs now become sentence breaks (P-AM), so multi-
  line / paragraph input reads with sentence pauses instead of running on. `ex.` added
  to the abbreviation rule (→ "for example"), alongside existing i.e./e.g./etc. (both
  Android + CLI).
- **Dev-only decode-strategy experiment harness** (`DECODE_EXPERIMENT`, off by default):
  dumps latents + re-decodes them under multiple window policies for diagnosing/tuning
  the mimi decoder. Root-caused the glitch above; kept for ongoing optimization work.

## [0.1.3] — 2026-05-22

### Changed
- Engine install path is now single-archive download + extraction.
  v0.1.0/0.1.1 fetched 358 files individually from Hugging Face;
  v0.1.2 mirrored those 358 to a dedicated GitHub Releases CDN;
  v0.1.3 collapses that to one tar.bz2 download (the upstream Sherpa-
  ONNX tarball, byte-identical). 358 HTTPS round-trips → 1.
  KittenEspeakDataManifest.kt + the per-file sha256 list are gone.
- New dep: org.apache.commons:commons-compress for tar.bz2 extraction.

## [0.1.0] — 2026-05-21

First shipped build (debug-signed APK on GitHub Releases). System TTS
engine provider with the Kitten engine via opt-in install, emoji prosody
layer, share-sheet target, Quick Settings tile, voice aliases, three
effect presets (cave / robot / telephone), and a foreground media
playback service for long-form text.

### Added — Engine installer + onboarding flow

- `EngineCatalog` (`app/src/main/java/app/marmalade/tts/install/EngineCatalog.kt`)
  with the static `EngineDescriptor` / `EngineFile` data model. Kitten is
  the only entry in v0.1; the catalog points each file at its HuggingFace
  mirror URL (`huggingface.co/csukuangfj/...`) and lists per-file SHA-256
  for integrity verification.
- `KittenEspeakDataManifest` — file-by-file manifest of the espeak-ng
  phonemizer data (~355 entries). Auto-generated by
  `scripts/generate-kitten-manifest.py` from a locally extracted bundle.
  v0.1 lands with a seed list of the most-critical entries; the full
  enumeration is tracked in STUBS.md.
- `EngineInstaller` — streams each catalog file via `HttpURLConnection`
  into `${filesDir}/engines/<name>.tmp/`, verifies SHA-256 incrementally,
  atomically renames into `${filesDir}/engines/<name>/`. Exposes
  per-engine `Flow<InstallState>` for the UI to render progress.
  Uninstall calls `KittenEngine.release()` first to drop the JNI handle
  before deleting model files.
- `OnboardingScreen` + `OnboardingViewModel` — three-step first-launch
  wizard (Welcome → Engine pick → Install progress). Engines are
  pre-checked when `EngineDescriptor.isRecommended` is true. Mascot
  animations across steps. "Continue" on the final step flips
  `SettingsRepository.onboarded` to true and routes to the Speak screen.
- `EnginesScreen` + `EnginesViewModel` — Settings → Engines surface
  reachable via a dropdown on the Speak screen's top bar. Per-row
  install/uninstall/retry affordances with confirmation dialogs.
  Install dialog surfaces the GPL-3.0 disclosure summary.
- `AppRoot` now gates on `SettingsRepository.onboarded` before routing
  to the regular screen graph. Adds an `Engines` route alongside
  `Speak` / `Voices`.
- `SettingsRepository` gains an `onboarded` boolean key — the documented
  trigger for the first-launch wizard.
- "Model not installed yet" copy in `SpeakScreen` and `VoicePickerScreen`
  updated to point users at Settings → Engines.
- Manifest permission `INTERNET` added with an inline comment scoping
  it to engine downloads. New `PRIVACY.md` documents the policy;
  `SECURITY.md` cross-references it.
- Unit tests:
  - `EngineCatalogTest` — pins the catalog schema (sizes sum, HTTPS
    URLs, GPL disclosure present, empty-files-list rejected).
  - `EngineInstallerTest` — spins up a loopback HTTP server, exercises
    the happy path, SHA mismatch, mid-stream HTTP error, idempotent
    reinstall, uninstall, and the three verify() outcomes.
  - `OnboardingViewModelTest` — step transitions, selection toggling,
    install-error recovery, the `finish()` → `onboarded=true` write.

### Added — Kitten TTS engine + system-TTS wiring

- `KittenEngine` (`app/src/main/java/app/marmalade/tts/engine/KittenEngine.kt`)
  wrapping Sherpa-ONNX's `OfflineTts` in Kitten mode. 24 kHz mono PCM
  output, 8 speakers, lazy load, idempotent `ensureModelLoaded()`,
  release-able. Verified against the vendored AAR's
  `OfflineTtsKittenModelConfig` / `OfflineTtsModelConfig` API surface.
- `MarmaladeTtsService` now feeds real PCM through the
  `SynthesisCallback` instead of returning silence. `@AndroidEntryPoint`,
  Hilt-injected `KittenEngine` + `VoiceMetaDao`. Voice negotiation,
  `LANG_COUNTRY_AVAILABLE` reporting for en-US, chunked
  `audioAvailable` writes capped at `callback.maxBufferSize`.
- `VoiceMeta` Room entity expanded: `id`, `engine`, `displayName`,
  `languageCode`, `sampleRate`, `gender`, `isInstalled`. DB bumped to
  v2 with `fallbackToDestructiveMigration()` (v1 had no real data).
- `VoiceMetaDao` with Flow-returning `getAll()` / `getByEngine()` and
  suspend `findById()` / `upsert()` / `upsertAll()`.
- `KittenVoiceCatalog` seeds the 8 Kitten voices (Bella, Jasper, Luna,
  Bruno, Rosie, Hugo, Kiki, Leo, all en-US, 24 kHz) into the DB on
  first launch via a `RoomDatabase.Callback.onCreate` hook.
- `AppModule` updated to provide `VoiceMetaDao` and wire the seed
  callback through a `Provider<VoiceMetaDao>` to break the cyclic dep.
- Unit tests for `KittenVoiceCatalog` (8 voices, IDs, default,
  install-state) and PCM16 little-endian encoding (endianness bugs
  here = screech instead of speech).
- Removed `engine/SherpaOnnxStub.kt` — `KittenEngine` is now the real
  compile-time proof that the AAR is wired correctly.

### Architecture — engine-as-plugin (engines install on user opt-in)

Engine model files are **not bundled in the APK**. They're downloaded
at runtime by an `EngineInstaller` (separate component, scaffolded
next) into `${filesDir}/engines/<engine>/` when the user opts in via
onboarding or Settings → Engines. The default install ships only the
CLI wrapper code + UI + Sherpa-ONNX AAR — no neural models, no
phonemizer data. This matches the CLI's `marmalade-tts install
<engine>` pattern.

Reasons for the pivot:
- **APK size.** Bundling Kitten alone would push the APK from ~115 MB
  to ~140 MB. The CLI's full engine stack would not fit at all.
- **License hygiene.** The Sherpa-ONNX AAR statically links espeak-ng
  (GPL-3.0). Shipping it by default forces a GPL-licensed APK. With
  opt-in install, the GPL'd component only lands on devices whose
  users have explicitly accepted it, and the default install posture
  stays MIT-clean. Trade-off: users must accept a one-line disclosure
  during engine install that the engine includes GPL components.
- **User choice.** Mobile users have widely varying tolerance for app
  sizes and network use. Letting them choose which engines to install
  is friendlier than forcing a 140 MB+ download for everyone.

`KittenEngine.ensureModelLoaded()` throws `EngineNotInstalledException`
(a typed subclass of `UnsupportedOperationException`) when the user
hasn't installed the engine. The UI catches this and routes to the
install flow.

The Kitten model bundle (`kitten-nano-en-v0_1-fp16` from
[sherpa-onnx tts-models](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kitten-nano-en-v0_1-fp16.tar.bz2),
Apache 2.0, NOTICE at `LICENSES/kitten-tts.md`) is the first engine to
be wired through the installer.

### Added — Initial Android project scaffold
- Gradle 8.11.1 wrapper + AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01
- Single `app` module, namespace `app.marmalade.tts`, minSdk 28, targetSdk 35
- `MarmaladeTtsApplication` with `@HiltAndroidApp`; `MainActivity` with `@AndroidEntryPoint`
- Placeholder Compose screen (mascot + app name + version)
- Marmalade-orange Material 3 theme with Material You dynamic colors (matches marmalade-android)
- Hilt DI wired; `AppModule` provides Room DB and DataStore
- `MarmaladeDb` Room database v1 (no entities — to be added with first migration)
- `marmalade_settings` Preferences DataStore
- `MarmaladeSynthService` foreground service skeleton (`foregroundServiceType="mediaPlayback"`)
- `MarmaladeTtsService` system TTS engine skeleton (registered, produces silence)
- `xml/tts_engine.xml` TTS engine descriptor
- Sherpa-ONNX AAR vendored in `app/libs/`, `OfflineTtsConfig` import verified
- 9 mascot vector drawables installed in `res/drawable/`
- Adaptive launcher icon (foreground: `mascot_happy`, background: marmalade orange)
- Manifest permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
  `POST_NOTIFICATIONS`, `RECORD_AUDIO` (declared; no INTERNET — on-device v0.1)
- Unit test scaffold (`ApplicationTest` passes; `androidTest/` directory present)
