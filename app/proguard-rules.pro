# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.muhan.intelligence.**$$serializer { *; }
-keepclassmembers class com.muhan.intelligence.** {
    *** Companion;
}
-keepclasseswithmembers class com.muhan.intelligence.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit
-keepattributes Signature, Exceptions
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# Hilt / Dagger
-dontwarn dagger.hilt.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Tink (transitive dependency of androidx.security:security-crypto)
#
# Tink references error-prone annotations that are compile-time only, so R8
# reports them as missing classes. They are never loaded at runtime, hence
# -dontwarn is the correct fix rather than adding the dependency.
# ---------------------------------------------------------------------------
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.element.**

# Tink's optional KeysDownloader / keyset-remote support pulls in the Google
# HTTP client and Joda-Time, neither of which is bundled. Those code paths are
# unreachable because MuHan only uses the local (Android Keystore backed)
# AndroidKeysetManager, so suppressing the warnings is safe.
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# Tink resolves key managers reflectively by class name.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-keepclasseswithmembers,allowobfuscation class com.google.crypto.tink.** {
    public static final ** PROTO;
}

# androidx.security relies on these for EncryptedSharedPreferences.
-keep class androidx.security.crypto.** { *; }
