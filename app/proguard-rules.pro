# Shizuku API
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }

# Compose
-keepclassmembers class androidx.compose.ui.platform.AndroidComposeView {
    void *Listener(...);
}

# Kotlin Serialization / Reflection (if used)
-keepattributes Signature,Annotation,EnclosingMethod,InnerClasses

# Prevent R8 from removing ViewModel constructors
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(...);
}

# Keep ShizukuBinderWrapper and other reflection-based calls
-keepclassmembers class * {
    @rikka.shizuku.ShizukuBinderWrapper *;
}
