package app.marmalade.tts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the in-app Open-source licenses screen against its silent
 * runtime-failure and compliance modes:
 *  1. a [LicenseCatalog.Component] referencing an unknown license id,
 *  2. a bundled text file that isn't actually present under
 *     `assets/licenses/` (the "view text" tap would fail at runtime), and
 *  3. — the important one — a component displaying a **shared** license body
 *     for a license that embeds the licensor's copyright (MIT/BSD), which
 *     would show the *wrong* copyright holder.
 *
 * All three would compile fine and only surface to a user.
 *
 * Unit tests run with the module directory (`app/`) as the working dir, so
 * the asset files are reachable on the filesystem from here.
 */
class LicenseCatalogTest {

    private val assetsDir = File("src/main/assets/licenses")

    /**
     * Licenses whose **text body contains no licensor copyright line**, so a
     * single shared body is safe to display for every component on them.
     * MIT and the BSD family are deliberately absent — their text embeds the
     * copyright, so each component must ship its own.
     */
    private val noEmbeddedCopyright =
        setOf("GPL-3.0-or-later", "Apache-2.0", "CC-BY-4.0", "CC-BY-SA-4.0", "CC0-1.0", "OFL-1.1")

    @Test
    fun everyComponentMapsToAKnownLicense() {
        for (component in LicenseCatalog.components) {
            assertNotNull(
                "Component '${component.name}' references unknown license " +
                    "'${component.licenseId}'",
                LicenseCatalog.licenseFor(component.licenseId),
            )
        }
    }

    @Test
    fun licenseAndComponentIdsAreUnique() {
        val licenseIds = LicenseCatalog.licenses.map { it.id }
        assertEquals("Duplicate license ids", licenseIds.distinct().size, licenseIds.size)
        val keys = LicenseCatalog.components.map { it.key }
        assertEquals("Duplicate component keys", keys.distinct().size, keys.size)
    }

    @Test
    fun everyBundledTextExists() {
        // Shared family bodies.
        for (license in LicenseCatalog.licenses) {
            val asset = license.sharedAsset ?: continue
            assertBundled(license.id, asset)
        }
        // Per-component exact texts.
        for (component in LicenseCatalog.components) {
            val asset = component.textAsset ?: continue
            assertBundled(component.name, asset)
        }
    }

    @Test
    fun everyComponentHasAttribution() {
        for (component in LicenseCatalog.components) {
            assertTrue(
                "Component '${component.name}' has no copyright/attribution line",
                component.copyright.isNotEmpty(),
            )
        }
    }

    /**
     * The compliance invariant: a component may only fall back to a license's
     * *shared* body when that license carries no embedded copyright. Otherwise
     * it must supply its own [LicenseCatalog.Component.textAsset] with the
     * correct holder.
     */
    @Test
    fun noComponentShowsASharedBodyThatEmbedsCopyright() {
        for (component in LicenseCatalog.components) {
            val usesSharedBody = component.textAsset == null &&
                LicenseCatalog.licenseFor(component.licenseId)?.sharedAsset != null
            if (usesSharedBody) {
                assertTrue(
                    "Component '${component.name}' would display the shared " +
                        "'${component.licenseId}' body, but that license embeds a " +
                        "copyright holder — give it a per-component textAsset.",
                    component.licenseId in noEmbeddedCopyright,
                )
            }
        }
    }

    @Test
    fun groupingCoversEveryComponent() {
        val grouped = LicenseCatalog.groupedByLicense()
        val groupedCount = grouped.sumOf { (_, members) -> members.size }
        assertEquals(
            "groupedByLicense() dropped or duplicated components",
            LicenseCatalog.components.size,
            groupedCount,
        )
    }

    @Test
    fun everyComponentShipsABundledText() {
        // Every component's full license text must be readable offline from the
        // APK — none may be online-only. (The license URLs remain only as the
        // "view online" affordance / metadata.)
        for (component in LicenseCatalog.components) {
            assertNotNull(
                "Component '${component.name}' has no bundled license text — " +
                    "all license texts must ship in the APK, not online-only.",
                component.resolvedAsset(),
            )
        }
    }

    private fun assertBundled(owner: String, asset: String) {
        val file = File(assetsDir, asset)
        assertTrue("Bundled license text missing for '$owner': ${file.path}", file.isFile)
        assertTrue("Bundled license text empty for '$owner': ${file.path}", file.length() > 0)
    }
}
