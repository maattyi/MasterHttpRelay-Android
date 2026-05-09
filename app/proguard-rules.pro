# Keep our public network classes intact for reflection-free release builds
-keep class com.velum.vpn.net.** { *; }
-keep class com.velum.vpn.proxy.** { *; }
-keep class com.velum.vpn.vpn.** { *; }
-keep class com.velum.vpn.gas.** { *; }
-keep class com.velum.vpn.cloudflare.** { *; }

# Compose and Kotlin Serialization need their own keeps
-keepattributes Signature, Annotation, InnerClasses
-keep class kotlinx.serialization.json.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# OkHttp and BouncyCastle
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }
