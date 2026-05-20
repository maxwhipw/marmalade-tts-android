package app.marmalade.tts.ui

import androidx.lifecycle.ViewModel
import app.marmalade.tts.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Routing-level ViewModel — surfaces the onboarded flag that [AppRoot] uses
 * to choose between the onboarding wizard and the main screen graph.
 *
 * Kept deliberately thin: a single Flow exposure. The flag is written by
 * [app.marmalade.tts.ui.onboarding.OnboardingViewModel], not here.
 */
@HiltViewModel
class AppRootViewModel @Inject constructor(
    settings: SettingsRepository,
) : ViewModel() {
    val onboarded: Flow<Boolean> = settings.onboarded
}
