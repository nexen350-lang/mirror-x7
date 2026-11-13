# Keep the scrcpy protocol classes readable in crash reports: they are the pieces most likely to
# need diagnosing from a user-supplied stack trace.
-keepnames class com.scrcpy.remote.scrcpy.** { *; }

# Strip verbose logging from release builds. Warnings and errors are kept.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# kotlinx.serialization generates serializers reflectively referenced by name.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class com.scrcpy.remote.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.scrcpy.remote.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# libadb reaches Conscrypt by class name and Conscrypt binds its natives through JNI, so Conscrypt
# is kept whole. Bouncy Castle is only referenced directly (certificate building, HKDF, AES-GCM,
# SHA-2 and the edwards25519 field used by SPAKE2), so R8 keeps exactly what is used. Keeping all of
# it shipped unused code, including an EST helper whose trust manager accepts every certificate.
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn io.github.muntashirakon.adb.**

# Line numbers make obfuscated release stack traces usable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
