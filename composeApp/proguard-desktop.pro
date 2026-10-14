# ProGuard rules for the desktop release build (packageRelease*, runRelease).
# Compose Desktop adds its own default rules; obfuscation is off in build.gradle.kts.

-keep class org.formulagen.wrapper.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations

# The browser: JCEF calls handlers back from native code, and KCEF loads it by name.
-keep class org.cef.** { *; }
-keep class com.jetbrains.cef.** { *; }
-keep class dev.datlag.kcef.** { *; }
-keep class com.multiplatform.webview.** { *; }
-dontwarn org.cef.**
-dontwarn com.jetbrains.cef.**
-dontwarn dev.datlag.kcef.**
-dontwarn com.multiplatform.webview.**

# JOGL and GlueGen load natives and platform classes reflectively.
-keep class com.jogamp.** { *; }
-keep class jogamp.** { *; }
-dontwarn com.jogamp.**
-dontwarn jogamp.**

# kotlinx.serialization generated serializers.
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Optional dependencies of transitive libraries that the desktop app never loads.
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.jetbrains.annotations.**
