package app.marmalade.tts

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Verifies that the Application class exists and can be referenced.
 *
 * This is a pure JVM test — no Android framework required. The Hilt
 * @HiltAndroidApp annotation is a compile-time code-gen annotation;
 * the class itself is valid to reference in a JVM context.
 */
class ApplicationTest {

    @Test
    fun applicationClassExists() {
        // Confirm the class can be loaded — this would fail if Hilt
        // code-gen produced a broken class or the package was wrong.
        val clazz = MarmaladeTtsApplication::class.java
        assertNotNull("MarmaladeTtsApplication class should exist", clazz)
        assertNotNull("Class name should be non-null", clazz.name)
    }
}
