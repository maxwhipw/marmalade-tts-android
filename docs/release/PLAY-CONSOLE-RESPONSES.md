# Play Console — prepared answers

Copy-paste answers for every form the Play Console asks for, in the
order the console presents them. Audit before pasting — these are
prepared answers, not gospel. Companion to
[PLAY-RELEASE-PLAN.md](PLAY-RELEASE-PLAN.md).

## The 10-step path

1. Create the developer account ($25 one-time):
   https://play.google.com/console/signup — personal account is fine.
   **Note:** new personal accounts must run a closed test with 12+
   testers for 14 days before production access. If that applies, plan
   the beta around it (friends/family/Reddit testers all count).
2. Create app → name **Marmalade TTS**, default language en-US, type
   **App**, **Free**.
3. Fill **App content** (Policy → App content) using the sections below
   — privacy policy, data safety, FGS declarations, content rating,
   target audience, ads.
4. Set up the **store listing** (Grow → Store presence → Main store
   listing): copy title/short/full description from
   `fastlane/metadata/android/en-US/`, icon from
   `fastlane/metadata/android/en-US/images/icon.png`, feature graphic
   1024×500 (TODO — see handoff), 2+ phone screenshots (TODO — capture
   on the Pixel once the release build is smoke-tested).
5. Generate the upload keystore + `keystore.properties`
   (see [SIGNING.md](SIGNING.md)).
6. `./gradlew :app:bundleRelease` → AAB at
   `app/build/outputs/bundle/release/app-release.aab`.
7. Release → Testing → **Closed testing** → create track, upload the
   AAB, enroll in **Play App Signing** when prompted.
8. Run the **pre-launch report** (automatic on upload) — fix anything
   it flags (it exercises Google's device farm, catches crashes and
   16 KB page-size issues).
9. After the closed test window: promote to **Production** (or Open
   testing first).
10. Calendar: **targetSdk 36 required by ~Aug 31, 2026** for updates.

## Privacy policy URL

A GitHub link is acceptable — Play only requires a publicly accessible,
non-editable-by-users URL:

```
https://github.com/maxwhipw/marmalade-tts-android/blob/main/PRIVACY.md
```

(If a reviewer ever objects to the GitHub chrome, the raw URL or a
GitHub Pages render of the same file are drop-in replacements.)

## Data safety form

- **Does your app collect or share any of the required user data
  types?** → **No**

⚠️ **Re-check this before submitting.** The old rationale said
"synthesized text never leaves the device". That stopped being true when
the cloud API engine landed (2026-07-24): if the user configures a
provider and picks one of its voices, the text to be spoken is sent to
Venice or OpenAI.

The **No** answer is still defensible, on Play's exemption for data
transferred "based on a specific user-initiated action, where the user
reasonably expects the data to be shared, **or** based on a prominent
in-app disclosure and consent"
(support.google.com/googleplay/android-developer/answer/10787469).
Marmalade satisfies the disclosure limb: the Cloud voices screen states
"your text is sent over the network per request" before any key is
entered, and the key field states the key is "stored only on this device
and sent only to <provider>". Nothing reaches a provider unless the user
has entered their own API key and selected a cloud voice.

The uncomfortable edge is the **system-TTS path**: a cloud voice set as
the primary alias means text from *other* apps flows to the provider
without a per-utterance user action. That is disclosed in PRIVACY.md and
cloud voices are labelled in the picker, but if a reviewer pushes back,
the honest fallback is to declare it rather than argue — the data type
would be "Other in-app text", collected and shared, for App
functionality.

Everything else is unambiguous: no analytics, no accounts, no ads, no
crash reporting. The engine-file download from GitHub transmits no user
data.

## Foreground service declarations

(App content → Foreground service permissions. Each needs a use-case
description and a short demo video — screen-record the flows named
below on the Pixel; 30-60 s is plenty.)

### mediaPlayback — MarmaladeSynthService

> Marmalade TTS is an offline text-to-speech engine. When the user
> starts long-form speech from the app's Speak screen (e.g. reading an
> article aloud), playback runs in a mediaPlayback foreground service so
> audio continues with the screen off, with lock-screen/media-session
> transport controls (play/pause/stop). The service starts only on a
> user-initiated speak action and stops when playback ends or the user
> dismisses the notification.

Demo video: open app → paste a paragraph → Speak → screen off → audio
continues, notification + lock-screen controls visible → stop.

### specialUse — MarmaladeKeepaliveService

> Subtype: keeping the neural TTS model loaded in memory. Marmalade runs
> neural text-to-speech entirely on-device; loading a model takes
> several seconds. When the user explicitly enables Settings →
> Performance → "Keep engine loaded", this service holds the loaded
> model so system-wide TTS requests (screen readers, reading apps) speak
> instantly instead of paying a multi-second cold start. It is strictly
> opt-in, shows a persistent notification, and no standard FGS type
> (mediaPlayback applies only during actual playback) covers
> model-residency between requests.

Demo video: Settings → Performance → toggle "Keep loaded" → notification
appears → trigger TTS from another app → instant speech → toggle off →
notification gone.

**Fallback if rejected:** ship the Play build with the keepalive toggle
removed (the feature is a nice-to-have; cold-start TTS still works).
Decide only if it actually bounces.

## REQUEST_IGNORE_BATTERY_OPTIMIZATIONS

Review-notes text (the console asks for permission justifications in
the App content → Sensitive permissions section, or in review replies):

> Marmalade TTS is a system text-to-speech engine. Its core function —
> uninterrupted speech for long-form reading and for accessibility
> consumers (screen readers) — breaks if the OS freezes the app
> mid-utterance. The exemption prompt is shown only after an explicit
> user action in Settings and is fully optional; all other
> functionality works without it.

## Content rating (IARC questionnaire)

- Category: **Utility / Productivity / Communication or other**
- Violence / sexuality / language / controlled substances / gambling:
  **No** to all.
- User interaction / user-generated content: **No** (the app speaks
  text the user provides locally; nothing is shared or posted).
- Shares location: **No**. Personal info: **No**. Digital purchases:
  **No**.
- Expected result: **Everyone / PEGI 3**.

## Target audience & ads

- Target age: **18 and over** (simplest; avoids the Families policy
  track entirely — the app is a utility, not child-directed).
- Contains ads: **No**.

## App access

- **All functionality is available without special access** (no login,
  no credentials, no geo-restrictions). If the form insists on notes for
  the reviewer: "No account. To test speech output: install an engine
  from the in-app onboarding (downloads voice data ~60-360 MB), then use
  the Speak tab."

## News, government, financial, health declarations

**No** to all (not a news app, no financial features, no health
features, not a government app).
