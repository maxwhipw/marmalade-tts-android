# Pro paywall plan — Play-only, F-Droid stays free

Implementation spec for gating per-app voices + custom sound effects
behind a one-time Pro purchase in the Play build, while keeping the
F-Droid build fully unlocked. Authored 2026-06-14; not yet implemented.

## Product decisions (locked unless explicitly revisited)

- **Free everywhere except the Play paywall.** F-Droid build = all
  features free. Play free tier = synth + built-in effects + primary
  alias + per-app voices visible but blocked; Pro upgrade unlocks
  per-app routing + custom effect creation.
- **One-time purchase, not subscription.** Marmalade is a utility.
  Subscriptions on utilities feel extractive and Android utility
  precedent (e.g. Bromite, AntennaPod-style donate-ware) is one-time.
  Product id: `marmalade_pro`. Suggested price **USD 4.99** — adjust in
  the Play Console listing, the in-code copy doesn't hard-code it.
- **Free features stay genuinely useful.** Synth, every voice, built-in
  effect presets, primary alias, system-TTS integration, share-sheet,
  QS tile, batch synth — all free. The paywall buys *personalisation*
  and *power-user* features, not the core function. This is the
  difference between "freemium with friction" (cheap-feeling) and
  "freemium with depth" (respectful — Sequence, Reeder, AntennaPod).
- **Accessibility users never paywalled out.** A pro entitlement is a
  preference layered on top — TalkBack and similar AT clients still
  get the primary-alias voice without any per-app config and that's
  the right default. Make this explicit in the in-app paywall copy.

## Free vs Pro split

| Feature                                  | Free | Pro |
|------------------------------------------|------|-----|
| Synth + every voice + every language     | ✅   | ✅  |
| Built-in effect presets (chip selection) | ✅   | ✅  |
| Primary alias (set one default persona)  | ✅   | ✅  |
| System-TTS integration                   | ✅   | ✅  |
| Share-sheet / QS tile / batch synth      | ✅   | ✅  |
| **Custom effect blocks** (create / edit) | 🚫   | ✅  |
| **Per-app voice routing**                | 🚫   | ✅  |

Free users see both gated screens but tapping the actionable affordance
opens the paywall sheet instead of the editor.

## Implementation — minimum viable, ordered

### 1. Build flavors

```kotlin
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "BILLING_ENABLED", "true")
        }
        create("fdroid") {
            dimension = "distribution"
            buildConfigField("boolean", "BILLING_ENABLED", "false")
        }
    }
}
```

Same `applicationId` for both. Same signing config. The fdroiddata
recipe specifies `gradle: [fdroid]` so F-Droid's buildserver compiles
the `fdroid` flavor; CI's existing `bundleRelease`/`assembleRelease`
need an explicit `bundlePlayRelease`/`assemblePlayRelease` from now
on.

### 2. Billing dependency — Play flavor only

```kotlin
dependencies {
    "playImplementation"("com.android.billingclient:billing-ktx:7.0.0")
}
```

`playImplementation` keeps the Google Billing library — and its
network calls — entirely out of the F-Droid build. F-Droid's
"NoAntiFeatures" check verifies the APK has no `com.google.*` /
`com.android.billingclient` classes; the flavor split is what makes
that true.

### 3. `ProEntitlement` interface (commonMain — `src/main`)

```kotlin
interface ProEntitlement {
    val isPro: StateFlow<Boolean>
    suspend fun launchPurchase(activity: Activity): PurchaseResult
    suspend fun restorePurchases()
}
```

Two concrete implementations:

- `src/fdroid/.../FdroidProEntitlement.kt` — returns
  `MutableStateFlow(true)`. `launchPurchase` no-ops. `restorePurchases`
  no-ops. Wired via Hilt.

- `src/play/.../PlayProEntitlement.kt` — wraps `BillingClient`,
  `queryProductDetails(marmalade_pro)`, `launchBillingFlow`, and
  `queryPurchasesAsync` on app start + every `onResume`. Persists the
  cached entitlement to DataStore so offline launches stay unlocked
  for ~30 days; re-verifies online on resume.

Hilt module under `src/play/` provides `PlayProEntitlement`; under
`src/fdroid/` provides `FdroidProEntitlement`. The shared module
binds the interface, neither flavor imports the other's class.

### 4. UI gating

Two trip-wire points:

- `AppMappingsScreen` — the FAB ("+ Add per-app voice") and tap on a
  row open the paywall sheet instead of the editor when
  `!proEntitlement.isPro`. The list of existing mappings remains
  visible so users see what the feature does. Existing mappings keep
  working (no retro-paywall of user data); they just can't add/edit.
- `EffectsScreen` — the "Create effect" FAB opens the paywall. The
  built-in effect chips/presets remain selectable and editable
  (presets are pre-shipped data, not user-created). Alias-level
  effect selection stays free.

Both screens read `proEntitlement.isPro` as a Compose `State<Boolean>`.

### 5. Paywall sheet (`PaywallSheet.kt`)

Single `ModalBottomSheet` Composable:

- Headline: "Marmalade Pro" + mascot art
- Two-bullet value prop tied to the feature the user just tapped
  (per-app voices vs. custom effects). One line each.
- Copy block: "Marmalade is open source and free everywhere. The Pro
  upgrade supports development and unlocks per-app routing and custom
  effects. One purchase, lifetime."
- **Restore purchases** button (Play policy requires it — must be
  visible, not buried).
- **Upgrade button** → `launchPurchase`. Errors render in-sheet.
- Accessibility note: "Already accessible without Pro — your primary
  voice is used everywhere automatically."

Sheet lives in `src/play/` only (free flavor never shows a paywall).
The trip-wire screens accept an `onShowPaywall: () -> Unit` lambda
that's null-and-noop in fdroid (which just lets the gated action
proceed because isPro=true).

### 6. Play Console setup (Max, when the developer account verifies)

1. App → Monetisation → Products → **In-app products** → Create.
2. Product id: `marmalade_pro`. Name: "Marmalade Pro". Description:
   "Per-app voice routing and custom audio effects."
3. Price: USD 4.99 (Play converts to other locales).
4. Status: **Active**.
5. Internal testing track must be active before the IAP can be
   purchased in a test build — set up at least one tester (your own
   account is fine).

### 7. Crash-safe + abuse-safe

- `BillingClient.connect` failures must not block app launch; isPro
  defaults to the last cached value, then re-verifies in the
  background.
- `queryPurchasesAsync` returns the source of truth on every
  foreground; a refunded purchase locks features back. Cache only
  reduces network chatter, never overrides Play's word.
- Acknowledge purchases within 3 days (`acknowledgePurchase`) or Play
  refunds them automatically. Do this in `onPurchasesUpdated`.
- Don't hard-pin to an Activity — use `BillingClient.newBuilder(context)`
  and pass the Activity only at `launchBillingFlow` time.

### 8. Testing

- Internal-testing track → install signed build → purchase with a
  Google test account (free, instant refund flow).
- Test the refund path: refund the test purchase in Play Console,
  confirm isPro flips back to false within one resume.
- Test offline launch with stale cache: airplane mode → relaunch →
  Pro features should stay unlocked from the DataStore cache. After
  ~30 days of consecutive offline, revoke (force re-verify).
- Test the F-Droid build separately (`assembleFdroidRelease`) → no
  billing classes, no paywall sheet, all features unlocked.

### 9. Play policy + UX musts (don't ship without)

- **Restore purchases** button must exist on the paywall sheet AND on
  Settings → About (in case the user dismisses the sheet).
- **No mention of alternative payment methods.** Don't link to GitHub
  Sponsors / Patreon / Ko-fi from inside the Play build — Play policy
  forbids it and will reject the listing. Sponsors links are fine in
  the F-Droid build, in the README/website, just not in-app on Play.
- **No "subscribe to unlock" language.** It's a one-time purchase;
  copy must say so.
- Play Console **Data safety form** stays "no data collected" — Play
  Billing's payment processing is exempt from the form.

## F-Droid build — explicit checklist

- `assembleFdroidRelease` is what F-Droid builds. The `gradle:`
  field in `metadata/app.marmalade.tts.yml` becomes `[fdroid]`.
- `proImplementation` (the billing dep) is excluded from the apk by
  Gradle's flavor source set — no manual `<uses-feature>` removal
  needed.
- `License: GPL-3.0-or-later` stays correct (espeak-ng compiled in;
  the flavor split doesn't change the license).
- NonFreeNet anti-feature stays (engine downloads from GitHub).
- **No new anti-features** because the F-Droid flavor has no Google
  classes, no telemetry, no billing — same posture as today.
- Update `docs/release/FDROID-RELEASE-PLAN.md` with the flavor and
  `gradle: [fdroid]` line.

References Max sent:
- https://f-droid.org/docs/FAQ_-_App_Developers/#how-do-i-get-my-app-included
- https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/

Nothing in those changes the existing plan — they confirm the
"submit a `metadata/<package>.yml` MR to fdroiddata" path.

## CI implications

`.github/workflows/release.yml` currently runs `bundleRelease` +
`assembleRelease`. With flavors that becomes `bundlePlayRelease` +
`assemblePlayRelease` for the Play artifact, and the workflow
should also produce `assembleFdroidRelease` and attach it to the
GitHub Release as the sideload/F-Droid-preview build. F-Droid still
builds from its own buildserver — the github-attached fdroid APK is
for users who want to sideload without waiting on F-Droid's index
update cycle.

## Open questions (decide before implementing)

1. **Price.** USD 4.99 default; willing to be wrong on this. UK/EU
   pricing tiers Play picks automatically; check the Console preview
   before locking.
2. **Sponsor / donate link in the F-Droid build?** Suggest yes — a
   Settings → "Support development" entry that opens the GitHub
   Sponsors page. F-Droid build only; Play build hides it.
3. **Pro user perks beyond features?** Optional: a small "Pro" badge
   somewhere unobtrusive (e.g. About screen footer). Keep subtle.
4. **What happens to existing per-app mappings if a user refunds?**
   Recommendation: keep the rows visible + functional for one more
   resume after isPro flips, then disable them (so the user notices
   and either re-purchases or removes them) rather than yanking
   audio behaviour out from under a running TTS session.

## Test plan (manual, on the Pixel)

| Path                                                  | Expected (Play)              | Expected (F-Droid)         |
|-------------------------------------------------------|------------------------------|----------------------------|
| Speak any text with built-in effect                   | works                        | works                      |
| Tap + on Per-app voices                               | paywall sheet                | editor opens               |
| Tap + on Effects                                      | paywall sheet                | editor opens               |
| Edit a built-in effect's name (not allowed today)     | unchanged                    | unchanged                  |
| Purchase Pro → tap + on Per-app voices                | editor opens                 | n/a                        |
| Refund Pro → tap + on Per-app voices                  | paywall sheet                | n/a                        |
| Airplane mode launch after Pro purchase               | Pro stays unlocked (cache)   | always unlocked            |
| TalkBack reads alongside Marmalade as default TTS     | works (no paywall)           | works                      |

## Effort estimate

Implementation, end-to-end, is ~1 focused session:
- Flavors + DI wiring: short
- ProEntitlement interface + both impls: short for fdroid, longer for
  play (BillingClient setup + retry + acknowledge)
- Paywall sheet + two screen trip-wires: short
- Testing on device with internal track: depends on Play Console
  approval cadence

If the session runs low, the implementation order above is
deliberately stop-anywhere: each step compiles and ships a usable
build (the F-Droid path works after step 5 even without step 6).
