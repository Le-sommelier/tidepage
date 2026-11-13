# The UniFFI bindings call libpdfa_ffi.so through JNA. JNA finds structures, fields, callbacks and
# its own native peers by reflection and by name from native code, so none of it may be renamed,
# stripped or optimised away.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.pdfa_ffi.** { *; }
-dontwarn java.awt.**
