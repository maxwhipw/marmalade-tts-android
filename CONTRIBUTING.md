# Contributing to marmalade-tts-android

Thanks for the interest. This project is in early development, so the
contribution path is a bit different than a stable project.

## Right now

v0.1.0 has shipped as a debug-signed APK on
[GitHub Releases](https://github.com/maxwhipw/marmalade-tts-android/releases).
Focus for the next milestones is on the items called out in
[ROADMAP.md](ROADMAP.md) (Piper / Kokoro engines, production signing key,
audible-test automation).

If you want to:

- **Discuss the design:** open a discussion or an issue.
  Architecture decisions in [SPEC.md](SPEC.md) are documented, but
  pre-1.0 we're still willing to revisit them.
- **Help with v0.2:** the next-milestone items are scoped in ROADMAP.md.
  Comment on the tracking issue (once filed) saying which feature
  you'd take.
- **Suggest features beyond v0.2:** ROADMAP.md lists v0.3 → v1.0; PRs
  to ROADMAP.md proposing additions are welcome.

## Once code lands

The PR workflow will be:

1. Fork + branch
2. Build locally with Android Studio
3. `./gradlew test` and `./gradlew connectedAndroidTest` (the latter
   needs a connected device or emulator)
4. Open PR — keep it focused on one logical change
5. Update CHANGELOG.md under `[Unreleased]`

## Code of conduct

By participating you agree to the
[Code of Conduct](CODE_OF_CONDUCT.md).

## License

By contributing, you agree your contributions will be licensed under
the project's MIT license.
