# kotlinx.serialization: keep generated serializers for navigation routes and JSON models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.sdm.**$$serializer { *; }
-keepclassmembers class com.sdm.** { *** Companion; }
# WebView JavaScript bridge
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
# OkHttp optional platform classes
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
