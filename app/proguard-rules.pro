# Keep Vosk classes (JNA bridge — must not be stripped)
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }

# Keep MediaPipe LLM Inference classes
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Keep our own classes in case minify is enabled
-keep class com.example.voiceassistant.** { *; }

# JSON (org.json is included in Android runtime but keep for clarity)
-keep class org.json.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
