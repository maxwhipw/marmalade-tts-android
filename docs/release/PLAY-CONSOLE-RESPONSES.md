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
   listing): everything is in `fastlane/metadata/android/en-US/` —
   title/short/full description (flatten the F-Droid HTML to plain
   "• " lines, keep `<b>/<i>`), `images/icon.png`,
   `images/featureGraphic.png`, and the 7 approved screenshots in
   `images/phoneScreenshots/`.
5. Signing — DONE: upload keystore exists and CI signs on tag
   (see [SIGNING.md](SIGNING.md) / [CI-SIGNING.md](CI-SIGNING.md)).
6. AAB — DONE: download `marmalade-tts-1.0.0-play.aab` from the
   v1.0.0 GitHub release (verify against SHA256SUMS.txt); don't
   rebuild locally.
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

The exemptions from disclosing **collection** are exactly three:
on-device-only processing, end-to-end encryption, and ephemeral
processing. None applies. The text leaves the device; TLS is not
end-to-end encryption in Google's sense (the provider must be able to
read it, and Play asks about in-transit encryption *separately*, which
shows the two are different concepts); and ephemeral is addressed below.
So the collect limb is met regardless of how strong the disclosure is.

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

So ephemeral is a *listing* suppression, not a way out of declaring —
and in any case it is answered No here, for the reasons below.

### What to declare

**Two data types, not one.** Reviewed 2026-07-27 by a three-model panel
reading the full Console page. Unanimous except where marked.

| | Text to be spoken | The user's API key |
|---|---|---|
| Data type | App activity → **Other user-generated content** | Personal info → **User IDs** |
| Collected | **Yes** | **Yes** |
| Shared | **No** — exemption relied on | **No** — exemption relied on |
| Processed ephemerally | **No** | **No** |
| Purpose | **App functionality** | **App functionality** |
| Required or optional | **Optional** | **Optional** |

Nothing else. No Location, Financial info, Messages, Contacts, Device
IDs, or Crash logs/Diagnostics — there is no crash reporter.

### The API key IS collected — the clause that settles it

An earlier draft of this document said the key needed no declaration, on
the reasoning that storing it on-device isn't collection and it is only
ever sent to the service that issued it. **Both halves are wrong**, and
the second is wrong because of one clause all three reviewers found:

> "Collect" means transmitting data from your app off a user's device.
> … **Libraries and SDKs:** This includes user data transmitted off
> device from your app by libraries and/or SDKs used in your app,
> **irrespective of whether data is transmitted to you or a third-party
> server.**

The destination is explicitly irrelevant. "It goes back to its own
issuer" and "the developer has no server and never sees it" are both
answered by that sentence. The key rides in an `Authorization: Bearer`
header on every cloud request, so it is transmitted off device, so it is
collected.

Storing it on-device remains *not* collection — the on-device exemption
is conditioned on data "not sent off device", and the key is sent. The
persistence is a red herring in both directions; the header is what
decides it.

**Data type: Personal info → User IDs** — *"Identifiers that relate to an
identifiable person. For example, an account ID, account number, or
account name."* The key is issued per-account and the provider resolves
it to a specific identifiable customer, which is what "relates to an
identifiable person" asks.

> **Panel split, recorded honestly.** Two of three reviewers said User
> IDs. The third argued for ticking **nothing**: Play has no
> credential/token data type at all (verified by grepping the page —
> zero occurrences of "password", "credential", or "token"), and the
> User IDs examples describe identifiers that name a person *to the
> developer*, which this never does. That is a real argument. It loses
> on two grounds: the definition says "relate to", not "identify to
> you"; and under-declaring is what draws enforcement while
> over-declaring is not a violation. If a reviewer ever objects to the
> User IDs row, the fallback is to remove it, not to defend it.

### Sharing: not declared — exemption relied on (Max, 2026-07-27)

**Source, with anchors** —
[#sharing](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en#sharing)
holds the definition and all four exemptions;
[#collection](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en#collection)
holds the collect definition. The exemption's second limb points at the
[User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
for what a "prominent disclosure" must look like.

This **is** sharing. Verbatim:

> "Sharing" refers to transferring user data collected from your app to a
> third party.

> "Third party" means any organization other than the first party or its
> service providers.

**Venice/OpenAI are not service providers**, and the exemption text says
why twice over:

> **Service providers.** Transferring user data to a "service provider"
> that processes it **on behalf of the developer**.
> "Service provider" means an entity that processes user data **on behalf
> of the developer and based on the developer's instructions**.

They process on behalf of the **user** — the user's account, the user's
contract, the user's money, no instructions from us. Declaring them as
service providers would be a false statement.

That leaves one bullet standing between "this is sharing" and "you need
not say so":

> **User-initiated action or prominent disclosure and user consent.**
> Transferring user data to a third party based on a specific
> user-initiated action, where the user reasonably expects the data to be
> shared, or based on a prominent in-app disclosure and consent that meets
> the requirements described in our User Data policy.

**Max's decision: rely on it, leave Shared unticked.** Note what the
bullet is, so the basis stays clear — relief from *declaring* a transfer
that is still sharing, not a finding that no sharing occurs. The
arguments for declaring anyway were weighed and set aside:

1. The exemption hinges on *"where the user reasonably expects"*. With a
   cloud voice as the system primary, text from **other apps** reaches
   the provider with no action inside Marmalade — that user formed no
   expectation at all. The exemption is weakest exactly where the data
   flow is largest.
2. The same page instructs that the form "describes the **sum** of your
   app's data collection and sharing across all its versions currently
   distributed on Google Play" — written to be over- rather than
   under-inclusive.
3. It is true. Relying on a soft exemption to avoid stating a true thing
   is the wrong trade for an app whose pitch is privacy.

This lands where the review panel did, but by a different route: they
read the exemption as clearly applicable, while Max weighed declaring
anyway and chose the exemption knowing the system-TTS soft spot.

**If a reviewer ever challenges it, do not argue** — tick Shared for both
types. The declaration is always available and costs one listing line;
the exemption is the only part of this form resting on judgement rather
than text.

### Ephemeral: answer No

Marmalade's *own* handling is ephemeral — memory only, never written to
disk or to a server we control. It is tempting to answer Yes on that
basis. **Don't.**

The transfer is not ephemeral end-to-end, and this is checkable rather
than assumed: OpenAI's API documentation states that *"abuse monitoring
logs are generated for all API feature usage and retained for up to 30
days, unless longer retention is required by law"* (Zero Data Retention
exists but requires prior approval, which a bring-your-own-key user will
not have). Thirty days is not "retained for no longer than necessary to
service the specific request in real-time".

The provider is not our service provider, so we cannot certify their
retention at all. And answering Yes **removes the entry from the public
listing** — hiding a disclosure from users on an assumption about a
third party's internals is the wrong direction for this app.

### Account creation and login: No

- **"My app does not allow users to create an account"** → correct.
- **"Can users login with accounts created outside of the app?"** →
  **No.** An app account is *"a unique user identity that developers
  provide as a user-facing feature to serve the user across applications
  and/or devices."* Marmalade provides no identity, no directory, no
  server; nothing gates the app behind authentication. The Venice/OpenAI
  account pre-exists the app and belongs to the provider.

Because there is no app account, Play's account-deletion obligations do
not attach.

> Keep `CloudProvider.keyHint` as plain descriptive text. If it ever
> becomes a tappable deep link into a provider signup flow, the clause
> about an app that *"directs the user to an app account creation flow
> outside of the app"* becomes something a reviewer could read
> over-literally. Cheap to avoid.

### Deletion: No — decided 2026-07-27

**"Do you provide a way for users to request that their data is
deleted?" → No.**

The question presumes a developer-held datastore. There isn't one: no
server, no account, nothing retained off the device. Answering **Yes**
would advertise a request channel that has nothing behind it.

Do **not** pick *"No, but user data is automatically deleted within 90
days"* — that asserts a retention timer we do not operate.

`PRIVACY.md` carries a matching **"Deleting your data"** section: app
storage is removed by clearing storage or uninstalling, a provider key is
removed in-app, and anything a provider retained is deleted through the
user's own account with them. The two documents must keep saying the same
thing.

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
