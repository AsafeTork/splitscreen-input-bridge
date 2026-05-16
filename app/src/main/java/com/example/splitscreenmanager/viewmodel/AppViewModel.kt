package com.example.splitscreenmanager.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.hardware.input.InputManager.InputDeviceListener
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.splitscreenmanager.manager.GamepadInfo
import com.example.splitscreenmanager.manager.GamepadManager
import com.example.splitscreenmanager.manager.SplitScreenController
import com.example.splitscreenmanager.model.AppInfo
import com.example.splitscreenmanager.service.InputAccessibilityService
import com.example.splitscreenmanager.util.PerformanceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.ArrayDeque

class AppViewModel : ViewModel() {

    // Exposed for PerformanceManager to launch coroutines
    val vmScope get() = viewModelScope

    private val _appList = mutableStateListOf<AppInfo>()
    val appList: List<AppInfo> = _appList

    private val _selectedApps = mutableStateListOf<AppInfo>()
    val selectedApps: List<AppInfo> = _selectedApps

    /** Apps em ordem de seleção: índice 0 = Jogador 1 (cima), índice 1 = Jogador 2 (baixo) */
    val selectedAppsOrdered: List<AppInfo> get() = _selectedApps.toList()

    private val _isLoading = mutableStateOf(false)
    val isLoading: State<Boolean> = _isLoading

    private val _hasSecureSettingsPermission = MutableStateFlow(false)
    val hasSecureSettingsPermission: StateFlow<Boolean> = _hasSecureSettingsPermission.asStateFlow()

    private val _isShizukuAvailable = MutableStateFlow(false)
    val isShizukuAvailable: StateFlow<Boolean> = _isShizukuAvailable.asStateFlow()

    private var originalDpi: Int? = null
    private val _currentDpi = mutableStateOf(0)
    val currentDpi: State<Int> = _currentDpi

    // Gamepad Management
    private val _connectedGamepads = mutableStateListOf<InputDevice>()
    val connectedGamepads: List<InputDevice> = _connectedGamepads

    private val _player1DeviceId = mutableStateOf<Int?>(null)
    val player1DeviceId: State<Int?> = _player1DeviceId

    private val _player1Name = mutableStateOf<String?>(null)
    val player1Name: State<String?> = _player1Name

    private val _player2DeviceId = mutableStateOf<Int?>(null)
    val player2DeviceId: State<Int?> = _player2DeviceId

    private val _player2Name = mutableStateOf<String?>(null)
    val player2Name: State<String?> = _player2Name

    private val _isBinding = mutableStateOf<Int?>(null)
    val isBinding: State<Int?> = _isBinding

    private var iInputManager: IBinder? = null
    private var iActivityManager: IBinder? = null

    private val inputHandlerThread =
        HandlerThread("InputInjectionThread", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private var inputHandler: Handler? = null
    private val inputBuffer = ArrayDeque<InputEvent>(10)
    private val MAX_BUFFER_SIZE = 10

    private val _isBatteryExempt = MutableStateFlow(false)

    private val splitScreenController = SplitScreenController(this)
    private val performanceManager = PerformanceManager(this)
    private var _gamepadManager: GamepadManager? = null

    val connectedGamepadsFlow: StateFlow<List<GamepadInfo>>
        get() = _gamepadManager?.connectedGamepads ?: MutableStateFlow(emptyList())

    data class SystemLog(val command: String, val error: String, val timestamp: Long = System.currentTimeMillis())
    private val _systemLogs = MutableStateFlow<List<SystemLog>>(emptyList())
    val systemLogs: StateFlow<List<SystemLog>> = _systemLogs.asStateFlow()

    private val SHELL_TIMEOUT_MS = 8000L

    private val _isAccessibilityEnabled = MutableStateFlow(false)
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    private val _showAdbTutorial = mutableStateOf(false)
    val showAdbTutorial: State<Boolean> = _showAdbTutorial

    fun checkAccessibilityService(context: Context) {
        val expectedService = "${context.packageName}/${InputAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        _isAccessibilityEnabled.value = enabledServices?.contains(context.packageName) == true
    }

    fun refreshPermissions(context: Context) {
        updateShizukuStatus()
        checkBatteryExemption(context)
        checkAccessibilityService(context)
    }

    fun reportError(command: String, errorMsg: String) {
        addSystemLog(SystemLog(command, errorMsg))
    }

    private fun logError(command: String, e: Exception) {
        val msg = e.message ?: e.javaClass.simpleName
        addSystemLog(SystemLog(command, msg))
        Log.e("AppVM", "Shell error [$command]: $msg", e)
    }

    private fun addSystemLog(log: SystemLog) {
        _systemLogs.value = (_systemLogs.value + log).takeLast(50)
    }

    val isBatteryExempt: StateFlow<Boolean> = _isBatteryExempt.asStateFlow()

    // HDMI Management
    private val _hdmiDisplayId = mutableStateOf<Int?>(null)
    val hdmiDisplayId: State<Int?> = _hdmiDisplayId

    private val _isDesktopMode = mutableStateOf(false)
    val isDesktopMode: State<Boolean> = _isDesktopMode

    private val _hdmiResolution = mutableStateOf<Pair<Int, Int>?>(null)
    val hdmiResolution: State<Pair<Int, Int>?> = _hdmiResolution

    private val _showCalibration = mutableStateOf(false)
    val showCalibration: State<Boolean> = _showCalibration

    private val inputDeviceListener = object : InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) { updateGamepadList(null) }
        override fun onInputDeviceRemoved(deviceId: Int) { updateGamepadList(null) }
        override fun onInputDeviceChanged(deviceId: Int) { updateGamepadList(null) }
    }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        _isShizukuAvailable.value = grantResult == PackageManager.PERMISSION_GRANTED
        if (_isShizukuAvailable.value) {
            initShizukuBinders()
            applyFocusHack()
        }
    }

    init {
        inputHandler = Handler(inputHandlerThread.looper)
        setupShizukuListener()
    }

    override fun onCleared() {
        super.onCleared()
        cleanupResources()
    }

    private fun setupShizukuListener() {
        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (e: Exception) {
            Log.w("AppVM", "Failed to setup Shizuku listener", e)
        }
    }

    private fun cleanupResources() {
        inputHandlerThread.quitSafely()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener)
        } catch (e: Exception) {
            Log.w("AppVM", "Failed to remove Shizuku listener", e)
        }
    }

    fun loadApps(context: Context) {
        if (_appList.isNotEmpty()) return

        initializeManagers(context)
        initializeSystemServices(context)
        loadApplicationsAsync(context)
    }

    private fun initializeManagers(context: Context) {
        if (_gamepadManager == null) {
            _gamepadManager = GamepadManager(context)
        }
        InputAccessibilityService.splitScreenController = splitScreenController
    }

    private fun initializeSystemServices(context: Context) {
        checkSecureSettingsPermission(context)
        updateShizukuStatus()
        checkBatteryExemption(context)
        loadCurrentDpi(context)
        updateGamepadList(context)
        registerInputDeviceListener(context)
        initDisplayManager(context)
        checkAndAutoResetDpi(context)

        if (_isShizukuAvailable.value) {
            initShizukuBinders()
            applyFocusHack()
        }
    }

    private fun registerInputDeviceListener(context: Context) {
        try {
            val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
            inputManager.registerInputDeviceListener(inputDeviceListener, null)
        } catch (e: Exception) {
            Log.w("AppVM", "Failed to register InputDeviceListener", e)
        }
    }

    private fun loadApplicationsAsync(context: Context) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val apps = withContext(Dispatchers.IO) {
                    loadInstalledApplications(context)
                }
                updateAppList(apps)
            } catch (e: Exception) {
                logError("loadApps", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadInstalledApplications(context: Context): List<AppInfo> {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)

        return packageManager.queryIntentActivities(intent, 0).map { resolveInfo ->
            createAppInfo(resolveInfo, packageManager)
        }.sortedBy { it.name.lowercase() }
    }

    private fun createAppInfo(resolveInfo: android.content.pm.ResolveInfo, packageManager: PackageManager): AppInfo {
        val pkg = resolveInfo.activityInfo.packageName
        val act = resolveInfo.activityInfo.name
        val iconDrawable = resolveInfo.loadIcon(packageManager)

        return AppInfo(
            name = resolveInfo.loadLabel(packageManager).toString(),
            packageName = pkg,
            icon = iconDrawable,
            iconBitmap = iconDrawable.toBitmap().asImageBitmap(),
            activityName = "$pkg/$act"
        )
    }

    private fun updateAppList(apps: List<AppInfo>) {
        _appList.clear()
        _appList.addAll(apps)
    }

    private fun checkSecureSettingsPermission(context: Context) {
        _hasSecureSettingsPermission.value =
            context.packageManager.checkPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                context.packageName
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndAutoResetDpi(context: Context) {
        val prefs = context.getSharedPreferences("ConsolePrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("dpi_changed", false)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (_isShizukuAvailable.value) {
                    cleanupConsoleSettings()
                } else if (_hasSecureSettingsPermission.value) {
                    Settings.Secure.putString(
                        context.contentResolver,
                        "display_density_forced",
                        ""
                    )
                    withContext(Dispatchers.Main) {
                        originalDpi?.let { _currentDpi.value = it }
                    }
                }
            } catch (e: Exception) {
                logError("autoResetDpi", e)
            } finally {
                prefs.edit().putBoolean("dpi_changed", false).apply()
            }
        }
    }

    fun updateShizukuStatus() {
        _isShizukuAvailable.value = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun requestShizukuPermission(): Boolean {
        return try {
            if (Shizuku.pingBinder()) {
                Shizuku.requestPermission(0)
                true
            } else {
                Log.w("AppVM", "Shizuku service not running - ping failed")
                false
            }
        } catch (e: Exception) {
            Log.e("AppVM", "Shizuku requestPermission failed", e)
            false
        }
    }

    fun openShizukuApp(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("AppVM", "Failed to open Shizuku app", e)
        }
    }

    private fun initShizukuBinders() {
        try {
            iInputManager = ShizukuBinderWrapper(rikka.shizuku.SystemServiceHelper.getSystemService("input"))
            iActivityManager = ShizukuBinderWrapper(rikka.shizuku.SystemServiceHelper.getSystemService("activity"))
        } catch (e: Exception) {
            Log.e("AppVM", "Failed to init Shizuku binders", e)
            iInputManager = null
            iActivityManager = null
        }
    }

    fun applyFocusHack() {
        if (!_isShizukuAvailable.value) return
        viewModelScope.launch(Dispatchers.IO) {
            execShellCommand("settings put global force_resizable_activities 1")
            execShellCommand("settings put global enable_freeform_support 1")
        }
    }

    private fun initDisplayManager(context: Context) {
        try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager

            val listener = object : android.hardware.display.DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) { updateHdmiStatus(displayManager, displayId) }
                override fun onDisplayRemoved(displayId: Int) {
                    if (_hdmiDisplayId.value == displayId) {
                        _hdmiDisplayId.value = null
                        _hdmiResolution.value = null
                        _isDesktopMode.value = false
                    }
                }
                override fun onDisplayChanged(displayId: Int) { updateHdmiStatus(displayManager, displayId) }
            }
            displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            displayManager.displays.forEach { updateHdmiStatus(displayManager, it.displayId) }
        } catch (e: Exception) {
            Log.w("AppVM", "Failed to init DisplayManager", e)
        }
    }

    private fun updateHdmiStatus(displayManager: android.hardware.display.DisplayManager, displayId: Int) {
        val display = displayManager.getDisplay(displayId) ?: return
        val primaryDisplay = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return

        val isExternal = display.displayId != android.view.Display.DEFAULT_DISPLAY &&
            (display.flags and android.view.Display.FLAG_PRESENTATION != 0 ||
                display.width != primaryDisplay.width ||
                display.height != primaryDisplay.height)

        if (!isExternal) return

        _hdmiDisplayId.value = displayId
        _isDesktopMode.value =
            display.displayId != android.view.Display.DEFAULT_DISPLAY &&
                (display.width != primaryDisplay.width || display.height != primaryDisplay.height)

        val metrics = android.util.DisplayMetrics()
        display.getRealMetrics(metrics)
        _hdmiResolution.value = Pair(metrics.widthPixels, metrics.heightPixels)

        _showCalibration.value = true
        viewModelScope.launch {
            delay(5000)
            _showCalibration.value = false
        }
    }

    fun injectInputEvent(event: InputEvent, displayId: Int = android.view.Display.DEFAULT_DISPLAY) {
        if (!_isShizukuAvailable.value || iInputManager == null) return

        inputHandler?.post {
            val startTime = System.nanoTime()
            try {
                val methodId = resolveInjectMethodId()
                val data = android.os.Parcel.obtain()
                val reply = android.os.Parcel.obtain()
                try {
                    data.writeInterfaceToken("android.hardware.input.IInputManager")
                    if (event is MotionEvent) {
                        data.writeInt(1)
                        event.writeToParcel(data, 0)
                    } else if (event is KeyEvent) {
                        data.writeInt(2)
                        event.writeToParcel(data, 0)
                    }
                    data.writeInt(0) // ASYNC mode
                    iInputManager?.transact(methodId, data, reply, 0)
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } catch (e: Exception) {
                Log.e("AppVM", "injectInputEvent failed", e)
            } finally {
                val latencyNs = System.nanoTime() - startTime
                val latencyMs = latencyNs / 1_000_000.0
                if (latencyMs > 16.0) {
                    Log.w("PerformanceLogger", "Latência alta: ${String.format("%.2f", latencyMs)}ms")
                }
            }
        }
    }

    private fun resolveInjectMethodId(): Int {
        // Transaction IDs vary by Android version. Fallback to safest option.
        return try {
            val sdk = android.os.Build.VERSION.SDK_INT
            when {
                sdk >= 34 -> 8  // Android 14+
                sdk >= 31 -> 7  // Android 12-13
                sdk >= 29 -> 6  // Android 10-11
                else -> 5
            }
        } catch (_: Exception) {
            7
        }
    }

    fun remapAndInjectForPlayer2(event: MotionEvent, screenHeight: Int, isHdmi: Boolean = false) {
        val remappedEvent = createRemappedEvent(event, screenHeight, isHdmi)
        bufferAndInjectEvent(remappedEvent)
    }

    private fun createRemappedEvent(event: MotionEvent, screenHeight: Int, isHdmi: Boolean): MotionEvent {
        val targetHeight = if (isHdmi) (_hdmiResolution.value?.second ?: screenHeight) else screenHeight
        val offsetY = targetHeight / 2f

        val newEvent = MotionEvent.obtain(
            event.downTime, event.eventTime, event.action,
            event.x, event.y + offsetY, event.metaState
        )
        newEvent.source = event.source
        return newEvent
    }

    private fun bufferAndInjectEvent(event: MotionEvent) {
        synchronized(inputBuffer) {
            if (inputBuffer.size >= MAX_BUFFER_SIZE) {
                inputBuffer.poll()?.let { if (it is MotionEvent) it.recycle() }
            }
            inputBuffer.offer(event)
        }

        inputHandler?.post {
            val nextEvent = synchronized(inputBuffer) { inputBuffer.poll() }
            nextEvent?.let {
                injectInputEvent(it)
                if (it is MotionEvent) it.recycle()
            }
        }
    }

    fun execShellCommand(command: String): String {
        return try {
            val process = createShellProcess(command)
            val output = readProcessOutput(process)
            handleProcessCompletion(process, command)
            output
        } catch (e: Exception) {
            handleShellCommandError(command, e)
            e.message ?: "Error"
        }
    }

    private fun createShellProcess(command: String): Process {
        return if (_isShizukuAvailable.value) {
            newShizukuShellProcess(command)
        } else {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        }
    }

    private fun readProcessOutput(process: Process): String {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            output.append(line).append("\n")
        }
        return output.toString()
    }

    private fun handleProcessCompletion(process: Process, command: String): Boolean {
        val finished = process.waitFor(SHELL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            logError(command, java.util.concurrent.TimeoutException("Shell timeout after ${SHELL_TIMEOUT_MS}ms"))
        }
        return finished
    }

    private fun handleShellCommandError(command: String, e: Exception) {
        logError(command, e)
    }

    fun launchConsoleMode(context: Context, targetDpi: Int) {
        if (!validatePrerequisites()) return

        viewModelScope.launch(Dispatchers.IO) {
            updateShizukuStatus()
            try {
                setupConsoleEnvironment(context)
                launchSplitScreenApps()
                applyPostLaunchOptimizations(context, targetDpi)
                Log.d("AppVM", "=== CONSOLE MODE ACTIVE ===")
            } catch (e: Exception) {
                handleLaunchError(e)
            }
        }
    }

    private fun validatePrerequisites(): Boolean {
        if (_selectedApps.size < 2) {
            reportError("Launch Console", "Selecione 2 apps primeiro")
            return false
        }
        return true
    }

    private suspend fun setupConsoleEnvironment(context: Context) {
        syncAccessibilityService()
        splitScreenController.activate()
        configureScreenDimensions(context)
        applySystemOptimizations()
        applyGamingOptimizations()
    }

    private fun configureScreenDimensions(context: Context) {
        val dm = context.resources.displayMetrics
        splitScreenController.setScreenDimensions(dm.widthPixels, dm.heightPixels)
    }

    private fun applySystemOptimizations() {
        val commands = listOf(
            "settings put global policy_control immersive.full=*",
            "wm overscan 0,0,0,0",
            "settings put global window_animation_scale 0.0",
            "settings put global transition_animation_scale 0.0",
            "settings put global animator_duration_scale 0.0"
        )
        commands.forEach { execShellCommand(it) }
    }

    private fun applyGamingOptimizations() {
        val commands = listOf(
            "settings put global force_desktop_mode_on_external_displays 0",
            "settings put global always_finish_activities 0",
            "settings put global package_verifier_enable 0",
            "settings put global development_settings_enabled 0",
            "settings put global game_mode 1",
            "echo 0 > /proc/sys/kernel/sched_child_runs_first",
            "echo 1 > /proc/sys/vm/swappiness"
        )
        commands.forEach { execShellCommand(it) }
    }

    private suspend fun launchSplitScreenApps() {
        val appTop = _selectedApps[0]
        val appBottom = _selectedApps[1]

        Log.d("AppVM", "=== LAUNCHING SPLIT-SCREEN ===")
        Log.d("AppVM", "TOP (P1): ${appTop.name} (${appTop.packageName})")
        Log.d("AppVM", "BOTTOM (P2): ${appBottom.name} (${appBottom.packageName})")

        launchSplitScreen(appTop, appBottom)
        performanceManager.applyDeepOptimizations(appTop.packageName, appBottom.packageName)
    }

    private suspend fun applyPostLaunchOptimizations(context: Context, targetDpi: Int) {
        execShellCommand("wm density $targetDpi")
        withContext(Dispatchers.Main) {
            _currentDpi.value = targetDpi
        }
    }

    private fun handleLaunchError(e: Exception) {
        logError("launchConsoleMode", e)
        reportError("Launch Console", "Erro ao iniciar: ${e.message}")
    }

    private fun launchSplitScreen(appTop: AppInfo, appBottom: AppInfo) {
        val topPkg = appTop.packageName
        val bottomPkg = appBottom.packageName
        val topAct = appTop.activityName ?: "$topPkg/.MainActivity"
        val bottomAct = appBottom.activityName ?: "$bottomPkg/.MainActivity"

        try {
            clearExistingSplitState()
            launchAppsInSplitScreenMode(topAct, bottomAct)
            applyFallbackSplitScreenCommands(topAct, bottomAct)
            forceRecentsToShowSplitView()
            Log.d("AppVM", "✓ Split-screen launched: TOP=$topPkg, BOTTOM=$bottomPkg")
        } catch (e: Exception) {
            logError("launchSplitScreen", e)
        }
    }

    private fun clearExistingSplitState() {
        execShellCommand("am kill-all 2>/dev/null || true")
    }

    private fun launchAppsInSplitScreenMode(topAct: String, bottomAct: String) {
        // Use Android 10+ windowing modes
        execShellCommand("am start --windowingMode 3 -n $topAct")
        Thread.sleep(1000)

        execShellCommand("am start --windowingMode 4 -n $bottomAct")
        Thread.sleep(1000)
    }

    private fun applyFallbackSplitScreenCommands(topAct: String, bottomAct: String) {
        val screenW = 1080
        val screenH = 2400
        val halfH = screenH / 2

        // Fallback commands if windowingMode fails
        execShellCommand("am move-task --displayId 0 top 0,$halfH,$screenW,$halfH 2>/dev/null || true")
        execShellCommand("am move-task --displayId 0 bottom 0,0,$screenW,$halfH 2>/dev/null || true")

        // Alternative stack-based approach
        execShellCommand("am start-activity -n $topAct --task bring-to-front --windowing-mode 3 2>/dev/null || true")
        execShellCommand("am start-activity -n $bottomAct --task bring-to-front --windowing-mode 4 2>/dev/null || true")
    }

    private fun forceRecentsToShowSplitView() {
        execShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        execShellCommand("input swipe 540 1200 540 2000")
        Thread.sleep(500)
    }

    fun cleanupConsoleSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            splitScreenController.deactivate()
            performanceManager.restoreSystemSettings()

            execShellCommand("wm density reset")
            execShellCommand("settings put global policy_control null")
            execShellCommand("wm overscan reset")
            execShellCommand("settings put global window_animation_scale 1.0")
            execShellCommand("settings put global transition_animation_scale 1.0")
            execShellCommand("settings put global animator_duration_scale 1.0")
        }
    }

    private fun newShizukuShellProcess(command: String): java.lang.Process {
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            m.invoke(null, arrayOf("sh", "-c", command), null, null) as java.lang.Process
        } catch (e: Exception) {
            throw RuntimeException("Failed to create Shizuku shell process: ${e.message}", e)
        }
    }

    fun updateGamepadList(context: Context?) {
        val inputManager = context?.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return
        try {
            val devices = findConnectedGamepads(inputManager)
            updateGamepadDevices(devices)
            updatePlayerDeviceAssignments(devices)
        } catch (e: Exception) {
            Log.w("AppVM", "Failed to update gamepad list", e)
        }
    }

    private fun findConnectedGamepads(inputManager: InputManager): List<InputDevice> {
        return inputManager.inputDeviceIds.toList().mapNotNull { id ->
            val device = inputManager.getInputDevice(id)
            if (device != null && isGamepadDevice(device)) {
                device
            } else null
        }
    }

    private fun isGamepadDevice(device: InputDevice): Boolean {
        return (device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
    }

    private fun updateGamepadDevices(devices: List<InputDevice>) {
        _connectedGamepads.clear()
        _connectedGamepads.addAll(devices)
    }

    private fun updatePlayerDeviceAssignments(devices: List<InputDevice>) {
        if (devices.isNotEmpty()) {
            _player1DeviceId.value = devices[0].id
            _player1Name.value = devices[0].name
            _player2DeviceId.value = if (devices.size > 1) devices[1].id else null
            _player2Name.value = if (devices.size > 1) devices[1].name else null
        } else {
            clearPlayerDeviceAssignments()
        }
    }

    private fun clearPlayerDeviceAssignments() {
        _player1DeviceId.value = null
        _player1Name.value = null
        _player2DeviceId.value = null
        _player2Name.value = null
    }

    fun startBinding(player: Int) {
        initializeBindingState(player)
        monitorBindingProgress(player)
    }

    private fun initializeBindingState(player: Int) {
        _isBinding.value = player
        _gamepadManager?.startBinding(player)
        InputAccessibilityService.lastCapturedDeviceId = null
    }

    private fun monitorBindingProgress(player: Int) {
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val timeout = 10000L

            while (System.currentTimeMillis() - startTime < timeout && _isBinding.value == player) {
                val capturedId = InputAccessibilityService.lastCapturedDeviceId
                if (capturedId != null) {
                    handleDeviceCaptured(capturedId, player)
                    break
                }
                delay(200)
            }

            cleanupBindingState(player)
        }
    }

    private fun handleDeviceCaptured(capturedId: Int, player: Int) {
        val device = _connectedGamepads.find { it.id == capturedId }
        if (device != null) {
            updatePlayerDevice(player, capturedId, device.name)
            syncAccessibilityService()
            _isBinding.value = null
        }
    }

    private fun updatePlayerDevice(player: Int, deviceId: Int, deviceName: String) {
        if (player == 1) {
            _player1DeviceId.value = deviceId
            _player1Name.value = deviceName
        } else {
            _player2DeviceId.value = deviceId
            _player2Name.value = deviceName
        }
    }

    private fun cleanupBindingState(player: Int) {
        if (_isBinding.value == player) {
            _isBinding.value = null
        }
    }

    private fun syncAccessibilityService() {
        InputAccessibilityService.splitScreenController = splitScreenController
        InputAccessibilityService.player1DeviceId = _player1DeviceId.value
        InputAccessibilityService.player2DeviceId = _player2DeviceId.value
    }

    private fun loadCurrentDpi(context: Context) {
        val density = context.resources.configuration.densityDpi
        _currentDpi.value = density
        if (originalDpi == null) originalDpi = density
    }

    fun checkBatteryExemption(context: Context) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            _isBatteryExempt.value = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Log.w("AppVM", "Failed to check battery exemption", e)
        }
    }

    fun requestBatteryExemption(context: Context) {
        if (_isBatteryExempt.value) return
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w("AppVM", "Failed to request battery exemption", e)
        }
    }

    fun toggleAdbTutorial() {
        _showAdbTutorial.value = !_showAdbTutorial.value
    }

    fun changeDisplayDPI(context: Context, targetDpi: Int) {
        // REMOVED - NÃO Usa mais. Usar launchConsoleMode ao invés
        Log.w("AppVM", "changeDisplayDPI deprecated - use launchConsoleMode")
    }

    fun restoreOriginalDPI(context: Context) {
        if (!_hasSecureSettingsPermission.value) {
            if (_isShizukuAvailable.value) {
                cleanupConsoleSettings()
                context.getSharedPreferences("ConsolePrefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("dpi_changed", false).apply()
            }
            return
        }
        try {
            Settings.Secure.putString(context.contentResolver, "display_density_forced", "")
            originalDpi?.let { _currentDpi.value = it }
            context.getSharedPreferences("ConsolePrefs", Context.MODE_PRIVATE)
                .edit().putBoolean("dpi_changed", false).apply()
        } catch (e: Exception) {
            logError("restoreDPI", e)
        }
    }

    fun toggleSelection(app: AppInfo) {
        if (_selectedApps.contains(app)) {
            _selectedApps.remove(app)
        } else if (_selectedApps.size < 2) {
            _selectedApps.add(app)
        }
    }

    fun startHotspot(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = execShellCommand("svc wifi tether start-tethering wifi")
                if (result != "TIMEOUT") {
                    execShellCommand("settings put global wifi_sleep_policy 2")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    try {
                        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun getHotspotIP(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.name.startsWith("ap", ignoreCase = true) }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()?.hostAddress ?: "127.0.0.1"
        } catch (_: Exception) {
            "127.0.0.1"
        }
    }

    fun isButtonEnabled(): Boolean = _selectedApps.size == 2
}