package com.splitscreen.inputbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.input.InputManager
import android.os.BatteryManager
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
import com.splitscreen.inputbridge.config.AdvancedConfigManager
import com.splitscreen.inputbridge.logging.EnhancedStructuredLogger
import com.splitscreen.inputbridge.metrics.EnhancedPerformanceMetrics
import com.splitscreen.inputbridge.persistence.ProfilePersistenceManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    }

    inner class LocalBinder : Binder() {
        fun getService(): InputBridgeService = this@InputBridgeService
    }

    private val binder = LocalBinder()
    private lateinit var inputManager: InputManager
    private lateinit var windowManager: WindowManager
    private lateinit var choreographer: Choreographer
    private lateinit var sharedPrefs: SharedPreferences

    private val player1Descriptor = AtomicReference("")
    private val player2Descriptor = AtomicReference("")
    private val bridgeActive = AtomicBoolean(false)

    private var statusCallback: WeakReference<((String, String, Boolean) -> Unit)>? = null

    private val serviceScope = CoroutineScope(
        Dispatchers.Default +
        SupervisorJob() +
        CoroutineExceptionHandler { _, throwable ->
            structuredLogger.error("Uncaught coroutine exception", "service_error", null, throwable)
        }
    )
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

    @Volatile private var screenWidth: Int = 0
    @Volatile private var screenHeight: Int = 0
    @Volatile private var screenDensity: Float = 1.0f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdogHandler = Handler(Looper.getMainLooper())

    @Volatile private var isWatchdogRunning = false
    private var currentFrameCallback: Choreographer.FrameCallback? = null
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (isWatchdogRunning) return
            isWatchdogRunning = true
            try {
                checkShizukuHealth()
            } finally {
                isWatchdogRunning = false
            }
        }
    }

    // Novos componentes: Métricas, Logging, Configuração Dinâmica e Persistência
    // private lateinit var performanceMetrics: EnhancedPerformanceMetrics
    // private lateinit var structuredLogger: EnhancedStructuredLogger
    // private lateinit var configManager: AdvancedConfigManager
    // private lateinit var profileManager: ProfilePersistenceManager

    // Track previous state for velocity-based prediction
    private val predictionLock = Any()
    private var lastAxisX = 0f
    private var lastAxisY = 0f
    private var lastFrameTime = 0L

    // Kalman filters for input smoothing
    private val xFilter = KalmanFilter1D(q = 0.02, r = 0.1)
    private val yFilter = KalmanFilter1D(q = 0.02, r = 0.1)

    override fun onCreate() {
        super.onCreate()
        inputManager = getSystemService(InputManager::class.java)
        windowManager = getSystemService(WindowManager::class.java)
        choreographer = Choreographer.getInstance()
        sharedPrefs = getSharedPreferences("InputBridgePrefs", Context.MODE_PRIVATE)

        // Inicializa novos componentes
        // performanceMetrics = EnhancedPerformanceMetrics(this)
        // performanceMetrics.initializeSystemMetrics(this)
        // structuredLogger = EnhancedStructuredLogger(TAG, performanceMetrics, enableFileLogging = true)
        // configManager = AdvancedConfigManager(this, structuredLogger)
        // profileManager = ProfilePersistenceManager(this, structuredLogger)

        // Carrega configurações
        // configManager.loadConfig()
        // profileManager.loadProfiles()

        // Aplica configurações de performance com base no modo atual
        // configManager.applyPerformanceModeSettings()

        // Load persisted fingerprints (chave principal) e descriptors (valor temporário)
        val p1Fingerprint = sharedPrefs.getString("player1_fingerprint", "") ?: ""
        val p2Fingerprint = sharedPrefs.getString("player2_fingerprint", "") ?: ""

        // Se tivermos fingerprints, usá-los como identificadores principais
        if (p1Fingerprint.isNotEmpty()) {
            player1Descriptor.set(p1Fingerprint)
        } else {
            // Fallback para descriptor antigo (compatibilidade)
            player1Descriptor.set(sharedPrefs.getString("player1_descriptor", "") ?: "")
        }

        if (p2Fingerprint.isNotEmpty()) {
            player2Descriptor.set(p2Fingerprint)
        } else {
            // Fallback para descriptor antigo (compatibilidade)
            player2Descriptor.set(sharedPrefs.getString("player2_descriptor", "") ?: "")
        }

        inputManager.registerInputDeviceListener(this, mainHandler)

        // Inicializar e registrar BroadcastReceiver para detectar novos dispositivos
        inputDeviceReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (Intent.ACTION_INPUT_DEVICE_ADDED == intent.action) {
                    val deviceId = intent.getIntExtra(Intent.EXTRA_DEVICE_ID, -1)
                    if (deviceId != -1 && bridgeActive.get()) {
                        revalidateDeviceFingerprints()
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_INPUT_DEVICE_ADDED)
        registerReceiver(inputDeviceReceiver, filter)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Bridge inativa"))
        updateScreenDimensions()

        structuredLogger.info("Service created", "service_lifecycle")
        performanceMetrics.logMetrics()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setStatusCallback(cb: (String, String, Boolean) -> Unit) {
        synchronized(this) {
            statusCallback = WeakReference(cb)
        }
    }

    fun assignGamepad(player: Int, descriptor: String) {
        synchronized(this) {
            // Obter o fingerprint do dispositivo
            val device = InputDevice.getDeviceIds().firstNotNullOfOrNull { id ->
                val dev = InputDevice.getDevice(id)
                if (dev?.descriptor == descriptor) dev else null
            }

            val fingerprint = if (device != null) {
                getDeviceMolecularFingerprint(device)
            } else {
                structuredLogger.warn("Device not found for descriptor", "device_assignment", mapOf(
                    "descriptor" to descriptor
                ))
                descriptor // Fallback para descriptor se dispositivo não encontrado
            }

            if (player == 1) {
                player1Descriptor.set(descriptor)
                // Armazenar fingerprint como chave principal e descriptor como valor
                sharedPrefs.edit()
                    .putString("player1_fingerprint", fingerprint)
                    .putString("player1_descriptor", descriptor)
                    .apply()
            } else {
                player2Descriptor.set(descriptor)
                sharedPrefs.edit()
                    .putString("player2_fingerprint", fingerprint)
                    .putString("player2_descriptor", descriptor)
                    .apply()
            }

            structuredLogger.info("Player $player assigned", "device_assignment", mapOf(
                "player" to player,
                "descriptor" to descriptor,
                "fingerprint" to fingerprint
            ))
            statusCallback?.get()?.invoke(player1Descriptor.get(), player2Descriptor.get(), bridgeActive.get())
        }
    }

    fun startBridge() {
        synchronized(this) {
            if (bridgeActive.get()) {
                structuredLogger.warn("Bridge already active", "bridge_error")
                return
            }

            val p1Desc = player1Descriptor.get()
            val p2Desc = player2Descriptor.get()

            if (p1Desc.isEmpty() || p2Desc.isEmpty()) {
                structuredLogger.warn("Cannot start bridge: gamepads not fully assigned", "bridge_error")
                return
            }
            if (p1Desc == p2Desc) {
                structuredLogger.error("Cannot start bridge: both players share the same descriptor", "bridge_error")
                return
            }

            bridgeActive.set(true)
        }

        serviceScope.launch {
            applySystemHacks()
        }
        updateNotification("Bridge ATIVA — P1: ${player1Descriptor.get().take(12)} | P2: ${player2Descriptor.get().take(12)}")
        statusCallback?.get()?.invoke(player1Descriptor.get(), player2Descriptor.get(), true)
        structuredLogger.info("Bridge started", "bridge_lifecycle", mapOf(
            "player1" to player1Descriptor.get(),
            "player2" to player2Descriptor.get()
        ))

        startWatchdog()
        performanceMetrics.reset()
        startSystemMetricsCollection()
    }

    fun stopBridge() {
        synchronized(this) {
            if (!bridgeActive.get()) {
                structuredLogger.warn("Bridge already inactive", "bridge_error")
                return
            }
            bridgeActive.set(false)
        }

        updateNotification("Bridge inativa")
        // Acesso sincronizado ao callback
        synchronized(this) {
            statusCallback?.invoke(player1Descriptor.get(), player2Descriptor.get(), false)
        }
        structuredLogger.info("Bridge stopped", "bridge_lifecycle")

        stopWatchdog()
        logFinalMetrics()
    }

    private fun logFinalMetrics() {
        structuredLogger.info("Final performance metrics", "metrics_report", mapOf(
            "total_events" to performanceMetrics.getEventsProcessed(),
            "avg_fps" to performanceMetrics.getCurrentFps(),
            "avg_latency_ms" to "%.2f".format(performanceMetrics.getAverageProcessingLatencyMs()),
            "success_rate" to "%.1f".format(performanceMetrics.getInjectionSuccessRate())
        ))
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
        if (!bridgeActive.get()) return false

        val device = InputDevice.getDevice(event.deviceId) ?: return false
        val fingerprint = getDeviceMolecularFingerprint(device)

        return when (fingerprint) {
            player1Descriptor.get() -> {
                false
            }
            player2Descriptor.get() -> {
                injectTransformedEvent(event)
                true
            }
            else -> false
        }
    }

    /**
     * Creates a molecular fingerprint for the input device by combining multiple
     * immutable hardware identifiers from the kernel (EVIOCGUNIQ, EVIOCGPHYS, EVIOCGID).
     * This creates a unique signature that persists across device reconnections.
     */
    private fun getDeviceMolecularFingerprint(device: InputDevice): String {
        val baseDescriptor = device.descriptor

        try {
            // Extract device info from /proc/bus/input/devices
            val deviceInfo = ShizukuUserService.execShellCommand(
                "cat /proc/bus/input/devices | grep -A 10 'N: Name=\"${device.name}\"'
            )

            // Parse unique ID (EVIOCGUNIQ equivalent)
            val uniqueId = deviceInfo.lines().find { it.startsWith("U: Uniq=") }
                ?.substringAfter("=")?.trim() ?: ""

            // Parse physical path (EVIOCGPHYS equivalent - may contain MAC for Bluetooth)
            val physicalPath = deviceInfo.lines().find { it.startsWith("P: ") }
                ?.substringAfter("P: ")?.trim() ?: ""

            // Parse device ID components (EVIOCGID equivalent)
            val idLine = deviceInfo.lines().find { it.startsWith("I: Bus=") }
            val busType = idLine?.substringAfter("Bus=")?.substringBefore(" ")?.toIntOrNull() ?: 0
            val vendorId = idLine?.substringAfter("Vendor=")?.substringBefore(" ")?.toIntOrNull() ?: 0
            val productId = idLine?.substringAfter("Product=")?.substringBefore(" ")?.toIntOrNull() ?: 0
            val version = idLine?.substringAfter("Version=")?.toIntOrNull() ?: 0

            // Create molecular fingerprint combining all immutable identifiers
            return "$baseDescriptor|$uniqueId|$physicalPath|$busType:$vendorId:$productId:$version"

        } catch (e: SecurityException) {
            structuredLogger.warn("SecurityException in fingerprinting", "device_fingerprint", null, e)
            return baseDescriptor
        } catch (e: IllegalStateException) {
            structuredLogger.warn("IllegalStateException in fingerprinting", "device_fingerprint", null, e)
            return baseDescriptor
        } catch (e: Exception) {
            structuredLogger.warn("Failed to create molecular fingerprint", "device_fingerprint", null, e)
            return baseDescriptor
        }
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
        performanceMetrics.onEventProcessingStarted()
        val startTime = System.nanoTime()

        updateScreenDimensions()

        val axisX = source.getAxisValue(MotionEvent.AXIS_X)
        val axisY = source.getAxisValue(MotionEvent.AXIS_Y)
        val axisTriggerL = source.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        val currentTime = System.nanoTime()

        // Get current config
        val config = configManager.configState.value

        // Deadzone filtering to eliminate noise
        if (Math.abs(axisX) < config.deadzoneThreshold && Math.abs(axisY) < config.deadzoneThreshold) {
            // Reset prediction state when in deadzone
            lastAxisX = 0f
            lastAxisY = 0f
            lastFrameTime = 0L
            performanceMetrics.onEventDropped()
            return
        }

        // Apply Kalman filter for input smoothing (if enabled)
        val filteredAxisX = if (config.enableInputSmoothing) {
            xFilter.update(axisX.toDouble()).toFloat()
        } else {
            axisX
        }

        val filteredAxisY = if (config.enableInputSmoothing) {
            yFilter.update(axisY.toDouble()).toFloat()
        } else {
            axisY
        }

        // Velocity-based prediction for latency compensation (thread-safe)
        var finalAxisX = filteredAxisX
        var finalAxisY = filteredAxisY

        if (config.enablePrediction) {
            synchronized(predictionLock) {
                val frameDeltaTime = if (lastFrameTime > 0) (currentTime - lastFrameTime) / 1_000_000f else 0f
                val velocityX = if (frameDeltaTime > 0) (filteredAxisX - lastAxisX) / frameDeltaTime else 0f
                val velocityY = if (frameDeltaTime > 0) (filteredAxisY - lastAxisY) / frameDeltaTime else 0f

                // Predict position based on current velocity
                val predictedAxisX = filteredAxisX + (velocityX * config.predictionFactor)
                val predictedAxisY = filteredAxisY + (velocityY * config.predictionFactor)

                // Clamp predicted values to [-1.0, 1.0] range
                finalAxisX = predictedAxisX.coerceIn(-1.0f, 1.0f)
                finalAxisY = predictedAxisY.coerceIn(-1.0f, 1.0f)

                // Store current state for next frame
                lastAxisX = filteredAxisX
                lastAxisY = filteredAxisY
                lastFrameTime = currentTime
            }
        }

        // Apply coordinate transformation
        val touchX = ((finalAxisX + 1.0f) / 2.0f) * screenWidth
        val touchY = (screenHeight / 2f) + ((finalAxisY + 1.0f) / 2.0f) * (screenHeight / 2f)

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

        // Log transformation event
        structuredLogger.logTransformationEvent(axisX, axisY, touchX, touchY, mapOf(
            "action" to if (action == MotionEvent.ACTION_DOWN) "down" else "move",
            "device_id" to source.deviceId
        ))

        // Record processing latency
        val processingEndTime = System.nanoTime()
        performanceMetrics.recordProcessingLatency(startTime, processingEndTime)

        val message = injectionHandler.obtainMessage(MSG_INJECT_EVENT, syntheticEvent)
        message.sendToTarget()
    }

    private fun injectEventWithChoreographer(event: MotionEvent) {
        val injectionStartTime = System.nanoTime()

        // Remove o callback anterior se existir
        currentFrameCallback?.let { choreographer.removeFrameCallback(it) }

        currentFrameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                try {
                    val config = configManager.configState.value
                    Process.setThreadPriority(config.injectionPriority)

                    val injectionResult = try {
                        ShizukuUserService.injectInputEvent(event)
                        true
                    } catch (e: Exception) {
                        structuredLogger.error("Injection failed", "injection_error", null, e)
                        false
                    }

                    // Record injection latency and result
                    val injectionEndTime = System.nanoTime()
                    performanceMetrics.recordInjectionLatency(injectionStartTime, injectionEndTime)

                    if (injectionResult) {
                        performanceMetrics.recordSuccessfulInjection()
                        structuredLogger.logInjectionEvent(true, event.deviceId.toString(),
                            (injectionEndTime - injectionStartTime) / 1_000_000.0)
                    } else {
                        performanceMetrics.recordFailedInjection()
                        structuredLogger.logInjectionEvent(false, event.deviceId.toString(),
                            (injectionEndTime - injectionStartTime) / 1_000_000.0)
                    }

                    // Record frame time for FPS calculation
                    performanceMetrics.recordFrameTime(frameTimeNanos)
                } finally {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                    event.recycle()
                }
            }
        })
    }

    /**
     * Lightweight 1D Kalman Filter for input smoothing
     */
    private class KalmanFilter1D(
        private var q: Double = 0.001,  // Process noise
        private var r: Double = 0.1,    // Measurement noise
        private var x: Double = 0.0,    // Estimated value
        private var p: Double = 1.0,    // Estimation error covariance
        private var k: Double = 0.0     // Kalman gain
    ) {
        fun update(measurement: Double): Double {
            // Prediction
            p += q

            // Update
            k = p / (p + r)
            x += k * (measurement - x)
            p *= (1 - k)

            return x
        }

        fun reset() {
            x = 0.0
            p = 1.0
            k = 0.0
        }
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
            structuredLogger.info("System hack applied", "system_hack", mapOf(
                "setting" to "multi_window_focus_enabled",
                "value" to "1"
            ))
        } catch (e: Exception) {
            structuredLogger.error("Failed to apply system hack", "system_hack_error", null, e)
        }
    }

    private fun startWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)

        // Adaptive watchdog interval based on battery level and config
        val config = configManager.configState.value
        val batteryLevel = getBatteryLevel()

        val adaptiveInterval = if (config.adaptiveWatchdogEnabled && batteryLevel < config.lowBatteryThreshold) {
            config.watchdogIntervalMs * 2 // Reduce frequency when battery is low
        } else {
            config.watchdogIntervalMs
        }

        watchdogHandler.postDelayed(watchdogRunnable, adaptiveInterval)
        structuredLogger.info("Watchdog started", "watchdog_lifecycle", mapOf(
            "interval_ms" to adaptiveInterval,
            "battery_level" to batteryLevel
        ))
    }

    private fun getBatteryLevel(): Int {
        return try {
            val batteryIntent = applicationContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else 100
        } catch (e: Exception) {
            structuredLogger.warn("Failed to get battery level", "system_error", null, e)
            100 // Default to full battery if we can't determine
        }
    }

    private fun stopWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        structuredLogger.info("Watchdog stopped", "watchdog_lifecycle")
    }

    private fun startSystemMetricsCollection() {
        serviceScope.launch {
            while (bridgeActive.get()) {
                try {
                    // performanceMetrics.collectSystemMetrics()
                    delay(1000) // Coleta a cada segundo
                } catch (e: Exception) {
                    // structuredLogger.error("Failed to collect system metrics", "metrics_error", null, e)
                    break
                }
            }
        }
    }

    private fun checkShizukuHealth() {
        if (!bridgeActive.get()) return

        try {
            val isShizukuAlive = ShizukuUserService.isReady()
            if (!isShizukuAlive) {
                // structuredLogger.warn("Shizuku binder died", "shizuku_health", mapOf(
                //     "alive" to false
                // ))
                serviceScope.launch {
                    try {
                        applySystemHacks()
                    } catch (e: Exception) {
                        // structuredLogger.error("Failed to restart bridge", "bridge_error", null, e)
                    }
                }
            } else {
                // structuredLogger.debug("Shizuku health check passed", "shizuku_health", mapOf(
                //     "alive" to true
                // ))
            }
        } catch (e: SecurityException) {
            // structuredLogger.error("SecurityException in watchdog", "watchdog_error", null, e)
        } catch (e: IllegalStateException) {
            // structuredLogger.error("IllegalStateException in watchdog", "watchdog_error", null, e)
        } catch (e: Exception) {
            // structuredLogger.error("Watchdog check failed", "watchdog_error", null, e)
        } finally {
            // Reagendar o watchdog com intervalo adaptativo
            if (bridgeActive.get()) {
                startWatchdog()
            }
        }
    }

    private fun updateScreenDimensions() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.density

        // structuredLogger.debug("Screen dimensions updated", "system_info", mapOf(
        //     "width" to screenWidth,
        //     "height" to screenHeight,
        //     "density" to screenDensity
        // ))
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
        // structuredLogger.debug("Input device added", "device_event", mapOf(
        //     "device_id" to deviceId
        // ))
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId)
        if (device != null && bridgeActive.get()) {
            if (device.descriptor == player1Descriptor.get() || device.descriptor == player2Descriptor.get()) {
                stopBridge()
                // structuredLogger.warn("Bridge stopped: assigned device removed", "bridge_error", mapOf(
                //     "device_id" to deviceId,
                //     "descriptor" to device.descriptor
                // ))
            }
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {}

    /**
     * BroadcastReceiver para detectar quando novos dispositivos de entrada são conectados
     * e revalidar os fingerprints para garantir que Player 2 não assumiu o lugar do Player 1.
     */
    private lateinit var inputDeviceReceiver: BroadcastReceiver

    /**
     * Revalida todos os fingerprints dos dispositivos conectados para garantir
     * que a associação Player 1/Player 2 ainda está correta.
     */
    private fun revalidateDeviceFingerprints() {
        // structuredLogger.info("Revalidating device fingerprints", "device_validation")

        val currentDevices = InputDevice.getDeviceIds()
        val currentFingerprints = mutableMapOf<String, String>() // descriptor -> fingerprint

        for (deviceId in currentDevices) {
            val device = InputDevice.getDevice(deviceId) ?: continue
            val fingerprint = getDeviceMolecularFingerprint(device)
            currentFingerprints[device.descriptor] = fingerprint
        }

        // Verificar se os fingerprints atuais correspondem aos armazenados
        val p1Desc = player1Descriptor.get()
        val p2Desc = player2Descriptor.get()

        val p1Fingerprint = currentFingerprints[p1Desc]
        val p2Fingerprint = currentFingerprints[p2Desc]

        // Se algum dispositivo foi desconectado ou o fingerprint mudou, parar a bridge
        if (p1Desc.isNotEmpty() && p1Fingerprint == null) {
            // structuredLogger.warn("Player 1 device disconnected or fingerprint changed", "device_validation")
            stopBridge()
        } else if (p2Desc.isNotEmpty() && p2Fingerprint == null) {
            // structuredLogger.warn("Player 2 device disconnected or fingerprint changed", "device_validation")
            stopBridge()
        } else {
            // structuredLogger.info("Device validation passed", "device_validation", mapOf(
            //     "player1_fingerprint" to p1Fingerprint,
            //     "player2_fingerprint" to p2Fingerprint
            // ))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inputManager.unregisterInputDeviceListener(this)
        serviceScope.cancel()

        // Unregister BroadcastReceiver
        try {
            unregisterReceiver(inputDeviceReceiver)
        } catch (e: IllegalArgumentException) {
            // structuredLogger.warn("BroadcastReceiver already unregistered", "service_lifecycle")
        }

        // Clear callback to prevent memory leaks
        statusCallback = null

        // Clean up handlers to prevent memory leaks
        injectionHandler.removeCallbacksAndMessages(null)
        watchdogHandler.removeCallbacksAndMessages(null)
        currentFrameCallback?.let { choreographer.removeFrameCallback(it) }

        // Clean up new components
        // configManager.cleanup()
        // profileManager.cleanup()
        // structuredLogger.shutdown()

        // Revert system hacks
        try {
            ShizukuUserService.execShellCommand("settings put global multi_window_focus_enabled 0")
            // structuredLogger.info("System hacks reverted", "system_hack")
        } catch (e: Exception) {
            // structuredLogger.error("Failed to revert system hacks", "system_hack_error", null, e)
        }

        // Stop foreground service
        stopForeground(true)

        // structuredLogger.info("Service destroyed", "service_lifecycle")
    }

    // Novos métodos públicos para configuração dinâmica
    /*
    fun getConfigManager(): DynamicConfigManager {
        return configManager
    }

    fun getProfileManager(): ProfilePersistenceManager {
        return profileManager
    }

    fun getPerformanceMetrics(): PerformanceMetrics {
        return performanceMetrics
    }

    fun getStructuredLogger(): StructuredLogger {
        return structuredLogger
    }

    /**
     * Exporta métricas de performance completas em formato JSON
     */
    fun exportPerformanceMetrics(): String {
        return performanceMetrics.generateMetricsJsonReport()
    }

    /**
     * Exporta perfis de usuário em formato JSON
     */
    suspend fun exportUserProfiles(): String {
        return profileManager.exportProfilesToJson()
    }

    /**
     * Obtém o relatório de métricas formatado
     */
    fun getPerformanceMetricsReport(): String {
        return performanceMetrics.generateMetricsReport()
    }
    */

    /**
     * Obtém descritor aprimorado do dispositivo
     */
    private fun getEnhancedDeviceDescriptor(device: InputDevice): String {
        return "${device.descriptor}|${device.name}|${device.vendorId}|${device.productId}"
    }
}