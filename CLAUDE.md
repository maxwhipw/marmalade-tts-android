# marmalade-tts-android — project notes for Claude

## Orientation: read REPO-MAP.md first

`REPO-MAP.md` at the project root is the orientation pass for this
codebase. It covers module structure, key files by concern, data
flow, conventions, and known quirks (TTS engine registration
requirements, nested-Scaffold inset handling, Hilt + ComponentActivity
constraint, the runBlocking hot-path cache pattern, etc.). Read it
before doing exploratory Grep/Glob work.

When spawning a subagent for investigation or implementation in this
repo, include **"Read REPO-MAP.md first"** in the briefing. The
subagent inherits this CLAUDE.md but won't read the map unless told.

Keep `REPO-MAP.md` current — when you discover a new gotcha or
architectural choice a future agent should know, update the map in
the same commit as the change.

## Remotes

**github is the only remote and is authoritative.**

```
github   https://github.com/maxwhipw/marmalade-tts-android.git
```

Push only to github (`git push github main`). Unlike the sister CLI
project at `/home/max/coding/marmalade-tts-cli`, this repo has no
Forgejo mirror configured — github is the single source of truth.

## Versioning

Bump `versionCode` + `versionName` in `app/build.gradle.kts` per
release. v0.1.x is debug-signed only — `applicationIdSuffix = ".debug"`,
so the installed package on devices is `app.marmalade.tts.debug`.
Commits of the form `vX.Y.Z: ...` mark a version bump.

When working on a batch of changes that would warrant separate
logical commits, split them — even if the work was done in one
session (recent v0.1.15/16/17 splits used `git stash` to peel apart
mixed working trees cleanly).
