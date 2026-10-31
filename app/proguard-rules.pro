# Keep JNI bridge entry points intact in release builds
-keep class org.pspp.mobile.engine.PsppBridge {
    native <methods>;
    public *;
}

# Do not obfuscate main activity or ViewModel entry points
-keep class org.pspp.mobile.MainActivity { *; }
-keep class org.pspp.mobile.viewmodel.PsppViewModel { *; }

# Allow R8 to completely strip PsppStatisticalEvaluator when BuildConfig.ALLOW_FALLBACK is false
-dontwarn org.pspp.mobile.engine.PsppStatisticalEvaluator
