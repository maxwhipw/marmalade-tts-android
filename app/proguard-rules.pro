# Add project specific ProGuard rules here.

# ONNX Runtime: libonnxruntime4j_jni.so makes JNI upcalls into these classes
# by name (e.g. OrtException construction); R8 renaming/stripping them crashes
# native inference. The AAR ships no consumer proguard rules of its own.
-keep class ai.onnxruntime.** { *; }

# commons-compress references optional codec backends we don't ship (XZ, zstd,
# brotli, OSGi). R8 fails the build on the missing classes without these.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.osgi.**
-dontwarn org.objectweb.asm.**

# Hilt: keep generated components
-keep class dagger.hilt.** { *; }
-keep class **_HiltComponents* { *; }

# Room: keep entity and DAO classes
-keep class * extends androidx.room.RoomDatabase { *; }

# Strip debug/verbose logging from RELEASE builds (R8 removes these calls and
# their argument computation) — this also drops the per-chunk timing logs and
# any line that logged user text (KokoroDirect/Kitten/Pocket). Info/warn/error
# are kept for crash diagnostics.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}
