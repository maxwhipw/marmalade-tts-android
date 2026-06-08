# Add project specific ProGuard rules here.

# Sherpa-ONNX: keep all native JNI classes
-keep class com.k2fsa.sherpa.onnx.** { *; }

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
