package app.marmalade.tts.ui.screen

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.data.db.AppAliasMapping
import app.marmalade.tts.data.db.AppAliasMappingDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   AliasScreen (routing strip on each alias card + AppRoutingSheet)
//     │
//     ├── mappings  ◄────── AppRoutingViewModel.mappings
//     │                        ▲  Flow — AppAliasMappingDao.getAll(), grouped
//     │                        │  by aliasName to render each card's strip.
//     │
//     ├── installedApps ◄── AppRoutingViewModel.installedApps
//     │                        ▲  one-shot InstalledAppsProvider.load(), fired
//     │                        │  when the sheet opens (PackageManager on IO).
//     │
//     ├── sheetState ◄────── AppRoutingViewModel.sheetState
//     │                        ▲  MutableStateFlow — which alias the sheet is
//     │                        │  scoped to + the ticked package set.
//     │
//     └── actions
//          ├── openSheet(aliasName)  — loads apps, seeds ticks from `mappings`
//          ├── toggle(packageName)   — tick/untick (Pro-gated on tick only)
//          └── saveRouting()         — diffs ticks against the alias's current
//                                      rows: upsert additions, delete removals
//
// Routing is stored app-first (packageName is the PK, one alias per app) but
// presented alias-first. The inversion lives here: `mappings` is grouped by
// alias for display, and [AppRoutingViewModel.saveRouting] translates an
// alias-scoped tick set back into per-package upserts and deletes.
//
// `aliasName` references VoiceAlias.name but is NOT a foreign key — see
// AppAliasMapping kdoc. If the user deletes the referenced alias the mapping
// stays around and TtsRouter falls back to the primary on the next synth.
// -----------------------------------------------------------------------------

/**
 * One row from the launcher-visible app roster.
 *
 * @property packageName  PK / lookup key — feeds into [AppAliasMapping.packageName].
 * @property displayName  Cached `applicationInfo.loadLabel(pm).toString()`.
 * @property icon         Resolved Drawable for the launcher icon. Loaded on
 *                        the IO dispatcher alongside the label so the LazyColumn
 *                        in the sheet doesn't have to do PackageManager work
 *                        per-row during fling. May be null if the package
 *                        has no resolvable icon — fall back to a placeholder.
 */
data class InstalledApp(
    val packageName: String,
    val displayName: String,
    val icon: Drawable?,
)

/**
 * Supplies the launchable-app roster. An interface so [AppRoutingViewModel]
 * stays a pure-JVM unit under test — only the production implementation needs
 * a real [PackageManager].
 */
interface InstalledAppsProvider {
    suspend fun load(): List<InstalledApp>
}

/**
 * Real roster: every installed app with a launcher intent.
 *
 * Apps without a launch intent (telephony stubs, media providers with no UI)
 * are dropped since the user has no way to invoke TTS from them. Marmalade
 * itself is dropped too — routing TTS through the app that performs it is
 * nonsensical.
 *
 * `PackageManager.MATCH_*` flags: we use the default `0`.
 * `MATCH_UNINSTALLED_PACKAGES` would include packages the user disabled —
 * undesirable here. On Android 11+, enumerating non-system apps needs a
 * `<queries>` element or `QUERY_ALL_PACKAGES`; AndroidManifest.xml carries a
 * minimal `<queries><intent>` block scoped to launchable apps — the same set
 * this filters to.
 */
@Singleton
class PackageManagerAppsProvider @Inject constructor(
    private val app: Application,
) : InstalledAppsProvider {

    override suspend fun load(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = app.packageManager
        pm.getInstalledApplications(0)
            .asSequence()
            .filter { info ->
                info.packageName != app.packageName &&
                    pm.getLaunchIntentForPackage(info.packageName) != null
            }
            .map { info -> info.toInstalledApp(pm) }
            .sortedBy { it.displayName.lowercase() }
            .toList()
    }

    private fun ApplicationInfo.toInstalledApp(pm: PackageManager): InstalledApp {
        // loadLabel falls back to the package name if no label is set;
        // never returns null per the framework contract.
        val label = loadLabel(pm).toString()
        val icon = try {
            pm.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        return InstalledApp(packageName = packageName, displayName = label, icon = icon)
    }
}

/**
 * Working state of the per-alias routing sheet.
 *
 * [selected] is the full tick set for [aliasName] — seeded from the saved
 * mappings when the sheet opens and diffed against them on save, so a sheet
 * that is opened and cancelled writes nothing.
 */
data class RoutingSheetState(
    val isOpen: Boolean = false,
    val aliasName: String = "",
    val selected: Set<String> = emptySet(),
    val query: String = "",
)

/**
 * Backs the routing half of [AliasScreen]: the "Used by N apps" strip on each
 * alias card and the app-picker sheet behind it.
 *
 * Deliberately separate from [AliasViewModel] rather than merged into it.
 * Routing needs the PackageManager roster; aliases don't, and keeping them
 * apart leaves [AliasViewModel] free of it. The screen holds two
 * `hiltViewModel()` handles.
 */
@HiltViewModel
class AppRoutingViewModel @Inject constructor(
    private val mappingDao: AppAliasMappingDao,
    private val appsProvider: InstalledAppsProvider,
) : ViewModel() {

    /** Clock indirection for tests — same idiom as [AliasViewModel]. */
    internal var now: () -> Long = { System.currentTimeMillis() }

    val mappings: StateFlow<List<AppAliasMapping>> = mappingDao.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())

    /**
     * Launchable apps — populated lazily by [openSheet]. Empty until the
     * first load completes, which the sheet renders as "Loading apps…".
     */
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _sheetState = MutableStateFlow(RoutingSheetState())
    val sheetState: StateFlow<RoutingSheetState> = _sheetState.asStateFlow()

    /**
     * Open the routing sheet for [aliasName], ticking the apps already routed
     * there.
     *
     * Not Pro-gated: free users can open the sheet, see which apps are routed
     * where, and untick rows. Only *adding* a route is gated (see [toggle]).
     * That preserves the pre-existing rule that a refunded user can always
     * clean up mappings they made while subscribed, and it puts the paywall at
     * the moment of intent rather than on the door.
     */
    fun openSheet(aliasName: String) {
        loadInstalledApps()
        _sheetState.value = RoutingSheetState(
            isOpen = true,
            aliasName = aliasName,
            selected = mappings.value
                .filter { it.aliasName == aliasName }
                .map { it.packageName }
                .toSet(),
        )
    }

    fun dismissSheet() {
        _sheetState.value = RoutingSheetState()
    }

    fun onQueryChange(query: String) {
        _sheetState.value = _sheetState.value.copy(query = query)
    }

    /** Tick or untick [packageName]. */
    fun toggle(packageName: String) {
        val state = _sheetState.value
        _sheetState.value =
            if (packageName in state.selected) {
                state.copy(selected = state.selected - packageName)
            } else {
                state.copy(selected = state.selected + packageName)
            }
    }

    /**
     * Persist the sheet's tick set for its alias, then close.
     *
     * Newly ticked apps are upserted to this alias; unticked apps that
     * belonged to it are deleted. Rows owned by other aliases are never
     * touched — the sheet disables those rows rather than letting a tick
     * steal them, so an app is only ever selectable from the one alias that
     * owns it (packageName is the PK). The upsert is still a PK-replace, so
     * the invariant holds even if a route is added by some other path.
     */
    fun saveRouting() {
        val state = _sheetState.value
        val aliasName = state.aliasName
        val current = mappings.value
        val previous = current
            .filter { it.aliasName == aliasName }
            .map { it.packageName }
            .toSet()

        val selected = state.selected

        val added = selected - previous
        val removed = previous - selected
        val roster = _installedApps.value

        viewModelScope.launch {
            for (packageName in added) {
                mappingDao.upsert(
                    AppAliasMapping(
                        packageName = packageName,
                        aliasName = aliasName,
                        displayName = roster
                            .firstOrNull { it.packageName == packageName }
                            ?.displayName,
                        // Preserve the original timestamp when an app moves
                        // between aliases so list order stays stable.
                        createdAt = current
                            .firstOrNull { it.packageName == packageName }
                            ?.createdAt
                            ?: now(),
                    ),
                )
            }
            for (packageName in removed) {
                mappingDao.delete(packageName)
            }
        }
        _sheetState.value = RoutingSheetState()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            _installedApps.value = appsProvider.load()
        }
    }

    private companion object {
        // Same 5s grace period as the other ViewModels — keeps state warm
        // across config changes without leaking observers.
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
