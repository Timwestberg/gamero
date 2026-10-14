# R8 rules for the Android release build (composeApp/build.gradle.kts).
# Libraries ship their own consumer rules; these cover what this app adds.

# App classes are small next to the libraries. Keep them whole so serializers,
# Koin definitions and bridge handlers found by name survive shrinking.
-keep class org.formulagen.wrapper.** { *; }

# Methods the page calls through the WebView's JavaScript interface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface, *Annotation*, Signature, InnerClasses, EnclosingMethod

# compose-webview-multiplatform's JS bridge (de)serializes its message types.
-keep class com.multiplatform.webview.** { *; }

# Firebase finds registrars by name from the manifest and calls their no-arg
# constructor; its consumer rule keeps the classes but not the constructors.
-keepclassmembers class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
