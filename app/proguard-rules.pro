# ── Shizuku ──────────────────────────────────────────────────────────────────
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# ── HiddenApiBypass ───────────────────────────────────────────────────────────
-keep class org.lsposed.hiddenapibypass.** { *; }

# ── IInputManager & ServiceManager (accessed via reflection) ─────────────────
-keep class android.hardware.input.IInputManager { *; }
-keep class android.hardware.input.IInputManager$Stub { *; }
-keep class android.os.ServiceManager { *; }
-keepclassmembers class android.hardware.input.InputManager {
    public static android.hardware.input.InputManager getInstance();
    public boolean injectInputEvent(android.view.InputEvent, int);
}

# ── AIDL generated stubs ──────────────────────────────────────────────────────
-keep class com.splitscreen.inputbridge.IShizukuUserService { *; }
-keep class com.splitscreen.inputbridge.IShizukuUserService$Stub { *; }
-keep class com.splitscreen.inputbridge.ShizukuPrivilegedUserService { *; }

# ── Compose ───────────────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── General Android ───────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.accessibilityservice.AccessibilityService
