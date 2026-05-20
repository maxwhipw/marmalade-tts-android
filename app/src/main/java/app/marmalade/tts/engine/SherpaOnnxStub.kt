package app.marmalade.tts.engine

// Compile-time check that the Sherpa-ONNX AAR is wired in correctly.
// This import resolves against the vendored AAR in app/libs/.
// Actual engine integration (KittenEngine, PiperEngine, etc.) is v0.1+ work.
import com.k2fsa.sherpa.onnx.OfflineTtsConfig

/**
 * Placeholder that exists solely to verify the Sherpa-ONNX AAR is on the
 * compile classpath. Will be replaced by real engine implementations.
 *
 * DO NOT instantiate OfflineTtsConfig here — native libraries are not
 * present in the JVM unit-test environment. The import is sufficient.
 */
@Suppress("UnusedImport")
internal object SherpaOnnxStub {
    // OfflineTtsConfig is referenced by name to prevent the import being
    // removed by lint/IDE "optimize imports" while keeping this file no-op.
    val configClass: Class<OfflineTtsConfig> = OfflineTtsConfig::class.java
}
