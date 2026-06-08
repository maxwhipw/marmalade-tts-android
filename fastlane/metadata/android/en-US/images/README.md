# Store images — what to drop here

This folder holds the visual assets for the Google Play and F-Droid
listings, using the **fastlane / Triple-T `metadata/android` layout**
(F-Droid reads the same structure). Image files are NOT in version
control yet — this README lists what each store needs and where it goes.
Add the actual files at the paths below.

All screenshots should show the app on a real (or emulated) device.
Avoid status-bar clutter and personal data. PNG or JPEG; PNG preferred
for UI.

## Required / expected files

| File (relative to this folder) | Used by | Spec |
|---|---|---|
| `icon.png` | Play, F-Droid | App icon. 512×512 px, 32-bit PNG. F-Droid also pulls the launcher icon from the APK, but a clean 512² here is recommended. |
| `featureGraphic.png` | Play (required), F-Droid | 1024×500 px. Shown at the top of the Play listing. No transparency. |
| `phoneScreenshots/1.png` … `8.png` | Play (min 2), F-Droid | Phone screenshots, numbered in display order. 16:9 or 9:16, min 320 px shortest side, max 3840 px longest. Play requires at least 2; 4–8 recommended. |

## Optional / recommended

| File | Used by | Spec |
|---|---|---|
| `promoGraphic.png` | Play (legacy) | 180×120 px. Optional; many listings skip it. |
| `tvBanner.png` | Play (TV only) | 1280×720 px. Only if you ever ship an Android TV build — not needed now. |
| `sevenInchScreenshots/*.png` | Play | 7" tablet screenshots, if you support tablets. |
| `tenInchScreenshots/*.png` | Play | 10" tablet screenshots, if you support tablets. |

## Suggested screenshots to capture

Aim for shots that show what makes Marmalade distinctive:

1. Onboarding / engine picker (choose which engines to install).
2. Main speak screen with text and a voice selected.
3. The Android system TTS picker showing "Marmalade TTS" selected as
   the device engine.
4. Voice aliases / personas screen.
5. Audio-effect presets.
6. Emoji-driven prosody in action (text with emoji + speak).
7. Voice-cloning consent screen (shows the privacy posture).
8. Quick Settings tile or share-sheet target.

## Notes

- The mascot art lives in `../../../../../../assets/` (repo `assets/`)
  if you want to build the feature graphic or icon from it.
- Keep the feature graphic text-light: Play crops and overlays it.
- F-Droid will also display `full_description.txt`,
  `short_description.txt`, and the `changelogs/` entries from the parent
  folder — no extra image is required beyond icon + screenshots.
