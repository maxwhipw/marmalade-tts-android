package app.marmalade.tts.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.marmalade.tts.ui.onboarding.OnboardingScreen
import app.marmalade.tts.ui.screen.AliasScreen
import app.marmalade.tts.ui.screen.EnginesScreen
import app.marmalade.tts.ui.screen.SpeakScreen
import app.marmalade.tts.ui.screen.VoicePickerScreen

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   MainActivity → AppRoot()
//                    │
//                    ├── viewModel<AppRootViewModel>() — activity-scoped via
//                    │     MainActivity's HiltViewModelFactory. NOT hiltViewModel(),
//                    │     which crashes outside a NavBackStackEntry (missing
//                    │     SAVED_STATE_REGISTRY_OWNER_KEY in CreationExtras).
//                    │     (no NavBackStackEntry above us — falls back to the
//                    │      Activity ViewModelStore, which is fine because
//                    │      MainActivity is @AndroidEntryPoint and supplies a
//                    │      HiltViewModelFactory)
//                    │     └── exposes SettingsRepository.onboarded
//                    │
//                    ├── if (!onboarded) ─► OnboardingScreen(onComplete)
//                    │       └── on complete → onboarded flips to true (DataStore)
//                    │
//                    └── else: rememberNavController() + NavHost
//                              │
//                              ▼
//                          NavHost(startDestination = Routes.Speak)
//                              ├── Routes.Speak   → SpeakScreen(navigate→Voices/Engines/Aliases)
//                              ├── Routes.Voices  → VoicePickerScreen(popBackStack, popBackStack)
//                              ├── Routes.Engines → EnginesScreen(popBackStack)
//                              └── Routes.Aliases → AliasScreen(popBackStack)
//
//   System back press on non-Speak destinations is wired through NavController
//   automatically; popping the last entry exits the activity by default.
//   The NavController is rememberSaveable internally, so the back stack
//   survives process death.
// -----------------------------------------------------------------------------

/** Route identifiers for the top-level nav graph. */
object Routes {
    const val Speak = "speak"
    const val Voices = "voices"
    const val Engines = "engines"
    const val Aliases = "aliases"
}

/**
 * Top-level navigation root. Gates on onboarding state, then hands off to
 * a `NavHost` rooted at [Routes.Speak].
 *
 * The onboarding wizard sits *outside* the nav graph as a pre-flight gate —
 * once `SettingsRepository.onboarded` flips to true the user enters the graph
 * and never sees the wizard again unless they explicitly reset.
 */
@Composable
fun AppRoot(viewModel: AppRootViewModel = viewModel()) {
    val onboarded by viewModel.onboarded.collectAsStateWithLifecycle(initialValue = null)

    // While we're still loading the onboarded flag, render nothing.
    // DataStore reads are fast — this is typically a single frame.
    val onboardedNow = onboarded ?: return

    if (!onboardedNow) {
        OnboardingScreen(
            onComplete = {
                // The VM has already flipped `onboarded` to true; the next
                // recomposition will pick that up via the Flow and route
                // through the NavHost branch.
            },
        )
        return
    }

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.Speak,
        // Keep the same 150ms cross-fade the hand-rolled router used.
        enterTransition = { fadeIn(animationSpec = tween(150)) },
        exitTransition = { fadeOut(animationSpec = tween(150)) },
    ) {
        composable(Routes.Speak) {
            SpeakScreen(
                onNavigateToVoices = { navController.navigate(Routes.Voices) },
                onNavigateToEngines = { navController.navigate(Routes.Engines) },
                onNavigateToAliases = { navController.navigate(Routes.Aliases) },
            )
        }
        composable(Routes.Voices) {
            VoicePickerScreen(
                // popBackStack over navigateUp: no app-bar nav icon to honour
                // "up" semantics, and we always want to drop the current entry.
                onBack = { navController.popBackStack() },
                onVoiceSelected = { navController.popBackStack() },
            )
        }
        composable(Routes.Engines) {
            EnginesScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.Aliases) {
            AliasScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
