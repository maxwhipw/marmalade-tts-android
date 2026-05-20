# Add project specific ProGuard rules here.

# Sherpa-ONNX: keep all native JNI classes
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Hilt: keep generated components
-keep class dagger.hilt.** { *; }
-keep class **_HiltComponents* { *; }

# Room: keep entity and DAO classes
-keep class * extends androidx.room.RoomDatabase { *; }
