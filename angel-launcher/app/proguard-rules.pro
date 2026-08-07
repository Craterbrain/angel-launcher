# R8 Proguard rules for Angel Launcher

# Angel Launcher code
-keep class vibes.launcher.angel.** { *; }
-keepclassmembers class vibes.launcher.angel.** { *; }

# Room Database & Generated Impl Classes
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
    public <init>(...);
}
-keep class **.*_Impl {
    public <init>();
    public <init>(...);
}
-keep class androidx.room.Room { *; }
-dontwarn androidx.room.paging.**

# WorkManager & Startup
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger {
    public <init>();
}
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}
-keep class androidx.startup.** { *; }

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson / Serialization / JSON
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
