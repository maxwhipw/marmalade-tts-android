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
  types?** → **Yes**

**This answer changed on 2026-07-27, from No.** Two earlier rationales
for **No** were both wrong, and the second was wrong in a subtle way
worth recording so nobody re-derives it.

**Wrong rationale 1 — "synthesized text never leaves the device."** True
until the cloud API engine landed (2026-07-24). Since then, a configured
provider plus a cloud voice means the text to be spoken is transmitted to
Venice or OpenAI.

**Wrong rationale 2 — "the prominent-disclosure exemption covers it."**
The exemption is real but it is a **sharing** exemption, and the question
asks "collect **or** share". Google defines the two separately
([answer/10787469](https://support.google.com/googleplay/android-developer/answer/10787469)):

> **"Collect"** means transmitting data from your app off a user's device.

> **"Sharing"** refers to transferring user data collected from your app
> to a third party.

The only exemptions from disclosing **collection** are on-device-only
processing and end-to-end encryption. Neither applies: the text does
leave the device, and TLS is not end-to-end encryption in Google's sense
— the provider can read it. So the collect limb is met regardless of how
strong the disclosure is.

**Ephemeral processing does not rescue a No either**, which is the trap.
Google's wording is explicit that it is a *display* suppression, not a
declaration exemption:

> User data transmitted off device that is processed ephemerally **needs
> to be included in your form response**, but if it meets the standard
> below, it will not be disclosed in your app's Data safety section on
> Google Play.

> Processing data "ephemerally" means accessing and using it while the
> data is only stored in memory and retained for no longer than necessary
> to service the specific request in real-time.

So: enter it in the form, mark it ephemeral, and Google decides what the
public listing shows.

### What to declare

| Field | Answer |
|---|---|
| Data type | **App activity → Other user-generated content** |
| Collected | **Yes** — it is transmitted off device |
| Shared | **Yes** — it goes to a third party (see note below) |
| Processed ephemerally | **Yes** — held in memory for the request only; Marmalade stores no text and has no server |
| Purpose | **App functionality** only |
| Required or optional | **Optional** — cloud voices are opt-in; on-device voices send nothing |

Marmalade *could* lean on the sharing exemption and leave "Shared"
unticked — the consent gate (2026-07-27) makes the prominent-disclosure
limb genuinely strong. **Declare it anyway.** The exemption turns on
whether the user "reasonably expects" the transfer, and the system-TTS
path is exactly where that expectation is weakest: with a cloud voice as
the primary alias, text from *other* apps — ebooks, messages, anything an
accessibility service reads — reaches the provider without any action
inside Marmalade. Ticking the box costs a line on the store listing and
removes the whole argument. For an app whose pitch is privacy, saying so
plainly is on-brand.

The user's **API key** is not declared separately: it is the user's own
credential being sent to the service that issued it, as authentication.

### Why only one box, when a user could type anything

A TTS input field can receive a phone number, a symptom, a bank balance
— so it is tempting to tick Contacts, Health, and Financial info too.
**Don't.** The form's standard is data *"actually collected and/or
shared"*, and "Other user-generated content" is Google's designated
catch-all for exactly this shape: *"Any other user-generated content not
listed here… For example, user bios, notes, or open-ended responses."*

The objective line is what the app can obtain **by any means other than
someone typing it**, and the manifest settles it. Marmalade holds
`INTERNET`, `POST_NOTIFICATIONS`, three foreground-service permissions
and battery-optimisation — no `READ_CONTACTS`, no location, no health,
no storage, and no `QUERY_ALL_PACKAGES`. There is no file or document
import; the only inbound intents are `SEND` and `PROCESS_TEXT`, both
`text/plain`. So the app cannot collect any of those categories.

Ticking them would be **inaccurate**, which the form requires you not to
be: the listing would tell users Marmalade reads their contacts. It also
contradicts PRIVACY.md. Tick a second box only when a feature
specifically ingests that category — a document importer would earn
"Files and docs"; reading notifications would earn "Messages".

### Security practices (these appear once collection is Yes)

- **Is all user data encrypted in transit?** → **Yes.** Provider calls
  and engine downloads are HTTPS.
- **Can users request that data be deleted?** → **No.** Marmalade holds
  nothing to delete — no server, no account. Deletion of anything a
  provider retains has to be requested from that provider, which
  PRIVACY.md states.
- **Independent security review badge** → skip. Requires a paid MASA
  audit by an authorized lab.

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
