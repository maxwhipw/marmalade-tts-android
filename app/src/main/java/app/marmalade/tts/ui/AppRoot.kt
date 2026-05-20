package app.marmalade.tts.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.marmalade.tts.ui.onboarding.OnboardingScreen
import app.marmalade.tts.ui.screen.EnginesScreen
import app.marmalade.tts.ui.screen.SpeakScreen
import app.marmalade.tts.ui.screen.VoicePickerScreen

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   MainActivity → AppRoot()
//                    │
//                    ├── rememberActivityViewModel<AppRootViewModel>()
//                    │     └── exposes SettingsRepository.onboarded
//                    │
//                    ├── if (!onboarded) ─► OnboardingScreen(onComplete)
//                    │       └── on complete → onboarded flips to true (DataStore)
//                    │
//                    └── else: rememberSaveable<Route>
//                              │
//                              ▼
//                          AnimatedContent switch on `current`
//                              ├── Route.Speak   → SpeakScreen(onNavigateToVoices, onNavigateToEngines)
//                              ├── Route.Voices  → VoicePickerScreen(onBack, onVoiceSelected)
//                              └── Route.Engines → EnginesScreen(onBack)
//
//   System back press on Voices/Engines ─► BackHandler → current = Speak
// -----------------------------------------------------------------------------

/**
 * Top-level navigation root. Gates on onboarding state, then routes to
 * the appropriate screen.
 *
 * Hand-rolled `AnimatedContent` swap instead of `navigation-compose` —
 * see STUBS.md for why and what changes when the dependency lands.
 */
@Composable
fun AppRoot(viewModel: AppRootViewModel = rememberActivityViewModel()) {
    val onboarded by viewModel.onboarded.collectAsStateWithLifecycle(initialValue = null)

    // While we're still loading the onboarded flag, render nothing.
    // DataStore reads are fast — this is typically a single frame.
    val onboardedNow = onboarded ?: return

    if (!onboardedNow) {
        OnboardingScreen(
            onComplete = {
                // The VM has already flipped `onboarded` to true; the next
                // recomposition will pick that up via the Flow and route
                // through the else branch.
            },
        )
        return
    }

    var current: Route by rememberSaveable(stateSaver = RouteSaver) {
        mutableStateOf(Route.Speak)
    }

    // System back from non-root destinations returns to Speak. On Speak
    // itself, back falls through to the platform default (exits the activity).
    BackHandler(enabled = current != Route.Speak) {
        current = Route.Speak
    }

    AnimatedContent(
        targetState = current,
        label = "AppRootNav",
        transitionSpec = {
            (fadeIn(animationSpec = androidx.compose.animation.core.tween(150))
                togetherWith fadeOut(animationSpec = androidx.compose.animation.core.tween(150)))
        },
    ) { route ->
        when (route) {
            Route.Speak -> SpeakScreen(
                onNavigateToVoices = { current = Route.Voices },
                onNavigateToEngines = { current = Route.Engines },
            )
            Route.Voices -> VoicePickerScreen(
                onBack = { current = Route.Speak },
                onVoiceSelected = { current = Route.Speak },
            )
            Route.Engines -> EnginesScreen(
                onBack = { current = Route.Speak },
            )
        }
    }
}

/** The destinations this app knows about. */
enum class Route { Speak, Voices, Engines }

/**
 * Saver for [Route] — `rememberSaveable` needs a Bundle-compatible
 * representation; storing the enum's name string is the simplest stable
 * form (Bundle survives across process death; ordinals are not stable
 * across versions, names are).
 */
private val RouteSaver: Saver<Route, String> = Saver(
    save = { it.name },
    restore = { name -> Route.entries.firstOrNull { it.name == name } ?: Route.Speak },
)
