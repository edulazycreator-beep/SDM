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

# JSch (SFTP) loads its crypto and key-exchange classes by name.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.apache.commons.net.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.net.unix.**
-dontwarn com.sun.jna.**
