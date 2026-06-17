package app.marmalade.tts.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.net.Uri
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.marmalade.tts.BuildConfig
import app.marmalade.tts.pro.PaywallReason
import app.marmalade.tts.pro.ProEntryPoint
import app.marmalade.tts.pro.ProGateHost
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.marmalade.tts.ui.onboarding.OnboardingScreen
import app.marmalade.tts.ui.screen.AliasScreen
import app.marmalade.tts.ui.screen.AppMappingsScreen
import app.marmalade.tts.ui.screen.BenchmarkScreen
import app.marmalade.tts.ui.screen.EffectEditorScreen
import app.marmalade.tts.ui.screen.EffectEditorViewModel
import app.marmalade.tts.ui.screen.EffectsScreen
import app.marmalade.tts.ui.screen.EngineDetailScreen
import app.marmalade.tts.ui.screen.EnginesScreen
import app.marmalade.tts.ui.screen.LicenseTextScreen
import app.marmalade.tts.ui.screen.LicensesScreen
import app.marmalade.tts.ui.screen.SettingsScreen
import app.marmalade.tts.ui.screen.SpeakScreen
import app.marmalade.tts.ui.screen.VoicePickerScreen

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   MainActivity → AppRoot()
//                    │
//                    ├── viewModel<AppRootViewModel>() — activity-scoped (see
//                    │     v0.1.4 commit for why `hiltViewModel()` doesn't
//                    │     work at the root of a NavHost-less composition).
//                    │
//                    ├── if (!onboarded) ─► OnboardingScreen() (no nav bar)
//                    │
//                    └── else: Scaffold { bottomBar = NavigationBar(...) }
//                              NavHost(startDestination = Routes.Speak)
//                                ├── Routes.Speak        → SpeakScreen     (tab)
//                                ├── Routes.Voices       → VoicePickerScreen (tab)
//                                ├── Routes.Aliases      → AliasScreen     (tab, v0.1.18+)
//                                ├── Routes.Settings     → SettingsScreen  (tab)
//                                ├── Routes.Engines      → EnginesScreen
//                                │                         (detail; reached from
//                                │                          Settings → Engines or the
//                                │                          Speak empty-state CTA)
//                                ├── Routes.AppMappings  → AppMappingsScreen
//                                │                         (detail; no nav bar)
//                                └── engine/{name}        → EngineDetailScreen
//                                                          (detail; no nav bar)
//
//   Bottom-nav tabs use popUpTo(startDestinationId) + saveState/restoreState
//   so tab switching never grows the back stack — matches marmalade-android.
//   Engines, AppMappings, and engine/{name} are detail screens (nav bar
//   hidden), reached via navigate() and dismissed with popBackStack().
// -----------------------------------------------------------------------------

/** Route identifiers for the top-level nav graph. */
object Routes {
    const val Speak = "speak"
    const val Voices = "voices"
    const val Engines = "engines"
    const val Settings = "settings"
    const val Aliases = "aliases"
    const val Effects = "effects"

    /**
     * Full-screen effect editor (E-F). Reached from the Effects tab's FAB
     * (blank create) or a card's Edit / Duplicate action. The effect id is
     * passed as an optional **query** arg, not a path segment, because ids
     * contain a colon (`builtin:cave`, `custom:<uuid>`) which a path segment
     * can't carry cleanly — see [effectEditorEdit] / [effectEditorDuplicate].
     * Leaf detail screen; the bottom nav bar hides while it's open.
     */
    const val EffectEditor = "effect_editor"

    /** Blank-create route. */
    fun effectEditorCreate(): String = EffectEditor

    /** Edit an existing custom effect (writes back to the same id). */
    fun effectEditorEdit(id: String): String =
        "$EffectEditor?editId=${Uri.encode(id)}"

    /** Duplicate any effect into a new one (the editor mints a fresh id on save). */
    fun effectEditorDuplicate(id: String): String =
        "$EffectEditor?dupeId=${Uri.encode(id)}"

    /**
     * Per-app voice routing — reached from Settings → "Per-app voices".
     * Leaf detail screen; the bottom nav bar is hidden while this is open.
     */
    const val AppMappings = "app_mappings"

    /** Detail screen for one engine. Use [engineDetail] to build the concrete route. */
    const val EngineDetail = "engine"

    /** Build the navigation route for [name]'s per-engine detail screen. */
    fun engineDetail(name: String): String = "$EngineDetail/$name"

    /**
     * Open-source licenses — reached from Settings → About → "Open-source
     * licenses". Leaf detail screen; the bottom nav bar is hidden while open.
     */
    const val Licenses = "licenses"

    /** Full license text for one component. Use [licenseText] to build the route. */
    const val LicenseText = "license_text"

    /** Build the license-text route for a component [key]. */
    fun licenseText(key: String): String = "$LicenseText/${Uri.encode(key)}"

    /**
     * Debug-only benchmark surface — measures per-engine synth timings
     * across the installed engines. Reachable from Settings only in
     * `BuildConfig.DEBUG` builds; the composable + route still exist in
     * release builds (dead code, ~5 KB) but no surface routes to it.
     */
    const val Benchmark = "benchmark"
}

/** Tabs that show in the bottom NavigationBar. Order = display order. */
private data class NavTab(
    val route: String,
    val label: String,
    val filledIcon: ImageVector,
    val outlinedIcon: ImageVector,
)

private val NAV_TABS = listOf(
    NavTab(Routes.Speak, "Speak", Icons.Filled.PlayArrow, Icons.Outlined.PlayArrow),
    NavTab(Routes.Voices, "Voices", Icons.AutoMirrored.Filled.List, Icons.AutoMirrored.Outlined.List),
    NavTab(Routes.Aliases, "Aliases", Icons.Filled.Person, Icons.Outlined.Person),
    NavTab(Routes.Effects, "Effects", Icons.Filled.Build, Icons.Outlined.Build),
    NavTab(Routes.Settings, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
)

/**
 * Top-level navigation root. Gates on onboarding state, then renders the
 * main app shell — a Scaffold with a bottom NavigationBar and a NavHost.
 *
 * Onboarding sits outside the nav graph as a pre-flight gate so the wizard
 * is never reachable via back navigation once dismissed.
 */
@Composable
fun AppRoot(viewModel: AppRootViewModel = viewModel()) {
    val onboarded by viewModel.onboarded.collectAsStateWithLifecycle(initialValue = null)

    val onboardedNow = onboarded ?: return

    if (!onboardedNow) {
        OnboardingScreen(onComplete = { /* Flow recomposes once onboarded flips */ })
        return
    }

    // Paywall host wraps the entire navigated content. F-Droid flavor's
    // ProGateHost is a no-op passthrough; the Play flavor renders a
    // ModalBottomSheet next to [content] whenever the singleton ProGate
    // flips its pending-reason. See pro/ProGate.kt.
    val ctx = LocalContext.current
    val proEntry = remember(ctx) {
        EntryPointAccessors.fromApplication(ctx.applicationContext, ProEntryPoint::class.java)
    }
    val proGate = remember(proEntry) { proEntry.proGate() }
    val proGateEntitlement = remember(proEntry) { proEntry.proEntitlement() }

    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value
        ?.destination?.route

    // Bottom bar hides on detail destinations: the per-app mappings
    // screen and the per-engine detail page (whose route is
    // "engine/<name>", so a startsWith check is the cheapest way to
    // match the whole family). Aliases used to be a detail route too;
    // promoted to a top-level tab in v0.1.18 so the nav bar stays
    // visible on that screen now.
    val showBottomBar = currentRoute != Routes.AppMappings &&
        currentRoute != Routes.Benchmark &&
        currentRoute != Routes.Engines &&
        currentRoute?.startsWith("${Routes.EngineDetail}/") != true &&
        currentRoute != Routes.Licenses &&
        currentRoute?.startsWith("${Routes.LicenseText}/") != true &&
        // The editor's route template carries query args
        // ("effect_editor?editId={editId}&dupeId={dupeId}"), so match the
        // family by prefix rather than exact string.
        currentRoute?.startsWith(Routes.EffectEditor) != true

    ProGateHost(proGate = proGate) {
    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            ) {
                NavigationBar {
                    val backStackEntry = navController.currentBackStackEntryAsState().value
                    NAV_TABS.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy?.any {
                            it.route == tab.route
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.filledIcon else tab.outlinedIcon,
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Speak,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(animationSpec = tween(150)) },
            exitTransition = { fadeOut(animationSpec = tween(150)) },
        ) {
            composable(Routes.Speak) {
                SpeakScreen(
                    onNavigateToVoices = { navController.navigateToTab(Routes.Voices) },
                    // Engines is a detail screen now (off the bottom nav), so
                    // navigate() into it rather than tab-switching.
                    onNavigateToEngines = { navController.navigate(Routes.Engines) },
                    onNavigateToAliases = { navController.navigateToTab(Routes.Aliases) },
                )
            }
            composable(Routes.Voices) {
                VoicePickerScreen(
                    onBack = { navController.navigateToTab(Routes.Speak) },
                    onVoiceSelected = { navController.navigateToTab(Routes.Speak) },
                )
            }
            composable(Routes.Engines) {
                EnginesScreen(
                    // Reached from Settings (or the Speak empty-state CTA) as a
                    // detail screen — pop back to wherever we came from.
                    onBack = { navController.popBackStack() },
                    onEngineSettings = { engine ->
                        navController.navigate(Routes.engineDetail(engine.name))
                    },
                )
            }
            composable(Routes.Settings) {
                SettingsScreen(
                    onNavigateToAppMappings = { navController.navigate(Routes.AppMappings) },
                    onNavigateToEngines = { navController.navigate(Routes.Engines) },
                    onNavigateToLicenses = { navController.navigate(Routes.Licenses) },
                    onNavigateToBenchmark = if (BuildConfig.DEBUG) {
                        { navController.navigate(Routes.Benchmark) }
                    } else {
                        null
                    },
                )
            }
            composable(Routes.Aliases) {
                // v0.1.18 promoted Aliases to a top-level tab. onBack goes
                // back to Speak (the canonical "home" tab) rather than
                // popping the back stack — there's no detail-route ancestor
                // anymore.
                AliasScreen(onBack = { navController.navigateToTab(Routes.Speak) })
            }
            composable(Routes.Effects) {
                // Custom-effect creation is Pro-gated. Editing built-in
                // presets stays free (those are stable, shipped-with-the-
                // app data, not user-created). Same precedent as the
                // per-app voices screen — see PAYWALL-PLAN.md §4.
                val isProState = proGateEntitlement.isPro.collectAsStateWithLifecycle()
                val isPro = isProState.value
                EffectsScreen(
                    onBack = { navController.navigateToTab(Routes.Speak) },
                    onCreate = {
                        if (isPro) navController.navigate(Routes.effectEditorCreate())
                        else proGate.show(PaywallReason.CustomEffect)
                    },
                    onEdit = { id -> navController.navigate(Routes.effectEditorEdit(id)) },
                    onDuplicate = { id ->
                        if (isPro) navController.navigate(Routes.effectEditorDuplicate(id))
                        else proGate.show(PaywallReason.CustomEffect)
                    },
                )
            }
            composable(
                route = "${Routes.EffectEditor}?editId={editId}&dupeId={dupeId}",
                arguments = listOf(
                    navArgument(EffectEditorViewModel.ARG_EDIT_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument(EffectEditorViewModel.ARG_DUPE_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                EffectEditorScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.AppMappings) {
                AppMappingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.Benchmark) {
                BenchmarkScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.Licenses) {
                LicensesScreen(
                    onBack = { navController.popBackStack() },
                    onViewComponentText = { key -> navController.navigate(Routes.licenseText(key)) },
                )
            }
            composable(
                route = "${Routes.LicenseText}/{key}",
                arguments = listOf(navArgument("key") { type = NavType.StringType }),
            ) { entry ->
                val key = entry.arguments?.getString("key") ?: return@composable
                LicenseTextScreen(componentKey = key, onBack = { navController.popBackStack() })
            }
            composable(
                route = "${Routes.EngineDetail}/{name}",
                arguments = listOf(navArgument("name") { type = NavType.StringType }),
            ) { entry ->
                val name = entry.arguments?.getString("name") ?: return@composable
                EngineDetailScreen(
                    engineName = name,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
    } // ProGateHost
}

/**
 * Bottom-nav-style navigation: drop everything above the start destination,
 * single-instance the target, restore prior state if any. Matches the
 * marmalade-android `MarmaladeNavHost` pattern.
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
