package com.splitscreen.inputbridge

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/**
 * ShizukuUserService — Privileged bridge to IInputManager via Shizuku binder.
 *
 * This object provides two capabilities via Shizuku's elevated process context:
 *
 * 1. injectInputEvent(event) — Calls IInputManager.injectInputEvent() through
 *    Shizuku's binder proxy. This bypasses the normal InputDispatcher focus
 *    restriction by injecting with MODE_WAIT_FOR_FINISH from a trusted source
 *    (the Shizuku server runs with uid=shell or uid=root depending on setup).
 *
 * 2. execShellCommand(cmd) — Runs arbitrary shell commands in the Shizuku process,
 *    used to apply system settings like multi_window_focus_enabled.
 *
 * IInputManager access:
 *   The standard approach is to obtain the IInputManager binder via:
 *     ServiceManager.getService("input") → IInputManager.Stub.asInterface(binder)
 *   Since we cannot call ServiceManager directly from app context, Shizuku gives us
 *   a remote execution environment where these privileged calls are permitted.
 *
 * Injection mode flags (from AOSP InputDispatcher.h):
 *   INJECT_INPUT_EVENT_MODE_ASYNC         = 0  (fire and forget)
 *   INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1
 *   INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2  ← we use this
 */
object ShizukuUserService {

    private const val TAG = "ShizukuUserService"

    private const val INJECT_MODE_WAIT_FOR_FINISH = 2

    /**
     * Injects a synthetic InputEvent via IInputManager through the Shizuku binder.
     *
     * The injection uses reflection to access the hidden IInputManager API surface,
     * which is gated by the `android.permission.INJECT_EVENTS` signature permission.
     * Shizuku's process context holds this permission, making injection possible.
     */
    fun injectInputEvent(event: InputEvent): Boolean {
        if (event == null) {
            Log.e(TAG, "injectInputEvent: event is null")
            return false
        }

        return try {
            val inputManagerClass = Class.forName("android.hardware.input.InputManager")
            val instanceMethod = inputManagerClass.getDeclaredMethod("getInstance")
            instanceMethod.isAccessible = true
            val instance = instanceMethod.invoke(null)

            if (instance == null) {
                Log.e(TAG, "injectInputEvent: failed to get InputManager instance")
                return injectViaShizukuProcess(event)
            }

            val injectMethod = inputManagerClass.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.java
            )
            injectMethod.isAccessible = true

            val result = injectMethod.invoke(instance, event, INJECT_MODE_WAIT_FOR_FINISH)
            Log.d(TAG, "injectInputEvent result=$result event=${event.javaClass.simpleName}")
            result as? Boolean ?: false
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "ClassNotFoundException in injectInputEvent: ${e.message}")
            injectViaShizukuProcess(event)
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "NoSuchMethodException in injectInputEvent: ${e.message}")
            injectViaShizukuProcess(event)
        } catch (e: IllegalAccessException) {
            Log.e(TAG, "IllegalAccessException in injectInputEvent: ${e.message}")
            injectViaShizukuProcess(event)
        } catch (e: InvocationTargetException) {
            Log.e(TAG, "InvocationTargetException in injectInputEvent: ${e.message}")
            injectViaShizukuProcess(event)
        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent failed via reflection: ${e.message}")
            injectViaShizukuProcess(event)
        }
    }

    /**
     * Fallback: execute input injection via Shizuku shell using the `input` CLI tool.
     * This is less accurate than IInputManager but always available when Shizuku is active.
     *
     * For MotionEvent (touch), we translate to `input tap x y`.
     */
    private fun injectViaShizukuProcess(event: InputEvent): Boolean {
        if (event !is MotionEvent) return false

        return try {
            val x = event.x.toInt()
            val y = event.y.toInt()

            // Validate coordinates are within reasonable screen bounds
            if (x < 0 || y < 0 || x > 8000 || y > 8000) {
                Log.w(TAG, "injectViaShizukuProcess: invalid coordinates x=$x, y=$y")
                return false
            }

            val cmd = when (event.action) {
                MotionEvent.ACTION_DOWN -> "input tap $x $y"
                MotionEvent.ACTION_MOVE -> "input swipe $x $y $x $y 16"
                else -> return false
            }

            val result = execShellCommand(cmd)
            if (result.isNotBlank()) {
                Log.d(TAG, "injectViaShizukuProcess success: $cmd")
                return true
            }
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in injectViaShizukuProcess: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException in injectViaShizukuProcess: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "injectViaShizukuProcess failed: ${e.message}")
            false
        }
    }

    /**
     * Executes a shell command through the Shizuku process.
     * The Shizuku server forks a child process with shell/root privileges.
     *
     * Common commands used by this bridge:
     *   - `settings put global multi_window_focus_enabled 1`
     *   - `settings put global multi_window_focus_enabled 0`  (cleanup on exit)
     *   - `input tap X Y`  (fallback injection)
     */
    fun execShellCommand(command: String): String {
        if (command.isBlank()) {
            Log.w(TAG, "execShellCommand: empty command")
            return ""
        }

        return try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null) as? ShizukuRemoteProcess

            if (process == null) {
                Log.e(TAG, "execShellCommand: process is null")
                return ""
            }

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Log.d(TAG, "execShellCommand: cmd='$command' exit=$exitCode output='${output.trim()}'")
            output
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "ClassNotFoundException in execShellCommand: ${e.message}")
            ""
        } catch (e: NoSuchMethodException) {
            Log.e(TAG, "NoSuchMethodException in execShellCommand: ${e.message}")
            ""
        } catch (e: IllegalAccessException) {
            Log.e(TAG, "IllegalAccessException in execShellCommand: ${e.message}")
            ""
        } catch (e: InvocationTargetException) {
            Log.e(TAG, "InvocationTargetException in execShellCommand: ${e.message}")
            ""
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in execShellCommand: ${e.message}")
            ""
        } catch (e: Exception) {
            Log.e(TAG, "execShellCommand failed: cmd='$command' error=${e.message}")
            ""
        }
    }

    /**
     * Queries the current value of a global setting via Shizuku shell.
     */
    fun getGlobalSetting(key: String): String {
        return execShellCommand("settings get global $key").trim()
    }

    /**
     * Verifies that the Shizuku binder is alive and the process has been granted permission.
     */
    fun isReady(): Boolean {
        return try {
            val binderAlive = Shizuku.pingBinder()
            val permissionGranted = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            binderAlive && permissionGranted
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in isReady: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException in isReady: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception in isReady: ${e.message}")
            false
        }
    }
}

/**
 * IUserService — Remote user service interface for running inside the Shizuku process.
 *
 * For use cases requiring a persistent privileged binder (as opposed to one-shot shell calls),
 * this service can be bound via Shizuku.bindUserService(). It runs with shell UID inside
 * the Shizuku server process, granting access to INJECT_EVENTS and other signature permissions.
 *
 * Lifecycle:
 *   The user service is started with Shizuku.bindUserService() and stopped with
 *   Shizuku.unbindUserService(). The binder is passed back via ServiceConnection.
 */
class ShizukuPrivilegedUserService : IShizukuUserService.Stub() {

    companion object {
        private const val TAG = "ShizukuPrivilegedSvc"

        fun createArgs(): Shizuku.UserServiceArgs {
            return Shizuku.UserServiceArgs(
                ComponentName(
                    "com.splitscreen.inputbridge",
                    ShizukuPrivilegedUserService::class.java.name
                )
            )
                .daemon(false)
                .processNameSuffix("privileged")
                .debuggable(false)
                .version(1)
        }
    }

    private var inputManagerService: Any? = null

    override fun onCreate() {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String::class.java)
            getServiceMethod.isAccessible = true
            val inputBinder = getServiceMethod.invoke(null, "input") as? IBinder

            if (inputBinder != null) {
                val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterfaceMethod = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                inputManagerService = asInterfaceMethod.invoke(null, inputBinder)
                Log.i(TAG, "IInputManager obtained successfully via ServiceManager")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain IInputManager: ${e.message}")
        }
    }

    override fun injectInputEvent(event: InputEvent?, mode: Int): Boolean {
        val mgr = inputManagerService ?: return false
        return try {
            val method = mgr.javaClass.getDeclaredMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.java
            )
            method.isAccessible = true
            method.invoke(mgr, event, mode) as? Boolean ?: false
        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent via IInputManager failed: ${e.message}")
            false
        }
    }

    override fun execShell(command: String?): String {
        if (command.isNullOrBlank()) return ""
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out
        } catch (e: Exception) {
            Log.e(TAG, "execShell failed: ${e.message}")
            ""
        }
    }

    override fun destroy() {
        Log.i(TAG, "ShizukuPrivilegedUserService destroyed")
    }
}
