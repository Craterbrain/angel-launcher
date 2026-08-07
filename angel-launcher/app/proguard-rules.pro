# R8 Proguard rules for Angel Launcher

-keep class vibes.launcher.angel.data.** { *; }
-keep class vibes.launcher.angel.ui.** { *; }
-keep class vibes.launcher.angel.services.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
