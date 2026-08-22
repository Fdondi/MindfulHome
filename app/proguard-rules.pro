# MindfulHome ProGuard rules

# Keep Room entities
-keep class com.mindfulhome.data.** { *; }

# Keep LM Playground AIDL stubs and client SDK
-keep class com.druk.lmplayground.api.** { *; }
