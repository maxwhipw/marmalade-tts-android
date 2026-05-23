package app.marmalade.tts.ui.screen

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.marmalade.tts.data.db.AppAliasMapping
import app.marmalade.tts.data.db.AppAliasMappingDao
import app.marmalade.tts.data.db.VoiceAlias
import app.marmalade.tts.data.db.VoiceAliasDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
//   AppMappingsScreen
//     │
//     ├── mappings ◄────── AppMappingsViewModel.mappings
//     │                       ▲
//     │                       │ Flow
//     │                AppAliasMappingDao.getAll()
//     │
//     ├── aliases  ◄────── AppMappingsViewModel.aliases
//     │                       ▲
//     │                       │ Flow
//     │                VoiceAliasDao.getAll()
//     │
//     ├── installedApps ◄── AppMappingsViewModel.installedApps
//     │                       ▲
//     │                       │ loadInstalledApps()  — one-shot, fires when
//     │                       │   the sheet opens. PackageManager call runs
//     │                       │   on Dispatchers.IO; result emitted as
//     │                       │   StateFlow<List<InstalledApp>>.
//     │
//     └── actions
//          ├── save(packageName, aliasName, displayName) → dao.upsert(...)
//          └── delete(packageName)                       → dao.delete(...)
//
// `aliasName` references VoiceAlias.name but is NOT a foreign key — see
// AppAliasMapping kdoc. If the user deletes the referenced alias the
// mapping stays around and TtsRouter falls back to the primary on the
// next synth.
// -----------------------------------------------------------------------------

/**
 * One row from `PackageManager.getInstalledApplications(0)` filtered to
 * launchable apps. Kept lightweight (no icon yet) so the picker sheet can
 * load fast on devices with hundreds of installed apps; the icon is
 * fetched lazily by the row composable via [PackageManager.getApplicationIcon].
 *
 * @property packageName  PK / lookup key — feeds into [AppAliasMapping.packageName].
 * @property displayName  Cached `applicationInfo.loadLabel(pm).toString()`.
 * @property icon         Resolved Drawable for the launcher icon. Loaded on
 *                        the IO dispatcher alongside the label so the LazyColumn
 *                        in the picker doesn't have to do PackageManager work
 *                        per-row during fling. May be null if the package
 *                        has no resolvable icon — fall back to a placeholder.
 */
data class InstalledApp(
    val packageName: String,
    val displayName: String,
    val icon: Drawable?,
)

/**
 * Working state of the "Add / edit mapping" sheet on [AppMappingsScreen].
 *
 * `originalPackageName` is non-null in edit mode and pins the row's PK so
 * the user can change the chosen alias without accidentally creating a
 * second row.
 */
data class MappingEditorState(
    val isOpen: Boolean = false,
    val originalPackageName: String? = null,
    val selectedApp: InstalledApp? = null,
    val selectedAliasName: String? = null,
)

/**
 * ViewModel for [AppMappingsScreen].
 *
 * Owns the list of saved (package → alias) mappings, the list of
 * user-saved aliases (for the picker), and the lazily-loaded installed-apps
 * roster (for the "Add" sheet).
 *
 * PackageManager work runs on `Dispatchers.IO` — on a device with a
 * couple hundred installed apps, calling `getInstalledApplications` +
 * `loadLabel` on the main thread can stutter for hundreds of milliseconds.
 */
@HiltViewModel
class AppMappingsViewModel @Inject constructor(
    private val app: Application,
    private val mappingDao: AppAliasMappingDao,
    aliasDao: VoiceAliasDao,
) : ViewModel() {

    /** Clock indirection for tests — same idiom as [AliasViewModel]. */
    internal var now: () -> Long = { System.currentTimeMillis() }

    val mappings: StateFlow<List<AppAliasMapping>> = mappingDao.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    /** All user-saved aliases — the choices for the alias picker. */
    val aliases: StateFlow<List<VoiceAlias>> = aliasDao.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    /**
     * Installed apps with a launcher intent — populated lazily by
     * [loadInstalledApps], typically when the user opens the "Add"
     * sheet. Empty until the first load completes.
     */
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _editorState = MutableStateFlow(MappingEditorState())
    val editorState: StateFlow<MappingEditorState> = _editorState.asStateFlow()

    /**
     * Load the installed-apps list off the main thread. Filters to apps
     * with a non-null launch intent — system services without a UI
     * (telephony, media providers, etc.) are dropped since the user has
     * no way to invoke TTS from them. Idempotent: safe to call on every
     * sheet open, but cheap enough that we don't bother caching beyond
     * the StateFlow itself.
     *
     * `PackageManager.MATCH_*` flags: we use the default `0` flag.
     * `MATCH_UNINSTALLED_PACKAGES` would include packages disabled by
     * the user — undesirable here (no point routing TTS through a
     * disabled app). On Android 11+, the manifest needs a `<queries>`
     * element or `QUERY_ALL_PACKAGES` permission to enumerate
     * non-system apps; AndroidManifest.xml carries a minimal
     * `<queries><intent>` block that scopes visibility to launchable
     * apps — same set this method filters to.
     */
    fun loadInstalledApps() {
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) {
                val pm = app.packageManager
                pm.getInstalledApplications(0)
                    .asSequence()
                    .filter { info ->
                        // Has a launcher intent (filters out telephony stubs,
                        // media providers without UI, etc.). Also drops the
                        // current app — routing marmalade-tts through itself
                        // is nonsensical.
                        info.packageName != app.packageName &&
                            pm.getLaunchIntentForPackage(info.packageName) != null
                    }
                    .map { info -> info.toInstalledApp(pm) }
                    .sortedBy { it.displayName.lowercase() }
                    .toList()
            }
        }
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

    /**
     * Open the editor sheet.
     *
     * @param existing  null  ⇒ create-new mode (FAB tap).
     *                  non-null ⇒ edit mode — pins the original package
     *                            name so changing the alias works without
     *                            creating a duplicate row.
     */
    fun openEditor(existing: AppAliasMapping? = null) {
        val all = _installedApps.value
        _editorState.value = if (existing == null) {
            MappingEditorState(isOpen = true)
        } else {
            MappingEditorState(
                isOpen = true,
                originalPackageName = existing.packageName,
                selectedApp = all.firstOrNull { it.packageName == existing.packageName }
                    ?: InstalledApp(
                        packageName = existing.packageName,
                        displayName = existing.displayName ?: existing.packageName,
                        icon = null,
                    ),
                selectedAliasName = existing.aliasName,
            )
        }
    }

    fun dismissEditor() {
        _editorState.value = MappingEditorState()
    }

    fun selectApp(installed: InstalledApp) {
        _editorState.value = _editorState.value.copy(selectedApp = installed)
    }

    fun selectAlias(aliasName: String) {
        _editorState.value = _editorState.value.copy(selectedAliasName = aliasName)
    }

    /**
     * Persist the editor's current state. Returns true on success so the
     * caller can dismiss the sheet; on failure (no app or no alias
     * selected) the sheet stays open.
     */
    fun save(): Boolean {
        val state = _editorState.value
        val app = state.selectedApp ?: return false
        val aliasName = state.selectedAliasName?.takeIf { it.isNotBlank() } ?: return false

        val mapping = AppAliasMapping(
            packageName = app.packageName,
            aliasName = aliasName,
            displayName = app.displayName,
            createdAt = mappings.value
                .firstOrNull { it.packageName == app.packageName }
                ?.createdAt
                ?: now(),
        )

        viewModelScope.launch {
            // If the user changed the picked app during an edit (i.e.
            // they re-selected a different package), drop the old row.
            val originalPkg = state.originalPackageName
            if (originalPkg != null && originalPkg != app.packageName) {
                mappingDao.delete(originalPkg)
            }
            mappingDao.upsert(mapping)
        }
        _editorState.value = MappingEditorState()
        return true
    }

    /** Remove the mapping for [packageName]. No-op if it doesn't exist. */
    fun delete(packageName: String) {
        viewModelScope.launch {
            mappingDao.delete(packageName)
        }
    }

    private companion object {
        // Same 5s grace period as the other ViewModels — keeps state warm
        // across config changes without leaking observers.
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
