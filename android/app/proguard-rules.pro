# Release shrinking (R8). Code that is not reachable is removed; names are kept, so a stack trace
# from a release build reads like the source. Library-specific rules come from the modules
# themselves (see android/engine/consumer-rules.pro for JNA and the UniFFI bindings).
-dontobfuscate
