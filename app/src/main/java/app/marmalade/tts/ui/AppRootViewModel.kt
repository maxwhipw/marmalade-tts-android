package app.marmalade.tts.ui

import androidx.lifecycle.ViewModel
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.ui.theme.ThemePreset
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Routing-level ViewModel — surfaces the onboarded flag that [AppRoot] uses
 * to choose between the onboarding wizard and the main screen graph, plus
 * the user's theme preset so [MainActivity] can apply it before any
 * composable renders.
 *
 * The onboarded flag is written by [app.marmalade.tts.ui.onboarding.OnboardingViewModel];
 * the theme preset is written by SettingsViewModel.
 */
@HiltViewModel
class AppRootViewModel @Inject constructor(
    settings: SettingsRepository,
) : ViewModel() {
    val onboarded: Flow<Boolean> = settings.onboarded
    val themePreset: Flow<ThemePreset> = settings.themePreset.map { ThemePreset.fromString(it) }
}
