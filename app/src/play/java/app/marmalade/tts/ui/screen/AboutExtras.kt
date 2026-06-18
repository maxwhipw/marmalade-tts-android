package app.marmalade.tts.ui.screen

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import app.marmalade.tts.pro.ProEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

/**
 * Play flavor: Settings → About → "Restore purchases". Required by
 * Play policy (the paywall sheet's Restore button isn't enough — users
 * who already purchased and reinstalled, or who dismissed the sheet,
 * need a second affordance). Calls
 * [app.marmalade.tts.pro.ProEntitlement.restorePurchases] and toasts
 * the result so the user knows whether anything changed.
 *
 * No donate link — that's F-Droid-only per
 * docs/release/PAYWALL-PLAN.md.
 */
@Composable
internal fun AboutExtras() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val entitlement = remember(ctx) {
        EntryPointAccessors
            .fromApplication(ctx.applicationContext, ProEntryPoint::class.java)
            .proEntitlement()
    }
    ListItem(
        modifier = Modifier.clickable {
            scope.launch {
                val before = entitlement.isPro.value
                entitlement.restorePurchases()
                val after = entitlement.isPro.value
                val msg = when {
                    after && !before -> "Marmalade Pro restored."
                    after -> "Marmalade Pro is active on this device."
                    else -> "No Pro purchase found for this Google account."
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        },
        headlineContent = { Text("Restore purchases") },
        supportingContent = {
            Text("Re-check this Google account for a Marmalade Pro purchase.")
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}
