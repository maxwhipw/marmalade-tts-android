package app.marmalade.tts.ui.components

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.data.BuiltinEffects
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// -----------------------------------------------------------------------------
// #118 UI-1: built-in effect names come from string resources keyed by id, so
// they follow the app language; custom effects keep the name the user typed.
// Robolectric for the merged resources and BuiltinEffects.seedRows (org.json).
// -----------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
class EffectNamesTest {

    private val resources: Resources
        get() = ApplicationProvider.getApplicationContext<android.app.Application>().resources

    @Test
    fun `every seeded built-in has a name resource`() {
        for (id in BuiltinEffects.seedIds) {
            assertNotNull("no name resource for $id", BuiltinEffects.nameRes(id))
        }
    }

    @Test
    fun `in English a built-in shows its seeded name`() {
        for (row in BuiltinEffects.seedRows) {
            assertEquals(row.name, effectDisplayName(resources, row.id, row.name))
        }
    }

    @Test
    @Config(qualifiers = "es")
    fun `in Spanish a built-in shows the Spanish name`() {
        assertEquals("Cueva", effectDisplayName(resources, BuiltinEffects.CAVE_ID, "Cave"))
        assertEquals("Audiolibro", effectDisplayName(resources, BuiltinEffects.AUDIOBOOK_ID, "Audiobook"))
    }

    @Test
    @Config(qualifiers = "es")
    fun `a custom effect keeps its own name`() {
        assertEquals("Cave", effectDisplayName(resources, "custom:abc", "Cave"))
    }

    @Test
    fun `custom effects stay first in order, built-ins sort by shown name`() {
        data class Row(val id: String, val builtin: Boolean, val shown: String)
        val rows = listOf(
            Row("custom:2", false, "Zeta"),
            Row("custom:1", false, "Alpha"),
            Row(BuiltinEffects.CAVE_ID, true, "Cueva"),
            Row(BuiltinEffects.AUDIOBOOK_ID, true, "Audiolibro"),
            Row(BuiltinEffects.ETHEREAL_ID, true, "Etéreo"),
            Row(BuiltinEffects.EIGHT_BIT_ID, true, "8 bits"),
        )
        val ordered = effectDisplayOrder(rows, Locale.forLanguageTag("es"), Row::builtin, Row::shown)
        assertEquals(
            listOf("Zeta", "Alpha", "8 bits", "Audiolibro", "Cueva", "Etéreo"),
            ordered.map { it.shown },
        )
    }
}
