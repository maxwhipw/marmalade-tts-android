package app.marmalade.tts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.marmalade.tts.pro.ProEntitlement
import app.marmalade.tts.ui.AppRoot
import app.marmalade.tts.ui.AppRootViewModel
import app.marmalade.tts.ui.theme.MarmaladeTtsTheme
import app.marmalade.tts.ui.theme.ThemePreset
import app.marmalade.tts.ui.theme.resolveThemeIsDark
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Single Activity host for the Compose UI. `@AndroidEntryPoint` plumbs
 * Hilt through to every `hiltViewModel()` call in the screen composables,
 * and to the activity-scoped `viewModel<AppRootViewModel>()` we use to
 * read the theme preset + dark-mode override before any screen renders.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var proEntitlement: ProEntitlement

    /**
     * Re-verify the Pro entitlement every time the app comes to the
     * foreground. This is the "onResume hook" the entitlement contract
     * depends on: it retries a failed post-purchase acknowledge before
     * Play's 3-day auto-refund window closes, and it notices refunds
     * without waiting out the 30-day offline-cache TTL. No-op on
     * F-Droid, cheap on Play (local Play Store query).
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { proEntitlement.restorePurchases() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val rootVm: AppRootViewModel = viewModel()
            val preset by rootVm.themePreset.collectAsStateWithLifecycle(
                initialValue = ThemePreset.MARMALADE,
            )
            val themeMode by rootVm.themeMode.collectAsStateWithLifecycle(initialValue = "system")
            val systemDark = isSystemInDarkTheme()
            val darkTheme = resolveThemeIsDark(themeMode, systemDark)
            MarmaladeTtsTheme(darkTheme = darkTheme, themePreset = preset) {
                AppRoot(viewModel = rootVm)
            }
        }
    }
}
