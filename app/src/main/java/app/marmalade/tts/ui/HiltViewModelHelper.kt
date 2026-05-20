package app.marmalade.tts.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   Composable
//     │
//     ▼
//   rememberActivityViewModel<VM>()
//     │
//     ├── LocalContext.current  ──► ComponentActivity (host of the Compose tree)
//     │
//     └── ViewModelProvider(
//             store   = activity.viewModelStore,
//             factory = activity.defaultViewModelProviderFactory,  // HiltViewModelFactory
//           ).get(VM::class.java)
// -----------------------------------------------------------------------------

/**
 * Activity-scoped ViewModel lookup that doesn't require the
 * `androidx.lifecycle:lifecycle-viewmodel-compose` artifact.
 *
 * Why the indirection: that artifact's `viewModel<T>()` composable isn't
 * available offline in this build environment, and adding it requires a
 * build-file edit that's outside this agent's scope (see STUBS.md). The
 * helper below does the same lookup by hand — go up to the hosting
 * Activity, use its `viewModelStore` and `defaultViewModelProviderFactory`,
 * and resolve through a plain `ViewModelProvider`.
 *
 * Because `MainActivity` is `@AndroidEntryPoint`, Hilt has overridden
 * `defaultViewModelProviderFactory` with `HiltViewModelFactory`, so the
 * resolved ViewModel arrives fully Hilt-injected.
 *
 * `remember` caches the instance per-composition, so repeated reads
 * (during recomposition) don't go through ViewModelProvider every time —
 * matching the behaviour of the upstream `viewModel()` composable.
 */
@Composable
inline fun <reified VM : ViewModel> rememberActivityViewModel(): VM {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
        ?: error(
            "rememberActivityViewModel() must be hosted in a ComponentActivity; " +
                "got ${context.javaClass.name}",
        )
    return remember(activity) {
        ViewModelProvider(
            activity.viewModelStore,
            activity.defaultViewModelProviderFactory,
        )[VM::class.java]
    }
}
