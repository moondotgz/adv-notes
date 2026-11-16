# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.advnotes.app.**$$serializer { *; }
-keepclassmembers class com.advnotes.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# JNI bridge
-keep class com.advnotes.app.ai.LlamaBridge { *; }
-keep interface com.advnotes.app.ai.LlamaBridge$TokenCallback { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
