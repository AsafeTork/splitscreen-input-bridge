package com.splitscreen.inputbridge

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import java.lang.reflect.InvocationTargetException
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
            val permissionGranted = isPermissionGranted()

            if (!binderAlive) {
                Log.w(TAG, "Shizuku binder is not alive")
            }

            if (!permissionGranted) {
                Log.w(TAG, "Shizuku permission not granted")
            }

            val ready = binderAlive && permissionGranted
            Log.d(TAG, "isReady check - binderAlive: $binderAlive, permissionGranted: $permissionGranted, ready: $ready")
            ready
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

    /**
     * Checks if Shizuku permission has been granted without checking binder status
     * Useful for quick permission checks
     */
    fun isPermissionGranted(): Boolean {
        return try {
            val permission = Shizuku.checkSelfPermission()
            val granted = permission == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Permission check - code: $permission, granted: $granted")
            granted
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException checking permission: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException checking permission: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception checking permission: ${e.message}", e)
            false
        }
    }

    private var privilegedService: IShizukuUserService? = null
    private var pendingCallback: ILinuxInputCallback? = null
    private var p1Vendor: Int = 0
    private var p1Product: Int = 0
    private var p1Name: String = ""
    private var p2Vendor: Int = 0
    private var p2Product: Int = 0
    private var p2Name: String = ""

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            privilegedService = IShizukuUserService.Stub.asInterface(binder)
            Log.i(TAG, "PrivilegedUserService connected!")
            pendingCallback?.let {
                try {
                    privilegedService?.startLinuxInputReader(
                        it, p1Vendor, p1Product, p1Name, p2Vendor, p2Product, p2Name
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start Linux input reader", e)
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            privilegedService = null
            Log.i(TAG, "PrivilegedUserService disconnected")
        }
    }

    fun startLinuxReader(
        cb: ILinuxInputCallback,
        p1V: Int, p1P: Int, p1N: String,
        p2V: Int, p2P: Int, p2N: String
    ) {
        Log.i(TAG, "startLinuxReader initiated in App process")
        this.pendingCallback = cb
        this.p1Vendor = p1V
        this.p1Product = p1P
        this.p1Name = p1N
        this.p2Vendor = p2V
        this.p2Product = p2P
        this.p2Name = p2N

        if (privilegedService != null) {
            try {
                privilegedService?.startLinuxInputReader(
                    cb, p1V, p1P, p1N, p2V, p2P, p2N
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error starting Linux reader", e)
            }
        } else {
            try {
                Shizuku.bindUserService(
                    ShizukuPrivilegedUserService.createArgs(),
                    serviceConnection
                )
                Log.i(TAG, "Shizuku.bindUserService called successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind privileged user service", e)
            }
        }
    }

    fun stopLinuxReader() {
        Log.i(TAG, "stopLinuxReader initiated in App process")
        this.pendingCallback = null
        try {
            privilegedService?.stopLinuxInputReader()
            Shizuku.unbindUserService(
                ShizukuPrivilegedUserService.createArgs(),
                serviceConnection,
                true
            )
            Log.i(TAG, "Shizuku.unbindUserService called")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind privileged user service", e)
        }
        privilegedService = null
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
    
    private var callback: ILinuxInputCallback? = null
    private val readerThreads = mutableListOf<Thread>()
    private val activeFds = mutableListOf<java.io.FileDescriptor>()
    @Volatile private var isRunning = false

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

    override fun startLinuxInputReader(
        cb: ILinuxInputCallback?,
        p1Vendor: Int, p1Product: Int, p1Name: String?,
        p2Vendor: Int, p2Product: Int, p2Name: String?
    ) {
        Log.i(TAG, "startLinuxInputReader called in privileged service")
        this.callback = cb
        if (isRunning) {
            stopLinuxInputReader()
        }
        isRunning = true

        val p1EventNum = findEventNumForDevice(p1Vendor, p1Product, p1Name)
        val p2EventNum = findEventNumForDevice(p2Vendor, p2Product, p2Name)

        Log.i(TAG, "Mapped Player 1 event number: $p1EventNum")
        Log.i(TAG, "Mapped Player 2 event number: $p2EventNum")

        val devicesToPlayers = mutableMapOf<Int, Int>()
        if (p1EventNum != -1) {
            devicesToPlayers[p1EventNum] = 1
        }
        if (p2EventNum != -1) {
            devicesToPlayers[p2EventNum] = 2
        }

        if (devicesToPlayers.isEmpty()) {
            Log.w(TAG, "No gamepads mapped to Player 1 or Player 2 in privileged context!")
            return
        }

        for ((eventNum, player) in devicesToPlayers) {
            val devPath = "/dev/input/event$eventNum"
            val thread = Thread {
                var fd: java.io.FileDescriptor? = null
                try {
                    fd = android.system.Os.open(devPath, android.system.OsConstants.O_RDONLY, 0)
                    synchronized(activeFds) {
                        activeFds.add(fd)
                    }

                    // EVIOCGRAB = 0x40044590
                    val EVIOCGRAB = 0x40044590
                    try {
                        ioctlInt(fd, EVIOCGRAB, 1)
                        Log.i(TAG, "Successfully grabbed $devPath for Player $player")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not grab device $devPath: ${e.message}. Reading anyway.")
                    }

                    val structSize = if (android.os.Process.is64Bit()) 24 else 16
                    val buffer = ByteArray(structSize)

                    while (isRunning) {
                        var readBytes = 0
                        while (readBytes < structSize) {
                            val ret = android.system.Os.read(fd, buffer, readBytes, structSize - readBytes)
                            if (ret <= 0) break
                            readBytes += ret
                        }
                        if (readBytes < structSize) break

                        val byteBuf = java.nio.ByteBuffer.wrap(buffer).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val sec = if (structSize == 24) byteBuf.long else byteBuf.int.toLong()
                        val usec = if (structSize == 24) byteBuf.long else byteBuf.int.toLong()
                        val type = byteBuf.short.toInt() and 0xFFFF
                        val code = byteBuf.short.toInt() and 0xFFFF
                        val value = byteBuf.int

                        callback?.onGamepadEvent(player, type, code, value)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading from $devPath for Player $player", e)
                } finally {
                    fd?.let {
                        try {
                            try {
                                ioctlInt(it, 0x40044590, 0)
                            } catch (ignored: Exception) {}
                            android.system.Os.close(it)
                        } catch (ignored: Exception) {}
                        synchronized(activeFds) {
                            activeFds.remove(it)
                        }
                    }
                }
            }
            thread.name = "LinuxInputReader-event$eventNum"
            thread.start()
            readerThreads.add(thread)
        }
    }

    override fun stopLinuxInputReader() {
        Log.i(TAG, "stopLinuxInputReader called")
        isRunning = false
        synchronized(activeFds) {
            for (fd in activeFds) {
                try {
                    android.system.Os.close(fd)
                } catch (ignored: Exception) {}
            }
            activeFds.clear()
        }
        for (thread in readerThreads) {
            try {
                thread.interrupt()
                thread.join(500)
            } catch (ignored: Exception) {}
        }
        readerThreads.clear()
        callback = null
    }

    private fun findEventNumForDevice(vendor: Int, product: Int, name: String?): Int {
        val file = java.io.File("/proc/bus/input/devices")
        if (!file.exists()) return -1

        val targetVendor = String.format("%04x", vendor).lowercase()
        val targetProduct = String.format("%04x", product).lowercase()
        val targetName = name?.lowercase() ?: ""

        var currentName = ""
        var currentHandler = ""
        var currentVendor = ""
        var currentProduct = ""

        try {
            val reader = file.bufferedReader()
            var line = reader.readLine()
            while (line != null) {
                if (line.startsWith("I:")) {
                    currentVendor = line.substringAfter("Vendor=").substringBefore(" ").lowercase()
                    currentProduct = line.substringAfter("Product=").substringBefore(" ").lowercase()
                } else if (line.startsWith("N: Name=")) {
                    currentName = line.substringAfter("Name=\"").substringBefore("\"").lowercase()
                } else if (line.startsWith("H: Handlers=")) {
                    val handlers = line.substringAfter("Handlers=")
                    val parts = handlers.split(" ")
                    currentHandler = parts.firstOrNull { it.startsWith("event") } ?: ""
                } else if (line.isBlank()) {
                    if (currentHandler.isNotEmpty()) {
                        val nameMatch = targetName.isNotEmpty() && (currentName.contains(targetName) || targetName.contains(currentName))
                        val idMatch = currentVendor == targetVendor && currentProduct == targetProduct
                        if (nameMatch || idMatch) {
                            reader.close()
                            return currentHandler.substringAfter("event").toIntOrNull() ?: -1
                        }
                    }
                    currentName = ""
                    currentHandler = ""
                    currentVendor = ""
                    currentProduct = ""
                }
                line = reader.readLine()
            }
            reader.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing devices file in findEventNumForDevice: ${e.message}")
        }
        return -1
    }

    private fun ioctlInt(fd: java.io.FileDescriptor, cmd: Int, arg: Int) {
        try {
            val osClass = Class.forName("android.system.Os")
            val method = osClass.getMethod(
                "ioctlInt",
                java.io.FileDescriptor::class.java,
                Int::class.java,
                Int::class.java
            )
            method.invoke(null, fd, cmd, arg)
        } catch (e: Exception) {
            Log.e(TAG, "ioctlInt failed via reflection: ${e.message}", e)
            throw e
        }
    }

    override fun destroy() {
        stopLinuxInputReader()
        Log.i(TAG, "ShizukuPrivilegedUserService destroyed")
    }
}
