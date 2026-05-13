package com.splitscreen.inputbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.WindowManager
import kotlinx.coroutines.*

/**
 * InputBridgeService — Foreground service that orchestrates the split-screen input bridge.
 *
 * Architecture overview (per AOSP InputDispatcher research):
 * ─────────────────────────────────────────────────────────
 * The Android InputDispatcher routes touch/gamepad events to the currently focused window only.
 * It is NOT possible to route by TaskID to an unfocused window directly via public APIs
 * because InputDispatcher uses a FocusResolver that only delivers non-injection events to
 * the window holding the current input focus token.
 *
 * Solution — Coordinate Transformation via Viewport:
 *   Player 1's gamepad passes natively (it controls the focused Minecraft instance).
 *   Player 2's gamepad axes are intercepted, converted into absolute screen touch coordinates
 *   mapped to the LOWER half of the screen (secondary split-screen viewport), then injected
 *   via IInputManager.injectInputEvent() through the Shizuku privileged binder. This tricks
 *   the system into delivering synthetic touch events to the secondary viewport window which,
 *   when paired with `multi_window_focus_enabled 1`, processes them independently.
 */
class InputBridgeService : Service(), InputManager.InputDeviceListener {

    companion object {
        private const val TAG = "InputBridgeService"
        private const val CHANNEL_ID = "bridge_channel"
        private const val NOTIF_ID = 1
        private const val MSG_INJECT_EVENT = 1
        private const val DEADZONE_THRESHOLD = 0.15f
        private const val WATCHDOG_INTERVAL_MS = 5000L
    }

    inner class LocalBinder : Binder() {
        fun getService(): InputBridgeService = this@InputBridgeService
    }

    private val binder = LocalBinder()
    private lateinit var inputManager: InputManager
    private lateinit var windowManager: WindowManager
    private lateinit var choreographer: Choreographer

    private var player1Descriptor: String = ""
    private var player2Descriptor: String = ""
    private var bridgeActive: Boolean = false

    private var statusCallback: ((String, String, Boolean) -> Unit)? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val injectionHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            MSG_INJECT_EVENT -> {
                val event = msg.obj as MotionEvent
                injectEventWithChoreographer(event)
                true
            }
            else -> false
        }
    }

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Float = 1.0f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdogHandler = Handler(Looper.getMainLooper())

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkShizukuHealth()
            watchdogHandler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        inputManager = getSystemService(InputManager::class.java)
        windowManager = getSystemService(WindowManager::class.java)
        choreographer = Choreographer.getInstance()

        inputManager.registerInputDeviceListener(this, mainHandler)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Bridge inativa"))
        updateScreenDimensions()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setStatusCallback(cb: (String, String, Boolean) -> Unit) {
        statusCallback = cb
    }

    fun assignGamepad(player: Int, descriptor: String) {
        if (player == 1) player1Descriptor = descriptor
        else player2Descriptor = descriptor
        Log.i(TAG, "Player $player assigned descriptor: $descriptor")
        statusCallback?.invoke(player1Descriptor, player2Descriptor, bridgeActive)
    }

    fun startBridge() {
        if (player1Descriptor.isEmpty() || player2Descriptor.isEmpty()) {
            Log.w(TAG, "Cannot start bridge: gamepads not fully assigned")
            return
        }
        if (player1Descriptor == player2Descriptor) {
            Log.e(TAG, "Cannot start bridge: both players share the same descriptor")
            return
        }

        serviceScope.launch {
            applySystemHacks()
        }

        bridgeActive = true
        updateNotification("Bridge ATIVA — P1: ${player1Descriptor.take(12)} | P2: ${player2Descriptor.take(12)}")
        statusCallback?.invoke(player1Descriptor, player2Descriptor, true)
        Log.i(TAG, "Bridge started. P1=$player1Descriptor P2=$player2Descriptor")

        startWatchdog()
    }

    fun stopBridge() {
        bridgeActive = false
        updateNotification("Bridge inativa")
        statusCallback?.invoke(player1Descriptor, player2Descriptor, false)
        Log.i(TAG, "Bridge stopped")

        stopWatchdog()
    }

    /**
     * Called from the InputBridgeAccessibilityService or a raw InputReader hook
     * whenever a MotionEvent arrives from any gamepad device.
     *
     * Decision logic:
     *   - If device descriptor == player1Descriptor → return false (pass natively, do nothing)
     *   - If device descriptor == player2Descriptor → intercept, transform, inject → return true
     */
    fun onGamepadMotionEvent(event: MotionEvent): Boolean {
        if (!bridgeActive) return false

        val device = InputDevice.getDevice(event.deviceId) ?: return false
        val descriptor = getEnhancedDeviceDescriptor(device)

        return when (descriptor) {
            player1Descriptor -> {
                false
            }
            player2Descriptor -> {
                injectTransformedEvent(event)
                true
            }
            else -> false
        }
    }

    private fun getEnhancedDeviceDescriptor(device: InputDevice): String {
        val baseDescriptor = device.descriptor
        try {
            val uniqueId = ShizukuUserService.execShellCommand("cat /proc/bus/input/devices | grep -A 5 'N: Name=\"${device.name}\"' | grep 'U: Uniq=' | cut -d'=' -f2")
            if (uniqueId.isNotBlank()) {
                return "$baseDescriptor|$uniqueId"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get enhanced descriptor: ${e.message}")
        }
        return baseDescriptor
    }

    /**
     * Transforms Player 2 gamepad axes into touch coordinates mapped to the lower
     * viewport half (split-screen secondary window) and injects via Shizuku.
     *
     * Coordinate Transformation:
     *   Raw gamepad axes (AXIS_X, AXIS_Y) are in [-1.0, 1.0].
     *   Secondary viewport occupies [0, screenWidth] × [screenHeight/2, screenHeight].
     *
     *   touchX = ((axisX + 1.0) / 2.0) * screenWidth
     *   touchY = (screenHeight / 2) + ((axisY + 1.0) / 2.0) * (screenHeight / 2)
     */
    private fun injectTransformedEvent(source: MotionEvent) {
        updateScreenDimensions()

        val axisX = source.getAxisValue(MotionEvent.AXIS_X)
        val axisY = source.getAxisValue(MotionEvent.AXIS_Y)
        val axisTriggerL = source.getAxisValue(MotionEvent.AXIS_LTRIGGER)

        if (Math.abs(axisX) < DEADZONE_THRESHOLD && Math.abs(axisY) < DEADZONE_THRESHOLD) {
            return
        }

        val touchX = ((axisX + 1.0f) / 2.0f) * screenWidth
        val touchY = (screenHeight / 2f) + ((axisY + 1.0f) / 2.0f) * (screenHeight / 2f)

        val action = if (axisTriggerL > 0.5f) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE

        val pointerProperties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val pointerCoords = arrayOf(MotionEvent.PointerCoords().apply {
            x = touchX
            y = touchY
            pressure = 1.0f
            size = 1.0f
        })

        val syntheticEvent = MotionEvent.obtain(
            source.downTime,
            System.currentTimeMillis(),
            action,
            1,
            pointerProperties,
            pointerCoords,
            0, 0, 1.0f, 1.0f,
            source.deviceId,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0
        )

        val message = injectionHandler.obtainMessage(MSG_INJECT_EVENT, syntheticEvent)
        message.sendToTarget()
    }

    private fun injectEventWithChoreographer(event: MotionEvent) {
        choreographer.postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    ShizukuUserService.injectInputEvent(event)
                } finally {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
                    event.recycle()
                }
            }
        })
    }

    /**
     * Applies necessary system settings via Shizuku shell to enable simultaneous
     * focus processing in split-screen mode.
     *
     * `multi_window_focus_enabled 1` is a global setting introduced in AOSP that
     * instructs the WindowManager's FocusResolver to allow multiple windows to
     * receive input simultaneously — essential for split-screen gaming.
     */
    private suspend fun applySystemHacks() = withContext(Dispatchers.IO) {
        try {
            ShizukuUserService.execShellCommand("settings put global multi_window_focus_enabled 1")
            Log.i(TAG, "System hack applied: multi_window_focus_enabled=1")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply system hack: ${e.message}")
        }
    }

    private fun startWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        Log.d(TAG, "Watchdog started, checking Shizuku health every ${WATCHDOG_INTERVAL_MS}ms")
    }

    private fun stopWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        Log.d(TAG, "Watchdog stopped")
    }

    private fun checkShizukuHealth() {
        if (!bridgeActive) return

        try {
            val isShizukuAlive = ShizukuUserService.isReady()
            if (!isShizukuAlive) {
                Log.w(TAG, "Shizuku binder died, attempting to restart bridge...")
                serviceScope.launch {
                    applySystemHacks()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog check failed: ${e.message}")
        }
    }

    private fun updateScreenDimensions() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.density

        Log.d(TAG, "Screen dimensions updated: ${screenWidth}x$screenHeight, density=$screenDensity")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SplitScreen Input Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Serviço de roteamento de gamepad para split-screen"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SplitScreen Input Bridge")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(status))
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        Log.d(TAG, "Input device added: $deviceId")
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId)
        if (device != null && bridgeActive) {
            if (device.descriptor == player1Descriptor || device.descriptor == player2Descriptor) {
                stopBridge()
                Log.w(TAG, "Bridge stopped: assigned device removed (id=$deviceId)")
            }
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        inputManager.unregisterInputDeviceListener(this)
        serviceScope.cancel()
        ShizukuUserService.execShellCommand("settings put global multi_window_focus_enabled 0")
        Log.i(TAG, "InputBridgeService destroyed, system hacks reverted")
    }
}
