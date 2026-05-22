package app.marmalade.tts.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Build
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.marmalade.tts.ui.onboarding.OnboardingScreen
import app.marmalade.tts.ui.screen.AliasScreen
import app.marmalade.tts.ui.screen.EnginesScreen
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
//                                ├── Routes.Speak    → SpeakScreen
//                                ├── Routes.Voices   → VoicePickerScreen
//                                ├── Routes.Engines  → EnginesScreen
//                                ├── Routes.Settings → SettingsScreen
//                                └── Routes.Aliases  → AliasScreen (no nav bar)
//
//   Bottom-nav tabs use popUpTo(startDestinationId) + saveState/restoreState
//   so tab switching never grows the back stack — matches marmalade-android.
//   Aliases is reachable from Settings; it's a detail screen (nav bar hidden).
// -----------------------------------------------------------------------------

/** Route identifiers for the top-level nav graph. */
object Routes {
    const val Speak = "speak"
    const val Voices = "voices"
    const val Engines = "engines"
    const val Settings = "settings"
    const val Aliases = "aliases"
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
    NavTab(Routes.Engines, "Engines", Icons.Filled.Build, Icons.Outlined.Build),
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

    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value
        ?.destination?.route

    // Bottom bar hides on detail destinations (currently just Aliases).
    val showBottomBar = currentRoute != Routes.Aliases

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
                    onNavigateToEngines = { navController.navigateToTab(Routes.Engines) },
                    onNavigateToAliases = { navController.navigate(Routes.Aliases) },
                )
            }
            composable(Routes.Voices) {
                VoicePickerScreen(
                    onBack = { navController.navigateToTab(Routes.Speak) },
                    onVoiceSelected = { navController.navigateToTab(Routes.Speak) },
                )
            }
            composable(Routes.Engines) {
                EnginesScreen(onBack = { navController.navigateToTab(Routes.Speak) })
            }
            composable(Routes.Settings) {
                SettingsScreen(
                    onNavigateToAliases = { navController.navigate(Routes.Aliases) },
                )
            }
            composable(Routes.Aliases) {
                AliasScreen(onBack = { navController.popBackStack() })
            }
        }
    }
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
