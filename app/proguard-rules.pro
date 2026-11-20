# Keep Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
# OkHttp/Retrofit
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.jsoup.**
