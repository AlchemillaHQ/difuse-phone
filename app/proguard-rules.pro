# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve line number information for Crashlytics stack trace deobfuscation
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Kotlin metadata for reflection-based features
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keep class kotlin.Metadata { *; }

# Keep data binding classes
-keep class android.databinding.** { *; }
-keep class androidx.databinding.** { *; }
-dontwarn android.databinding.**

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable { *; }

# Keep Serializable classes
-keep class * implements java.io.Serializable { *; }

# Keep custom views used from XML layouts
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep Firebase/Crashlytics
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Keep Linphone SDK JNI methods
-keep class org.linphone.core.** { *; }

# Keep navigation safe-args classes
-keep class * extends androidx.navigation.NavArgs { *; }
