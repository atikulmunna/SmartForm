# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# SmartForm — keep rules for on-device ML SDKs (reflection / JNI heavy)
# ---------------------------------------------------------------------------

# ML Kit pose detection (+ Play Services vision internals)
-keep class com.google.mlkit.** { *; }
-keep interface com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }
-dontwarn com.google.android.gms.**

# MediaPipe Tasks (hand landmarker), its JNI, and protobuf models
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Native method bindings used by both SDKs
-keepclasseswithmembernames class * {
    native <methods>;
}

# AutoValue-generated option classes referenced reflectively
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.**