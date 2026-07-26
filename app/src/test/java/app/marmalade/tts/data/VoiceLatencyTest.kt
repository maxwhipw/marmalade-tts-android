package app.marmalade.tts.data

import org.junit.Assert.assertEquals
import org.junit.Test

// -----------------------------------------------------------------------------
// Covers the pure parts of the picker's speed badge: which bucket a measured
// median lands in, and what key a voice is tracked under. Descriptor parsing
// of the seed lives in CloudProvidersTest, which runs under Robolectric
// because org.json is a throwing stub on the plain JVM.
// -----------------------------------------------------------------------------

class VoiceLatencyTest {

    @Test
    fun `buckets split on the calibrated cut points`() {
        assertEquals(LatencyBucket.INSTANT, LatencyBucket.ofMillis(0))
        assertEquals(LatencyBucket.INSTANT, LatencyBucket.ofMillis(599))
        assertEquals(LatencyBucket.QUICK, LatencyBucket.ofMillis(600))
        assertEquals(LatencyBucket.QUICK, LatencyBucket.ofMillis(1_799))
        assertEquals(LatencyBucket.SLOW, LatencyBucket.ofMillis(1_800))
        assertEquals(LatencyBucket.SLOW, LatencyBucket.ofMillis(9_000))
    }

    @Test
    fun `a cloud voice is tracked per model, not per voice`() {
        val aria = "cloud-api-v1:venice:tts-elevenlabs-turbo-v2-5:Aria"
        val bella = "cloud-api-v1:venice:tts-elevenlabs-turbo-v2-5:Bella"
        assertEquals(
            latencyKeyFor(aria, CloudApiVoiceCatalog.ENGINE),
            latencyKeyFor(bella, CloudApiVoiceCatalog.ENGINE),
        )
        assertEquals(
            "venice:tts-elevenlabs-turbo-v2-5",
            latencyKeyFor(aria, CloudApiVoiceCatalog.ENGINE),
        )
    }

    @Test
    fun `two models under one provider get different keys`() {
        assertEquals(
            "venice:tts-kokoro",
            latencyKeyFor("cloud-api-v1:venice:tts-kokoro:af_heart", CloudApiVoiceCatalog.ENGINE),
        )
    }

    @Test
    fun `an on-device voice is tracked per engine`() {
        assertEquals(
            "kitten-direct-v0_8",
            latencyKeyFor("kitten-direct-v0_8:kiki", "kitten-direct-v0_8"),
        )
    }

    @Test
    fun `the legacy two-part cloud id still resolves to Venice Kokoro`() {
        assertEquals(
            "venice:tts-kokoro",
            latencyKeyFor("cloud-api-v1:af_heart", CloudApiVoiceCatalog.ENGINE),
        )
    }
}
