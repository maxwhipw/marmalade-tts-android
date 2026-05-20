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
import app.marmalade.tts.ui.screen.SpeakScreen
import app.marmalade.tts.ui.screen.VoicePickerScreen

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   MainActivity → AppRoot()
//                    │
//                    ├── rememberSaveable<Route>   (survives rotation)
//                    │
//                    └── AnimatedContent switch on `current`
//                          │
//                          ├── Route.Speak  ─► SpeakScreen(onNavigateToVoices)
//                          │                       └── onNavigateToVoices ─► current = Voices
//                          │
//                          └── Route.Voices ─► VoicePickerScreen(
//                                                  onBack         = current = Speak,
//                                                  onVoiceSelected = current = Speak)
//
//   System back press on Voices ─► BackHandler → current = Speak
// -----------------------------------------------------------------------------

/**
 * Two-destination navigation root.
 *
 * Hand-rolled `AnimatedContent` swap instead of `androidx.navigation:navigation-compose`
 * because the dependency wasn't available offline at build time, and adding it
 * requires a build-system file edit that's outside this agent's scope (see
 * STUBS.md). For two screens this is honestly fine — it's roughly the same
 * lines of code, and `rememberSaveable` carries the current route through
 * rotation cleanly.
 *
 * When `navigation-compose` does land:
 *   - Replace this composable with a `NavHost` rooted at `"speak"`.
 *   - `onNavigateToVoices` → `navController.navigate("voices")`.
 *   - `onBack` / `onVoiceSelected` → `navController.popBackStack()`.
 *   - Keep the same screen signatures so the swap is contained here.
 */
@Composable
fun AppRoot() {
    var current: Route by rememberSaveable(stateSaver = RouteSaver) {
        mutableStateOf(Route.Speak)
    }

    // System-back from the voice picker returns to Speak. On Speak itself,
    // back falls through to the platform default (exits the activity).
    BackHandler(enabled = current == Route.Voices) {
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
            )
            Route.Voices -> VoicePickerScreen(
                onBack = { current = Route.Speak },
                onVoiceSelected = { current = Route.Speak },
            )
        }
    }
}

/** The two destinations this app knows about. */
enum class Route { Speak, Voices }

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
