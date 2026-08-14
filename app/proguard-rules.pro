# MindfulHome ProGuard rules

# Keep Room entities
-keep class com.mindfulhome.data.** { *; }

# Keep LM Playground AIDL stubs and client SDK
-keep class com.druk.lmplayground.api.** { *; }

# Credential Manager loads the Play Services provider by class name from manifest metadata.
-keep class androidx.credentials.playservices.CredentialProviderPlayServicesImpl { *; }
-keep class androidx.credentials.playservices.CredentialProviderMetadataHolder { *; }
