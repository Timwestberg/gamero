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

# commons-compress (KCEF's bundle downloader): pack200 needs ASM, and the xz, zstd and
# brotli codecs are optional jars. The app never unpacks those formats.
-dontwarn org.apache.commons.compress.harmony.pack200.**
-dontwarn org.objectweb.asm.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**

# OkHttp probes for Android and optional TLS providers; on the desktop JVM it uses the JDK's.
-dontwarn android.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

