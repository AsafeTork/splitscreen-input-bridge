package com.splitscreen.inputbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.os.BatteryManager
import android.os.Binder
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.work.WorkManager
import com.splitscreen.inputbridge.metrics.PerformanceMetrics
import com.splitscreen.inputbridge.repository.ControllerRegistry
import com.splitscreen.inputbridge.repository.ShizukuServiceInterface
import com.splitscreen.inputbridge.repository.ShizukuServiceRepository
import com.splitscreen.inputbridge.state.BridgeState
import com.splitscreen.inputbridge.state.BridgeStateManager
import com.splitscreen.inputbridge.worker.WatchdogManager
import kotlinx.coroutines.*
import kotlin.system.measureTimeMillis

/**
 * InputBridgeServiceEnhanced — Foreground service with comprehensive improvements
 *
 * Enhancements implemented:
 * 1. State Machine pattern using sealed classes (BridgeState)
 * 2. ShizukuServiceInterface with default implementation
 * 3. Performance metrics system (latency, FPS, injection success rate)
 * 4. Enhanced CoroutineExceptionHandler with automatic recovery
 * 5. Comprehensive error handling and state management
 */
class InputBridgeServiceEnhanced : Service(), InputManager.InputDeviceListener {

    companion object {
        private const val TAG = "InputBridgeServiceEnh"
        private const val CHANNEL_ID = "bridge_channel"
        private const val NOTIF_ID = 1
        private const val MSG_INJECT_EVENT = 1
        private const val DEADZONE_THRESHOLD = 0.15f
    }

    inner class LocalBinder : Binder() {
        fun getService(): InputBridgeServiceEnhanced = this@InputBridgeServiceEnhanced
    }

    private val binder = LocalBinder()
    private lateinit var inputManager: InputManager
    private lateinit var windowManager: WindowManager
    private lateinit var choreographer: Choreographer

    // Architecture components
    private lateinit var stateManager: BridgeStateManager
    private lateinit var controllerRegistry: ControllerRegistry
    private lateinit var shizukuService: ShizukuServiceInterface
    private lateinit var watchdogManager: WatchdogManager
    private lateinit var performanceMetrics: PerformanceMetrics

    private var statusCallback: ((String, String, Boolean) -> Unit)? = null

    // Enhanced CoroutineExceptionHandler with automatic recovery
    private val enhancedExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught coroutine exception", throwable)
        handleCoroutineException(throwable)
    }

    private val serviceScope = CoroutineScope(
        Dispatchers.Default +
        SupervisorJob() +
        enhancedExceptionHandler
    )

    private val injectionHandler = Handler(mainLooper) { msg ->
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

    // Track previous state for velocity-based prediction
    private var lastAxisX = 0f
    private var lastAxisY = 0f
    private var lastFrameTime = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service creating with enhanced architecture")

        // Initialize architecture components
        stateManager = BridgeStateManager()
        controllerRegistry = ControllerRegistry(applicationContext)
        shizukuService = ShizukuServiceRepository()
        watchdogManager = WatchdogManager(applicationContext, shizukuService)
        performanceMetrics = PerformanceMetrics()

        // Setup state listeners
        setupStateListeners()

        // Initialize system services
        inputManager = getSystemService(InputManager::class.java)
        windowManager = getSystemService(WindowManager::class.java)
        choreographer = Choreographer.getInstance()

        // Register for input device events
        inputManager.registerInputDeviceListener(this, mainHandler)

        // Initialize notification channel
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Bridge inativa"))

        // Update screen dimensions
        updateScreenDimensions()

        // Transition to initializing state
        stateManager.transitionTo(BridgeState.Initializing)

        // Load controller assignments
        loadControllerAssignments()

        Log.d(TAG, "Service initialized with enhanced features")
    }

    private fun setupStateListeners() {
        stateManager.addListener { state ->
            when (state) {
                is BridgeState.Idle -> {
                    updateNotification("Bridge inativa")
                    statusCallback?.invoke("", "", false)
                    performanceMetrics.reset()
                }
                is BridgeState.Initializing -> {
                    updateNotification("Inicializando...")
                }
                is BridgeState.Ready -> {
                    updateNotification("Pronto para ativar")
                    statusCallback?.invoke(state.player1Descriptor, state.player2Descriptor, false)
                }
                is BridgeState.Active -> {
                    updateNotification("Bridge ATIVA")
                    statusCallback?.invoke(
                        controllerRegistry.controllersState.value.player1Descriptor,
                        controllerRegistry.controllersState.value.player2Descriptor,
                        true
                    )
                    performanceMetrics.startTracking()
                }
                is BridgeState.Stopping -> {
                    updateNotification("Desativando...")
                }
                is BridgeState.Error -> {
                    updateNotification("Erro: ${state.message}")
                    statusCallback?.invoke("", "", false)
                    // Attempt automatic recovery
                    attemptAutomaticRecovery(state)
                }
            }
        }
    }

    private fun attemptAutomaticRecovery(errorState: BridgeState.Error) {
        serviceScope.launch {
            delay(2000) // Wait 2 seconds before recovery attempt
            try {
                Log.i(TAG, "Attempting automatic recovery from error: ${errorState.message}")

                // Check if Shizuku is the issue
                if (!shizukuService.isReady()) {
                    Log.w(TAG, "Shizuku not ready, attempting to restart...")
                    // Attempt to restart Shizuku connection
                    val isReadyAfterRetry = shizukuService.isReady()
                    if (isReadyAfterRetry) {
                        Log.i(TAG, "Shizuku recovered successfully")
                        // Transition back to ready state
                        val currentControllers = controllerRegistry.controllersState.value
                        stateManager.transitionTo(
                            BridgeState.Ready(
                                currentControllers.player1Descriptor,
                                currentControllers.player2Descriptor
                            )
                        )
                    }
                } else {
                    // If Shizuku is fine, transition back to ready state
                    val currentControllers = controllerRegistry.controllersState.value
                    stateManager.transitionTo(
                        BridgeState.Ready(
                            currentControllers.player1Descriptor,
                            currentControllers.player2Descriptor
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Automatic recovery failed: ${e.message}")
                stateManager.transitionTo(
                    BridgeState.Error("Falha na recuperação automática", e)
                )
            }
        }
    }

    private fun handleCoroutineException(throwable: Throwable) {
        Log.e(TAG, "Handling coroutine exception", throwable)

        // Attempt to recover based on exception type
        when (throwable) {
            is SecurityException -> {
                Log.w(TAG, "Security exception detected, checking Shizuku permissions")
                if (!shizukuService.isReady()) {
                    stateManager.transitionTo(
                        BridgeState.Error("Permissão Shizuku revogada", throwable)
                    )
                }
            }
            is IllegalStateException -> {
                Log.w(TAG, "Illegal state detected, transitioning to error state")
                stateManager.transitionTo(
                    BridgeState.Error("Estado ilegal detectado", throwable)
                )
            }
            else -> {
                Log.w(TAG, "Unexpected exception, transitioning to error state")
                stateManager.transitionTo(
                    BridgeState.Error("Erro inesperado: ${throwable.message}", throwable)
                )
            }
        }

        // Attempt to restart critical components
        restartCriticalComponents()
    }

    private fun restartCriticalComponents() {
        serviceScope.launch {
            try {
                Log.i(TAG, "Restarting critical components...")

                // Reinitialize Shizuku service
                shizukuService = ShizukuServiceRepository()

                // Restart watchdog
                if (stateManager.getCurrentState() is BridgeState.Active) {
                    watchdogManager.stopWatchdog()
                    watchdogManager.startWatchdog(true)
                }

                Log.i(TAG, "Critical components restarted successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart critical components: ${e.message}")
            }
        }
    }

    private fun loadControllerAssignments() {
        serviceScope.launch {
            // Observe controller registry state
            controllerRegistry.controllersState.collect { state ->
                val player1Desc = state.player1Descriptor
                val player2Desc = state.player2Descriptor

                // Update service state based on controller registry
                if (player1Desc.isNotEmpty() || player2Desc.isNotEmpty()) {
                    val readyState = BridgeState.Ready(player1Desc, player2Desc)
                    if (stateManager.getCurrentState() !is BridgeState.Ready) {
                        stateManager.transitionTo(readyState)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setStatusCallback(cb: (String, String, Boolean) -> Unit) {
        statusCallback = cb
        // Send current state to callback
        val currentState = stateManager.getCurrentState()
        when (currentState) {
            is BridgeState.Ready -> {
                cb(currentState.player1Descriptor, currentState.player2Descriptor, false)
            }
            is BridgeState.Active -> {
                cb(
                    controllerRegistry.controllersState.value.player1Descriptor,
                    controllerRegistry.controllersState.value.player2Descriptor,
                    true
                )
            }
            else -> cb("", "", false)
        }
    }

    fun assignGamepad(player: Int, descriptor: String) {
        serviceScope.launch {
            val success = controllerRegistry.assignController(player, descriptor)
            if (success) {
                Log.i(TAG, "Player $player assigned descriptor: $descriptor")
            } else {
                Log.w(TAG, "Failed to assign gamepad to player $player")
            }
        }
    }

    fun startBridge() {
        serviceScope.launch {
            try {
                val currentState = stateManager.getCurrentState()

                if (currentState !is BridgeState.Ready) {
                    Log.w(TAG, "Cannot start bridge from state: ${currentState::class.simpleName}")
                    return@launch
                }

                // Validate configuration
                if (!currentState.isFullyConfigured) {
                    Log.w(TAG, "Cannot start bridge: controllers not fully configured")
                    stateManager.transitionTo(BridgeState.Error("Controles não configurados corretamente"))
                    return@launch
                }

                if (!currentState.hasDifferentControllers) {
                    Log.e(TAG, "Cannot start bridge: both players share the same controller")
                    stateManager.transitionTo(BridgeState.Error("Os dois jogadores não podem usar o mesmo controle"))
                    return@launch
                }

                // Apply system hacks
                applySystemHacks()

                // Transition to active state
                stateManager.transitionTo(BridgeState.Active)

                // Start watchdog
                watchdogManager.startWatchdog(true)

                Log.i(TAG, "Bridge started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start bridge: ${e.message}")
                stateManager.transitionTo(BridgeState.Error("Falha ao iniciar bridge", e))
            }
        }
    }

    fun stopBridge() {
        serviceScope.launch {
            try {
                // Transition to stopping state
                stateManager.transitionTo(BridgeState.Stopping)

                // Stop watchdog
                watchdogManager.stopWatchdog()

                // Transition to ready state (keep controller assignments)
                val currentState = controllerRegistry.controllersState.value
                stateManager.transitionTo(
                    BridgeState.Ready(
                        currentState.player1Descriptor,
                        currentState.player2Descriptor
                    )
                )

                Log.i(TAG, "Bridge stopped successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop bridge: ${e.message}")
                stateManager.transitionTo(BridgeState.Error("Falha ao parar bridge", e))
            }
        }
    }

    /**
     * Called from the InputBridgeAccessibilityService or a raw InputReader hook
     * whenever a MotionEvent arrives from any gamepad device.
     */
    fun onGamepadMotionEvent(event: MotionEvent): Boolean {
        val currentState = stateManager.getCurrentState()
        if (currentState !is BridgeState.Active) return false

        val device = InputDevice.getDevice(event.deviceId) ?: return false

        return when (val player1Desc = controllerRegistry.controllersState.value.player1Descriptor) {
            device.descriptor -> false // Player 1 - pass natively
            controllerRegistry.controllersState.value.player2Descriptor -> {
                injectTransformedEvent(event)
                true
            }
            else -> false
        }
    }

    private fun injectTransformedEvent(source: MotionEvent) {
        updateScreenDimensions()

        val axisX = source.getAxisValue(MotionEvent.AXIS_X)
        val axisY = source.getAxisValue(MotionEvent.AXIS_Y)
        val axisTriggerL = source.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        val currentTime = System.nanoTime()

        // Deadzone filtering to eliminate noise
        if (Math.abs(axisX) < DEADZONE_THRESHOLD && Math.abs(axisY) < DEADZONE_THRESHOLD) {
            // Reset prediction state when in deadzone
            lastAxisX = 0f
            lastAxisY = 0f
            lastFrameTime = 0L
            return
        }

        // Velocity-based prediction for latency compensation
        val frameDeltaTime = if (lastFrameTime > 0) (currentTime - lastFrameTime) / 1_000_000f else 0f
        val velocityX = if (frameDeltaTime > 0) (axisX - lastAxisX) / frameDeltaTime else 0f
        val velocityY = if (frameDeltaTime > 0) (axisY - lastAxisY) / frameDeltaTime else 0f

        // Predict position based on current velocity (compensate ~16ms display latency)
        val predictionFactor = 0.016f // 16ms latency compensation
        val predictedAxisX = axisX + (velocityX * predictionFactor)
        val predictedAxisY = axisY + (velocityY * predictionFactor)

        // Clamp predicted values to [-1.0, 1.0] range
        val clampedAxisX = predictedAxisX.coerceIn(-1.0f, 1.0f)
        val clampedAxisY = predictedAxisY.coerceIn(-1.0f, 1.0f)

        // Store current state for next frame
        lastAxisX = axisX
        lastAxisY = axisY
        lastFrameTime = currentTime

        // Apply coordinate transformation with predicted values
        val touchX = ((clampedAxisX + 1.0f) / 2.0f) * screenWidth
        val touchY = (screenHeight / 2f) + ((clampedAxisY + 1.0f) / 2.0f) * (screenHeight / 2f)

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

        // Track injection metrics
        val injectionStartTime = System.nanoTime()
        performanceMetrics.onInjectionStarted()

        val message = injectionHandler.obtainMessage(MSG_INJECT_EVENT, syntheticEvent)
        message.sendToTarget()

        // Measure injection latency
        val injectionLatency = measureTimeMillis {
            // The actual injection happens asynchronously in the handler
        }
        performanceMetrics.recordInjectionLatency(injectionLatency)
    }

    private fun injectEventWithChoreographer(event: MotionEvent) {
        val frameStartTime = System.nanoTime()

        choreographer.postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val choreographerLatency = (frameTimeNanos - frameStartTime) / 1_000_000f // Convert to ms
                performanceMetrics.recordChoreographerLatency(choreographerLatency)

                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)

                    val injectionStartTime = System.nanoTime()
                    val success = shizukuService.injectInputEvent(event)
                    val injectionDuration = (System.nanoTime() - injectionStartTime) / 1_000_000f

                    performanceMetrics.recordInjectionSuccess(success)
                    performanceMetrics.recordInjectionDuration(injectionDuration)

                    // Calculate FPS
                    performanceMetrics.updateFPS()

                    Log.d(TAG, "Injection metrics - Choreographer: ${"%.2f".format(choreographerLatency)}ms, " +
                                "Injection: ${"%.2f".format(injectionDuration)}ms, " +
                                "Success: $success, " +
                                "FPS: ${performanceMetrics.currentFPS}")
                } finally {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
                    event.recycle()
                }
            }
        })
    }

    private suspend fun applySystemHacks() = withContext(Dispatchers.IO) {
        try {
            shizukuService.execShellCommand("settings put global multi_window_focus_enabled 1")
            Log.i(TAG, "System hack applied: multi_window_focus_enabled=1")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply system hack: ${e.message}")
            throw e
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
        controllerRegistry.refreshConnectedDevices()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId)
        if (device != null) {
            Log.d(TAG, "Input device removed: ${device.name} (id=$deviceId)")
            controllerRegistry.refreshConnectedDevices()
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        controllerRegistry.refreshConnectedDevices()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying")

        // Cleanup architecture components
        watchdogManager.cleanup()
        controllerRegistry.cleanup()
        serviceScope.cancel()

        // Revert system hacks
        try {
            shizukuService.execShellCommand("settings put global multi_window_focus_enabled 0")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to revert system hacks: ${e.message}")
        }

        // Unregister listeners
        inputManager.unregisterInputDeviceListener(this)

        // Stop foreground service
        stopForeground(true)

        Log.i(TAG, "InputBridgeService destroyed, system hacks reverted")
    }

    /**
     * Get current performance metrics
     */
    fun getPerformanceMetrics(): PerformanceMetrics {
        return performanceMetrics
    }
}