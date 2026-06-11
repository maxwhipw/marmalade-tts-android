package app.marmalade.tts.ui.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.marmalade.tts.data.LicenseCatalog

// -----------------------------------------------------------------------------
// Open-source licenses
// -----------------------------------------------------------------------------
//   Two detail screens, both reached from Settings → About → "Open-source
//   licenses" (AppRoot hides the bottom nav bar on both):
//
//     LicensesScreen     — licensing posture + components grouped by license,
//                          each row showing its exact copyright holder. A row
//                          opens the component's license text (bundled) or, for
//                          bundle-only items we don't ship the text for, the
//                          upstream URL in a browser.
//     LicenseTextScreen  — one component's full license text. For MIT/BSD the
//                          bundled file already embeds the copyright; for the
//                          shared GPL/Apache/CC bodies the component's
//                          attribution is shown above the body.
//
//   Content comes from [LicenseCatalog], which mirrors NOTICE.md / LICENSES/.
// -----------------------------------------------------------------------------

/**
 * Settings → About → Open-source licenses. Lists every third-party component
 * Marmalade ships or downloads, grouped by license, each with its correct
 * copyright holder — and fronts the app's licensing posture: MIT source and
 * APK, with GPL espeak-ng only in opt-in downloaded engine bundles.
 *
 * Detail screen; the bottom nav bar is hidden by [app.marmalade.tts.ui.AppRoot]
 * while this is the current destination.
 *
 * @param onViewComponentText navigates to [LicenseTextScreen] for a component
 *        whose license text is bundled. Components without a bundled text open
 *        their license's canonical URL in a browser instead (handled here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    onViewComponentText: (componentKey: String) -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        // Nested-Scaffold inset handoff — AppRoot's outer Scaffold owns
        // status-bar insets; opt this inner Scaffold + its TopAppBar out so
        // the bar doesn't double-pad itself. See SpeakScreen for the note.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Open-source licenses") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            PostureSection(onOpenSource = {
                openUrl(context, LicenseCatalog.POSTURE.CORRESPONDING_SOURCE_URL)
            })

            LicenseCatalog.groupedByLicense().forEach { (license, members) ->
                HorizontalDivider()
                SectionHeader(license.id)
                members.forEach { component ->
                    ComponentRow(
                        component = component,
                        onClick = {
                            if (component.resolvedAsset() != null) {
                                onViewComponentText(component.key)
                            } else {
                                openUrl(context, license.url)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PostureSection(onOpenSource: () -> Unit) {
    SectionHeader("Licensing")

    Text(
        text = LicenseCatalog.POSTURE.SOURCE,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Text(
        text = LicenseCatalog.POSTURE.BINARY,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    ListItem(
        modifier = Modifier.clickable(onClick = onOpenSource),
        headlineContent = { Text(LicenseCatalog.POSTURE.CORRESPONDING_SOURCE_LABEL) },
        supportingContent = { Text(LicenseCatalog.POSTURE.CORRESPONDING_SOURCE_URL) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun ComponentRow(
    component: LicenseCatalog.Component,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(component.name) },
        supportingContent = {
            Column {
                Text("${component.role} · ${component.shipsIn}")
                component.copyright.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                component.note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "View full license",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

/**
 * Full license text for one component. Monospace + selectable so a user (or a
 * license auditor) can read or copy the verbatim text. For MIT/BSD the bundled
 * file already embeds the copyright line; for the shared GPL/Apache/CC bodies
 * the component's attribution is shown above the body so it isn't lost.
 *
 * Detail screen; the bottom nav bar is hidden while open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseTextScreen(
    componentKey: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val component = remember(componentKey) { LicenseCatalog.componentFor(componentKey) }
    val body = remember(componentKey) {
        val asset = component?.resolvedAsset()
        if (asset == null) {
            "License text is not bundled. See the link on the previous screen."
        } else {
            try {
                context.assets.open("licenses/$asset").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.e("LicenseTextScreen", "Failed to read licenses/$asset", e)
                "Could not load the license text. See the upstream link on the previous screen."
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(component?.licenseId ?: "License") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            component?.let { Text(it.name, style = MaterialTheme.typography.titleMedium) }
            // Shared GPL/Apache/CC bodies carry no licensor copyright, so show
            // the component's attribution above them. MIT/BSD files already
            // embed it — don't duplicate.
            if (component != null && !component.bodyEmbedsCopyright()) {
                Spacer(Modifier.height(4.dp))
                component.copyright.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Section label — same style as the Settings screen's. Kept local (it's
 * file-private there) rather than coupling the two screens; matches the
 * codebase's "spell small helpers out locally" idiom.
 */
@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Open [url] in the user's browser; no-op (logged) if nothing can handle it. */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (e: ActivityNotFoundException) {
        Log.w("LicensesScreen", "No browser to open $url", e)
    }
}
