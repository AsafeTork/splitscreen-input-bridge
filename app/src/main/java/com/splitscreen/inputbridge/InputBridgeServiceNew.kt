package com.splitscreen.inputbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.work.WorkManager
import com.splitscreen.inputbridge.repository.ControllerRegistry
import com.splitscreen.inputbridge.repository.ShizukuServiceInterface
import com.splitscreen.inputbridge.repository.ShizukuServiceRepository
import com.splitscreen.inputbridge.state.BridgeState
import com.splitscreen.inputbridge.state.BridgeStateManager
import com.splitscreen.inputbridge.worker.WatchdogManager
import kotlinx.coroutines.*

/**
 * InputBridgeService — Foreground service with improved architecture
 *
 * This version implements:
 * 1. State Machine pattern for BridgeState management
 * 2. Repository pattern for controller management
 * 3. Dependency injection for Shizuku operations
 * 4. WorkManager for intelligent watchdog scheduling
 */
class InputBridgeServiceNew : Service(), InputManager.InputDeviceListener {

    companion object {
        private const val TAG = "InputBridgeServiceNew"
        private const val CHANNEL_ID = "bridge_channel"
        private const val NOTIF_ID = 1
        private const val MSG_INJECT_EVENT = 1
        private const val DEADZONE_THRESHOLD = 0.15f
    }

    inner class LocalBinder : Binder() {
        fun getService(): InputBridgeServiceNew = this@InputBridgeServiceNew
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

    private var statusCallback: ((String, String, Boolean) -> Unit)? = null

    private val serviceScope = CoroutineScope(
        Dispatchers.Default +
        SupervisorJob() +
        CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Uncaught coroutine exception", throwable)
            stateManager.transitionTo(BridgeState.Error("Service error: ${throwable.message}", throwable))
        }
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

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service creating with new architecture")

        // Initialize architecture components
        stateManager = BridgeStateManager()
        controllerRegistry = ControllerRegistry(applicationContext)
        shizukuService = ShizukuServiceRepository()
        watchdogManager = WatchdogManager(applicationContext, shizukuService)

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

        Log.d(TAG, "Service initialized")
    }

    private fun setupStateListeners() {
        stateManager.addListener { state ->
            when (state) {
                is BridgeState.Idle -> {
                    updateNotification("Bridge inativa")
                    statusCallback?.invoke("", "", false)
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
                }
                is BridgeState.Stopping -> {
                    updateNotification("Desativando...")
                }
                is BridgeState.Error -> {
                    updateNotification("Erro: ${state.message}")
                    statusCallback?.invoke("", "", false)
                }
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
                    shizukuService.injectInputEvent(event)
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
}
