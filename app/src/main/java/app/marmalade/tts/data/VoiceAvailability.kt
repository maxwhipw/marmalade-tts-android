package app.marmalade.tts.data

import app.marmalade.tts.BuildConfig
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.install.EngineInstaller
import app.marmalade.tts.install.InstallState
import app.marmalade.tts.install.VoicePackCatalog
import app.marmalade.tts.install.isUsableOnDisk

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   probeInstalledVoiceAssets(installer, anyCloudKeySet)
//     │     EngineInstaller.verify(engine)      for every catalog engine
//     │     EngineInstaller.verifyPack(packId)  for every pack of a
//     │                                         pack-based engine
//     ▼
//   InstalledVoiceAssets(engines, packs)
//     │
//     └── pickableVoices(assets, showDeveloper)
//            │   isVoiceAvailable (on disk) + the developer gate; used by
//            │   VoicePickerViewModel, AliasViewModel and OnboardingViewModel
//            │   to filter the Room voice rows down to what can be picked
//            ▼
//         buildVoiceTree(...) → the picker's source › model › voice tree
// -----------------------------------------------------------------------------
//
// Why this file exists: Room is seeded with EVERY catalog voice at startup, and
// `VoiceMeta.isInstalled` is never flipped in production, so disk state is the
// only honest answer to "can the user pick this". Engine-level granularity was
// enough while every engine's voices came in one bundle. VITS Marmalade breaks
// that: its voices arrive in nine independent per-language packs, so an
// engine-level check listed all 25 voices the moment ANY pack was on disk, and
// picking an absent one failed at synth with EngineNotInstalledException. The
// filter is therefore per-asset, and both picker surfaces share it so they
// can't drift apart again.
// -----------------------------------------------------------------------------

/**
 * What is actually on disk (plus configured, for the keys-only cloud engine).
 *
 * @property engines Engine names whose layout passes [EngineInstaller.verify],
 *                   plus the cloud engine when a provider key is configured.
 * @property packs   Voice-pack ids that pass [EngineInstaller.verifyPack].
 *                   Only pack-based engines contribute; for every other engine
 *                   the engine-level check is the whole story.
 */
data class InstalledVoiceAssets(
    val engines: Set<String> = emptySet(),
    val packs: Set<String> = emptySet(),
)

/**
 * Whether [voice] can be spoken right now.
 *
 * A pack-based engine's voice needs BOTH its engine and its own pack: the
 * engine verifies as installed as soon as one pack is present, which says
 * nothing about the pack this particular voice lives in.
 *
 * An unparseable VITS voice id (no pack segment) is treated as unavailable —
 * it is a row no engine can route, so offering it would only produce a
 * synthesis failure.
 */
fun isVoiceAvailable(voice: VoiceMeta, assets: InstalledVoiceAssets): Boolean {
    if (voice.engine !in assets.engines) return false
    if (!isPackBasedEngine(voice.engine)) return true
    val packId = VitsVoiceCatalog.packIdOf(voice.id) ?: return false
    return packId in assets.packs
}

/** [isVoiceAvailable] over a whole list, order preserved. */
fun List<VoiceMeta>.filterAvailable(assets: InstalledVoiceAssets): List<VoiceMeta> =
    filter { isVoiceAvailable(it, assets) }

/**
 * Whether [voice] is exposed to an ordinary (non-developer) user.
 *
 * A pack-based VITS voice is released only when its own pack is
 * [app.marmalade.tts.install.VoicePack.released]; an installed-but-unreleased
 * pack (side-loaded or dev-installed) follows the developer-engines precedent —
 * visible only in developer mode. Every non-VITS voice, and any VITS id whose
 * pack the catalog no longer lists, is released (the latter is filtered out by
 * [isVoiceAvailable] anyway). The picker waives this in developer mode outside
 * the Play build, so a developer still sees the staged packs' voices there.
 */
fun isVoiceReleased(voice: VoiceMeta): Boolean {
    val packId = VitsVoiceCatalog.packIdOf(voice.id) ?: return true
    return VoicePackCatalog.byId(packId)?.released ?: true
}

/**
 * The voices a user may pick: available on disk ([filterAvailable]) and, unless
 * [showDeveloper], neither on a developer-only engine nor in an unreleased pack
 * ([isVoiceReleased]) — so an installed-but-staged VITS pack, or a diagnostic
 * engine left installed after developer mode was switched off, stays out of an
 * ordinary user's list while a developer still sees it. The Play [flavor]
 * has no developer mode at all ([EngineCatalog.effectiveDeveloperMode]): a
 * stored `true` shows neither a developer engine nor an unreleased pack. Play
 * also never lists voices of `fdroidOnly` engines (Pocket), even if data
 * carried over from an F-Droid install still has them on disk.
 *
 * Shared by the full-screen picker, the alias editor and onboarding's alias
 * step so the three can't disagree about what is pickable.
 */
fun List<VoiceMeta>.pickableVoices(
    assets: InstalledVoiceAssets,
    showDeveloper: Boolean,
    flavor: String = BuildConfig.FLAVOR,
): List<VoiceMeta> {
    val developer = EngineCatalog.effectiveDeveloperMode(showDeveloper, flavor)
    val showUnreleased = VoicePackCatalog.showsUnreleased(developer, flavor)
    return filterAvailable(assets).filter { voice ->
        (developer || voice.engine !in EngineCatalog.developerOnlyNames) &&
            (flavor != "play" || EngineCatalog.byName(voice.engine)?.fdroidOnly != true) &&
            (showUnreleased || isVoiceReleased(voice))
    }
}

/**
 * True when [engineName]'s voices arrive as separate downloadable packs, so a
 * per-voice availability check has to look at the pack as well as the engine.
 */
private fun isPackBasedEngine(engineName: String): Boolean =
    EngineCatalog.byName(engineName)?.isPackBased == true

/**
 * Probe the disk for everything the voice filter needs.
 *
 * Sequential rather than parallel on purpose: [EngineInstaller.verify] and
 * `verifyPack` are presence/size checks on a handful of files, and the two
 * picker ViewModels both call this from a `refresh()` the screen triggers on
 * becoming active — fanning out coroutines for a few stat calls would cost more
 * than it saves.
 *
 * @param anyCloudKeySet Whether at least one cloud provider key is configured.
 *                       The cloud engine has no bundle, so a key IS its install
 *                       state; the caller reads the setting because this
 *                       function deliberately has no DataStore dependency.
 */
suspend fun probeInstalledVoiceAssets(
    installer: EngineInstaller,
    anyCloudKeySet: Boolean,
): InstalledVoiceAssets {
    val engines = mutableSetOf<String>()
    val packs = mutableSetOf<String>()
    for (engine in EngineCatalog.all) {
        if (installer.verify(engine.name) !is InstallState.Installed) continue
        engines += engine.name
        if (!engine.isPackBased) continue
        for (pack in VoicePackCatalog.forEngine(engine.name)) {
            // isUsableOnDisk, not `is Installed`: a pack whose archive was
            // re-published still speaks, and its voices should stay pickable
            // until the user takes the update.
            if (installer.verifyPack(pack.id).isUsableOnDisk) packs += pack.id
        }
    }
    if (anyCloudKeySet) engines += CloudApiVoiceCatalog.ENGINE
    return InstalledVoiceAssets(engines = engines, packs = packs)
}
