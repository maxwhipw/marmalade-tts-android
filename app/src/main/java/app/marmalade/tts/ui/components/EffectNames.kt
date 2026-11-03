package app.marmalade.tts.ui.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.marmalade.tts.data.BuiltinEffects
import java.text.Collator
import java.util.Locale

/**
 * The name to show for the effect [id]: a built-in's from string resources,
 * so it follows the app language, a custom effect's [storedName] as the user
 * typed it (#118 UI-1). The DB keeps built-ins' English names.
 */
fun effectDisplayName(resources: Resources, id: String, storedName: String): String =
    BuiltinEffects.nameRes(id)?.let(resources::getString) ?: storedName

/** [effectDisplayName] for composables. */
@Composable
fun effectDisplayName(id: String, storedName: String): String =
    BuiltinEffects.nameRes(id)?.let { stringResource(it) } ?: storedName

/**
 * [effects] in list order: custom effects first, in the DAO's order, then the
 * built-ins alphabetically by [displayName] in [locale]. The DAO sorts
 * built-ins by their English names, which reads as no order at all once they
 * show localized.
 */
fun <T> effectDisplayOrder(
    effects: List<T>,
    locale: Locale,
    isBuiltin: (T) -> Boolean,
    displayName: (T) -> String,
): List<T> {
    val (builtins, custom) = effects.partition(isBuiltin)
    return custom + builtins.sortedWith(compareBy(Collator.getInstance(locale), displayName))
}
