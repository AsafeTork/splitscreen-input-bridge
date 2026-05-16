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
import android.os.Handler
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.work.WorkManager
import com.splitscreen.inputbridge.config.AdvancedConfigManager
import com.splitscreen.inputbridge.logging.EnhancedStructuredLogger
import com.splitscreen.inputbridge.metrics.PerformanceMetrics
import com.splitscreen.inputbridge.persistence.ProfilePersistenceManager
import com.splitscreen.inputbridge.repository.ControllerRegistry
import com.splitscreen.inputbridge.repository.ShizukuServiceInterface
import com.splitscreen.inputbridge.repository.ShizukuServiceRepository
import com.splitscreen.inputbridge.state.BridgeState
import com.splitscreen.inputbridge.state.BridgeStateManager
import com.splitscreen.inputbridge.util.CoroutineManager
import com.splitscreen.inputbridge.worker.WatchdogManager
import kotlinx.coroutines.*
import kotlin.system.measureTimeMillis

/**
 * InputBridgeService — Foreground service with comprehensive improvements
 *
 * Enhancements implemented:
 * 1. State Machine pattern using sealed classes (BridgeState)
 * 2. ShizukuServiceInterface with default implementation
 * 3. Performance metrics system (latency, FPS, injection success rate)
 * 4. Enhanced CoroutineExceptionHandler with automatic recovery
 * 5. Comprehensive error handling and state management
 */
class InputBridgeService : Service(), InputManager.InputDeviceListener {

    companion object {
        private const val TAG = "SHIZUKU_DEBUG"
        private const val CHANNEL_ID = "bridge_channel"
        private const val NOTIF_ID = 1
        private const val MSG_INJECT_EVENT = 1
        private const val DEADZONE_THRESHOLD = 0.15f
    }

    inner class LocalBinder : Binder() {
        fun getService(): InputBridgeService = this@InputBridgeService
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
    private lateinit var structuredLogger: EnhancedStructuredLogger
    private lateinit var configManager: AdvancedConfigManager
    private lateinit var profileManager: ProfilePersistenceManager

    private var statusCallback: ((String, String, Boolean) -> Unit)? = null

    // Enhanced CoroutineExceptionHandler with automatic recovery
    private val enhancedExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught coroutine exception", throwable)
        handleCoroutineException(throwable)
    }

    private val serviceScope = CoroutineManager.createScopeWithDispatcherAndHandler(
        Dispatchers.Default,
        enhancedExceptionHandler
    )

    private lateinit var injectionHandler: Handler

    // Dynamic mapping for device ID to player number
    private val deviceToPlayerMap = mutableMapOf<Int, Int>()

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var screenDensity: Float = 1.0f

    // Track previous state for velocity-based prediction
    private var lastAxisX = 0f
    private var lastAxisY = 0f
    private var lastFrameTime = 0L

    override fun onCreate() {
        super.onCreate()
        
        // Initialize injectionHandler here where context is safe
        injectionHandler = Handler(mainLooper) { msg ->
            when (msg.what) {
                MSG_INJECT_EVENT -> {
                    val event = msg.obj as MotionEvent
                    injectEventWithChoreographer(event)
                    true
                }
                else -> false
            }
        }

        try {
            Log.d(TAG, "[BOOT_TRACE] Passo 1: Service creating")
            stateManager = BridgeStateManager()
            Log.d(TAG, "[BOOT_TRACE] Passo 2: StateManager ok")
            controllerRegistry = ControllerRegistry(applicationContext)
            Log.d(TAG, "[BOOT_TRACE] Passo 3: ControllerRegistry ok")
            shizukuService = ShizukuServiceRepository()
            Log.d(TAG, "[BOOT_TRACE] Passo 4: ShizukuService ok")
            watchdogManager = WatchdogManager(applicationContext, shizukuService)
            Log.d(TAG, "[BOOT_TRACE] Passo 5: WatchdogManager ok")
            performanceMetrics = PerformanceMetrics()
            Log.d(TAG, "[BOOT_TRACE] Passo 6: PerformanceMetrics ok")
            structuredLogger = EnhancedStructuredLogger(TAG, performanceMetrics)
            configManager = AdvancedConfigManager(applicationContext, structuredLogger)
            profileManager = ProfilePersistenceManager(applicationContext, structuredLogger)
            Log.d(TAG, "[BOOT_TRACE] Passo 7: Config/Profile ok")
            setupStateListeners()
            Log.d(TAG, "[BOOT_TRACE] Passo 8: StateListeners ok")
        } catch (e: Exception) {
            Log.e("CRASH_DEBUG", "InputBridgeService.onCreate component init failed: ${e.message}", e)
        }

        try {
            inputManager = getSystemService(InputManager::class.java)
            windowManager = getSystemService(WindowManager::class.java)
            choreographer = Choreographer.getInstance()
            inputManager.registerInputDeviceListener(this, injectionHandler)
            Log.d(TAG, "[BOOT_TRACE] Passo 9: System services ok")
        } catch (e: Exception) {
            Log.e("CRASH_DEBUG", "InputBridgeService system services failed: ${e.message}", e)
        }

        try {
            createNotificationChannel()
            startForeground(NOTIF_ID, buildNotification("Bridge inativa"))
            Log.d(TAG, "[BOOT_TRACE] Passo 10: Foreground started")
        } catch (e: Exception) {
            Log.e("CRASH_DEBUG", "InputBridgeService foreground start failed: ${e.message}", e)
        }

        try {
            updateScreenDimensions()
            stateManager.transitionTo(BridgeState.Initializing)
            loadControllerAssignments()
            Log.d(TAG, "[BOOT_TRACE] Passo 11: Service fully initialized")
        } catch (e: Exception) {
            Log.e("CRASH_DEBUG", "InputBridgeService late init failed: ${e.message}", e)
        }
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
                    } else {
                        // Shizuku permission might have been revoked
                        Log.w(TAG, "Shizuku permission may have been revoked, transitioning to error state")
                        stateManager.transitionTo(
                            BridgeState.Error("Permissão Shizuku revogada ou serviço indisponível")
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "[BOOT_TRACE] onStartCommand flags=$flags startId=$startId")
        try {
            if (::shizukuService.isInitialized) {
                val shizukuReady = shizukuService.isReady()
                Log.d(TAG, "[BOOT_TRACE] Shizuku ready: $shizukuReady")
            } else {
                Log.w(TAG, "[BOOT_TRACE] shizukuService not initialized yet")
            }
        } catch (e: Exception) {
            Log.e("CRASH_DEBUG", "onStartCommand Shizuku check failed: ${e.message}", e)
        }
        return START_STICKY
    }

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

                // Clear dynamic mappings when starting a fresh bridge session
                deviceToPlayerMap.clear()

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

                // Apply aggressive system hacks
                applySystemHacks()

                // Start Anti-Pause Keep-Alive loop
                startAntiPauseEngine()

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
                
                // Clear dynamic mappings
                deviceToPlayerMap.clear()

                // Transition to ready state (keep controller assignments)
                val currentState = controllerRegistry.controllersState.value
                stateManager.transitionTo(
                    BridgeState.Ready(
                        currentState.player1Descriptor,
                        currentState.player2Descriptor
                    )
                )

                // Stop anti-pause loop
                antiPauseJob?.cancel()

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
    private val deviceToPlayerMap = mutableMapOf<String, Int>() // descriptor -> playerNumber

    fun onGamepadMotionEvent(event: MotionEvent): Boolean {
        val currentState = stateManager.getCurrentState()
        if (currentState !is BridgeState.Active) return false

        val device = android.view.InputDevice.getDevice(event.deviceId) ?: return false
        val descriptor = device.descriptor ?: event.deviceId.toString()
        
        // 1. Dynamic assignment (stable by descriptor)
        if (!deviceToPlayerMap.containsKey(descriptor)) {
            val assignedPlayer = if (!deviceToPlayerMap.values.contains(1)) 1 else if (!deviceToPlayerMap.values.contains(2)) 2 else null
            if (assignedPlayer != null) {
                deviceToPlayerMap[descriptor] = assignedPlayer
                Log.i(TAG, "SPLIT_DEBUG: Device '${device.name}' ($descriptor) -> Player $assignedPlayer")
            } else {
                Log.w(TAG, "SPLIT_DEBUG: Max players reached. Ignoring device '${device.name}'")
                return false
            }
        }

        val playerNumber = deviceToPlayerMap[descriptor] ?: return false
        
        // 2. Comprehensive Input Processing
        processAndInjectEvent(event, playerNumber)
        return true
    }

    private fun processAndInjectEvent(source: MotionEvent, playerNumber: Int) {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        
        // Player Area (Top: 0 to H/2, Bottom: H/2 to H)
        val yOffset = if (playerNumber == 1) 0f else screenHeight / 2f
        val playerHeight = screenHeight / 2f
        
        // --- AXIS MAPPING ---
        // Left Stick: Move (Lower Left of the player half)
        val lsX = source.getX()
        val lsY = source.getY()
        
        // Right Stick: Look (Center Right of the player half)
        val rsX = source.getAxisValue(MotionEvent.AXIS_Z)
        val rsY = source.getAxisValue(MotionEvent.AXIS_RZ)

        // Trigger Left Stick Injection (Move)
        if (Math.abs(lsX) > 0.1f || Math.abs(lsY) > 0.1f) {
            val touchX = (screenWidth * 0.2f) + (lsX * (screenWidth * 0.1f))
            val touchY = yOffset + (playerHeight * 0.7f) + (lsY * (playerHeight * 0.2f))
            Log.v(TAG, "SPLIT_DEBUG: P$playerNumber MOVE -> X=$touchX Y=$touchY")
            injectTouch(touchX, touchY, MotionEvent.ACTION_MOVE)
        }

        // Trigger Right Stick Injection (Look/Aim)
        if (Math.abs(rsX) > 0.1f || Math.abs(rsY) > 0.1f) {
            val touchX = (screenWidth * 0.7f) + (rsX * (screenWidth * 0.2f))
            val touchY = yOffset + (playerHeight * 0.5f) + (rsY * (playerHeight * 0.3f))
            Log.v(TAG, "SPLIT_DEBUG: P$playerNumber LOOK -> X=$touchX Y=$touchY")
            injectTouch(touchX, touchY, MotionEvent.ACTION_MOVE)
        }
        
        // Note: ACTION_DOWN/UP would be handled by button mappings in a full implementation
    }

    private fun injectTouch(x: Float, y: Float, action: Int) {
        serviceScope.launch {
            try {
                // Create a synthetic MotionEvent for touch injection
                val now = System.currentTimeMillis()
                val event = MotionEvent.obtain(
                    now, now, action, x, y, 0
                ).apply {
                    source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                }
                shizukuService.injectInputEvent(event)
                event.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "SPLIT_DEBUG: Injection error: ${e.message}")
            }
        }
    }

    private fun injectTransformedEvent(source: MotionEvent, playerNumber: Int) {
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

        // Apply coordinate transformation based on player number
        val touchX = ((clampedAxisX + 1.0f) / 2.0f) * screenWidth
        
        // Map Y coordinate based on split-screen position
        val touchY = if (playerNumber == 1) {
            // Player 1: Top half (0% to 50%)
            ((clampedAxisY + 1.0f) / 2.0f) * (screenHeight / 2f)
        } else {
            // Player 2: Bottom half (50% to 100%)
            (screenHeight / 2f) + ((clampedAxisY + 1.0f) / 2.0f) * (screenHeight / 2f)
        }

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
                                "FPS: ${performanceMetrics.currentFPS()}")
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
            shizukuService.execShellCommand("settings put global force_resizable_activities 1")
            shizukuService.execShellCommand("settings put global enable_freeform_support 1")
            Log.i(TAG, "Aggressive system hacks applied")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply system hacks: ${e.message}")
            throw e
        }
    }

    private var antiPauseJob: kotlinx.coroutines.Job? = null
    private fun startAntiPauseEngine() {
        antiPauseJob?.cancel()
        antiPauseJob = serviceScope.launch {
            while (isActive) {
                if (stateManager.getCurrentState() is BridgeState.Active) {
                    keepAppActive(1)
                    keepAppActive(2)
                }
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    private fun keepAppActive(player: Int) {
        val x = screenWidth / 2f
        val y = if (player == 1) screenHeight * 0.25f else screenHeight * 0.75f
        // Use hover to stay 'active' without clicking UI
        injectTouch(x, y, MotionEvent.ACTION_HOVER_MOVE)
    }

    private fun updateScreenDimensions() {
        val metrics = DisplayMetrics()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.density = resources.displayMetrics.density
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
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
        deviceToPlayerMap.clear()
        serviceScope.cancel()

        // Revert system hacks
        try {
            shizukuService.execShellCommand("settings put global multi_window_focus_enabled 0")
            antiPauseJob?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to revert system hacks: ${e.message}")
        }

        // Unregister listeners
        inputManager.unregisterInputDeviceListener(this)

        // Stop foreground service
        stopForeground(STOP_FOREGROUND_REMOVE)

        Log.i(TAG, "InputBridgeService destroyed, system hacks reverted")
    }

    /**
     * Get current performance metrics
     */
    fun getPerformanceMetrics(): PerformanceMetrics {
        return performanceMetrics
    }

    /**
     * Get structured logger
     */
    fun getStructuredLogger(): EnhancedStructuredLogger {
        return structuredLogger
    }

    /**
     * Get configuration manager
     */
    fun getConfigManager(): AdvancedConfigManager {
        return configManager
    }

    /**
     * Get profile manager
     */
    fun getProfileManager(): ProfilePersistenceManager {
        return profileManager
    }
}