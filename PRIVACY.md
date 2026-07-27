# Privacy Policy — Marmalade TTS

_Last updated: 2026-07-26_

Marmalade TTS is a **text-to-speech app that speaks entirely on your
device by default** — offline, with no internet connection needed —
and *optionally* lets you turn on **cloud voices** from a third-party
provider instead. Used offline, it collects and shares nothing: the text
you have it read stays on your phone, and there is no analytics,
tracking, or account of any kind. If you do set up a cloud provider, the
text you have spoken is sent to that provider, under **their** privacy
policy rather than this one. That is the one and only case in which your
text leaves your device, it never happens unless you enable it, and it is
described in full below.

## Summary

- **We collect nothing.** No analytics, tracking, telemetry, crash
  reporting, ads, or accounts. There is no Marmalade server anywhere —
  we could not collect your data even if we wanted to.
- **On-device voices are fully offline.** Text sent to Marmalade's
  built-in engines is converted to audio locally and never uploaded.
- **Cloud voices are opt-in and send your text to a third party.** If
  you add a provider's API key and select one of its voices, your text
  goes to that company, under *their* privacy policy and terms — not
  ours. See below.
- **Your API keys stay on your device** and are sent only to the
  provider they belong to.

## What stays on your device

- **Your settings and voice choices** — voice selections, aliases /
  personas, audio-effect presets, per-app voice routing, and history are
  stored in app-private storage.
- **Your provider API keys** — stored in app-private storage. They are
  never sent anywhere except to the provider they authenticate against.
- **Text spoken with an on-device voice** — synthesis happens locally
  and the text is never transmitted.

## Cloud voice providers (optional)

Marmalade can synthesize speech through third-party cloud providers —
currently **Venice AI** and **OpenAI**, with the list updatable in future
releases. This is entirely opt-in. It does nothing until you go to
**Engines → Cloud voices**, add your own API key for a provider, and
select one of that provider's voices.

The first time you open that screen, the app shows a disclaimer covering
everything in this section and will not let you configure a provider
until you accept it. It is shown once; this page is the permanent copy.

**When you use a cloud voice, the following is sent to that provider
over HTTPS:**

- **The text to be spoken.** In full.
- The voice and model you selected, and the speed and audio format
  requested.
- Your API key for that provider, as authentication.
- When discovering voices, a request asking the provider which models
  and voices your account can use.

**That data is then handled by that provider, under that provider's own
privacy policy and terms and conditions — not this one.** What they log,
how long they keep it, and whether they use it to train models is
governed by their policies and your account with them. Please read them
before enabling a cloud voice:

- Venice AI — <https://venice.ai/legal/privacy> and <https://venice.ai/legal/tos>
- OpenAI — <https://openai.com/policies/privacy-policy> and <https://openai.com/policies/terms-of-use>

**We are not affiliated with, endorsed by, sponsored by, or partnered
with any of these providers in any way.** Marmalade is an independent
app that speaks their public APIs using credentials you supply and an
account you hold directly with them. Their names and trademarks are used
only to identify the service you are connecting to. We receive nothing
from them, we have no visibility into your usage of them, and we cannot
act on your behalf with them — including honouring any request to delete
data they hold. Direct those requests to the provider.

**One consequence worth understanding.** Marmalade can act as your
system-wide text-to-speech engine, and it can route particular apps to
particular voices. If you set a **cloud** voice as your primary alias or
route an app to one, then text those *other* apps ask Android to speak —
your ebooks, your messages, whatever an accessibility service reads
aloud — is also sent to that provider. If you want a guarantee that
nothing ever leaves your device, use only the on-device engines. The app
labels cloud voices as such wherever they appear.

## Other network activity

When you tap "Install" on an on-device engine (during onboarding or in
Engines), the app downloads that engine's voice-model files and its
espeak-ng pronunciation dictionaries over HTTPS from:

> **github.com/maxwhipw/marmalade-tts-android-engines** (GitHub Releases)

**No executable code is ever downloaded.** The espeak-ng phonemizer
itself is compiled into the app when it is built; what a download adds
is voice models and pronunciation data, nothing that runs.

These are ordinary file downloads. Each file is verified against a
pinned SHA-256 hash before being saved. No information about you is sent
beyond what a normal HTTPS file download requires. Once your chosen
on-device engines are installed, they work fully offline.

The app also fetches a small list of available cloud providers from the
same repository, so a new provider can be offered without a full app
update. That request carries no information about you, and happens
whether or not you use cloud voices.

GitHub's handling of that request (e.g. server logs) is governed by
[GitHub's Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).

Apart from the two GitHub requests above and any cloud provider you
explicitly configure, the app contacts no other server. Marmalade has no
server of its own.

## Deleting your data

There is no "delete my data" request to send us, because there is nothing
on our side to delete. Marmalade has no server and no account, and keeps
nothing about you anywhere but on your own phone.

- **Everything the app stores** — your settings, aliases, effect presets,
  per-app routing, history, and any provider API key — lives in
  app-private storage on your device. Removing a key deletes it.
  Uninstalling the app, or clearing its storage in Android's app
  settings, removes all of it.
- **Anything a cloud provider has kept** is held by that provider, not by
  us, and has to be deleted through your own account with them. We have
  no access to it and cannot make that request on your behalf.

## Permissions and why they are needed

| Permission | Why Marmalade needs it |
|---|---|
| `INTERNET` | To download optional engine/model files from GitHub Releases, and — only if you configure one — to reach your chosen cloud voice provider. |
| `POST_NOTIFICATIONS` (Android 13+) | To show the "speaking" / "keeping engine loaded" foreground notice Android requires when the app plays audio or runs a foreground service. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | To keep long-form speech playing reliably (including with the screen off) and to expose lock-screen / Bluetooth playback controls. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | For the optional "keep engine loaded" service so the next speak request is instant. You opt into this in Settings → Performance. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | To offer an in-app prompt asking Android not to pause the speech service mid-sentence when the screen sleeps. The prompt is optional and you can decline it. |

The app does **not** request `QUERY_ALL_PACKAGES`. The "per-app voices"
feature lists only apps that have a launcher icon, so you can pick which
app uses which voice; this stays on your device.

## Children's privacy

Marmalade does not collect any data from anyone, including children, and
contains no ads and no in-app purchases. Every feature is free. Note
that the optional cloud providers above have their own age and account
requirements.

## Changes to this policy

If this policy changes, the updated version will be published in the app
repository with a new "Last updated" date.

## Contact

Questions about privacy can be sent to:

> **maxwellw@posteo.com**

You can also report any behaviour that contradicts this policy through
the project's security disclosure process (see [SECURITY.md](SECURITY.md)).
