package app.marmalade.tts.pro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

// Hilt requires an Activity-scoped entry-point to reach the singletons
// from Compose without a viewmodel. We use the parent Activity (the app's
// MainActivity) by walking up the context chain — see [findActivity].
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import dagger.hilt.android.EntryPointAccessors

/**
 * Play flavor: wraps [content] in a Box so the paywall ModalBottomSheet
 * can render alongside it when [PlayProGate.pending] flips non-null.
 * The sheet handles purchase + restore by calling
 * [ProEntitlement.launchPurchase] / [ProEntitlement.restorePurchases]
 * resolved via Hilt's entry-point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProGateHost(
    proGate: ProGate,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    // [proGate] is the cross-flavor [ProGate] interface; cast to the
    // Play impl to read the pending-paywall signal it owns. Safe: this
    // composable only exists in the Play flavor, so the only ProGate
    // binding it can ever see is PlayProGate.
    val play = proGate as PlayProGate
    val entryPoint = remember(ctx) {
        EntryPointAccessors.fromApplication(ctx.applicationContext, ProEntryPoint::class.java)
    }
    val entitlement = remember(entryPoint) { entryPoint.proEntitlement() }
    val pending by play.pending.collectAsStateWithLifecycle()
    val isPro by entitlement.isPro.collectAsStateWithLifecycle()

    Box {
        content()

        if (pending != null && !isPro) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val scope = rememberCoroutineScope()
            var purchaseError by remember { mutableStateOf<String?>(null) }
            var purchaseInFlight by remember { mutableStateOf(false) }

            ModalBottomSheet(
                onDismissRequest = { play.dismiss() },
                sheetState = sheetState,
            ) {
                Column(
                    // verticalScroll keeps the sheet usable at large
                    // system font scales (1.5x+) on small minSdk=28
                    // devices where the content would otherwise clip
                    // the bottom buttons.
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Marmalade Pro",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = reasonHeadline(pending!!),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Marmalade is open source and free everywhere. Pro " +
                            "supports development and unlocks per-app voice routing " +
                            "and custom audio effects. One purchase, lifetime.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        // "available" not "accessible" — screen-reader
                        // users may parse "accessible" as a11y.
                        text = "Already available without Pro — your primary voice " +
                            "is used by every app automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    purchaseError?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !purchaseInFlight && activity != null,
                        onClick = {
                            val a = activity ?: return@Button
                            scope.launch {
                                purchaseInFlight = true
                                purchaseError = null
                                val result = entitlement.launchPurchase(a)
                                purchaseInFlight = false
                                when (result) {
                                    is PurchaseResult.Success -> play.dismiss()
                                    is PurchaseResult.Pending -> {
                                        purchaseError =
                                            "Payment is pending Play approval — you'll get " +
                                            "Pro automatically once it clears (usually within " +
                                            "a few minutes; gift-card balance checks can take longer)."
                                    }
                                    is PurchaseResult.Cancelled -> { /* leave sheet open */ }
                                    is PurchaseResult.Error -> purchaseError = result.message
                                    is PurchaseResult.NotApplicable -> play.dismiss()
                                }
                            }
                        },
                    ) {
                        Text(if (purchaseInFlight) "Opening Play…" else "Upgrade to Pro")
                    }
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !purchaseInFlight,
                        onClick = {
                            scope.launch {
                                val before = entitlement.isPro.value
                                entitlement.restorePurchases()
                                val after = entitlement.isPro.value
                                if (after && !before) {
                                    play.dismiss()
                                } else if (after) {
                                    // Already restored — close out the sheet.
                                    play.dismiss()
                                } else {
                                    purchaseError =
                                        "No Marmalade Pro purchase found on this Google account."
                                }
                            }
                        },
                    ) {
                        Text("Restore purchases")
                    }
                }
            }
        }
    }
}

private fun reasonHeadline(reason: PaywallReason): String = when (reason) {
    PaywallReason.PerAppVoice ->
        "Pick a unique voice persona for every app that asks Marmalade to speak."
    PaywallReason.CustomEffect ->
        "Create and save your own audio effect blocks beyond the built-in presets."
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
