@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.splitscreen.inputbridge

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.splitscreen.inputbridge.repository.ShizukuPermissionManager
import com.splitscreen.inputbridge.ui.theme.SplitScreenInputBridgeTheme
import com.splitscreen.inputbridge.util.ShizukuDiagnosticUtil
import com.splitscreen.inputbridge.util.ShizukuMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), InputManager.InputDeviceListener {

    // ===== NUCLEAR LOGGING =====
    private val logFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun tsLog(tag: String, msg: String) {
        val ts = logFormatter.format(Date())
        Log.d("SHIZUKU_DEBUG", "[$ts] [$tag] $msg")
    }

    private fun visibleToast(msg: String) {
        Toast.makeText(this, "InputBridge: $msg", Toast.LENGTH_LONG).show()
        tsLog("TOAST", msg)
    }

    private lateinit var inputManager: InputManager
    private var bridgeService: InputBridgeService? = null
    private lateinit var shizukuPermissionManager: ShizukuPermissionManager
    private lateinit var shizukuMonitor: ShizukuMonitor
    private val handler = Handler(Looper.getMainLooper())
    private val permissionCheckRunnable = object : Runnable {
        override fun run() {
            checkShizukuPermission()
            handler.postDelayed(this, 2000) // Check every 2 seconds for better responsiveness
        }
    }

    private val _uiState = MutableStateFlow(BridgeUiState())
    private val uiState: StateFlow<BridgeUiState> = _uiState

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d("MainActivity", "Service connected")
            bridgeService = (binder as? InputBridgeService.LocalBinder)?.getService()
            if (bridgeService != null) {
                Log.d("MainActivity", "Bridge service obtained successfully")
                bridgeService?.setStatusCallback { p1, p2, active ->
                    Log.d("MainActivity", "Service status callback: p1=$p1, p2=$p2, active=$active")
                    _uiState.value = _uiState.value.copy(
                        player1Descriptor = p1,
                        player2Descriptor = p2,
                        bridgeActive = active
                    )
                }
                _uiState.value = _uiState.value.copy(serviceConnected = true)
                Log.d("MainActivity", "Service connected state updated")
            } else {
                Log.e("MainActivity", "Failed to obtain bridge service")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d("MainActivity", "Service disconnected")
            bridgeService = null
            _uiState.value = _uiState.value.copy(serviceConnected = false, bridgeActive = false)
        }
    }

    private val shizukuRequestCode = 100
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == shizukuRequestCode) {
            val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            tsLog("PERMISSION_CALLBACK", "requestCode=$requestCode granted=$granted")
            visibleToast(if (granted) "Permissão Shizuku CONCEDIDA!" else "Permissão Shizuku NEGADA")
            _uiState.value = _uiState.value.copy(shizukuGranted = granted)
            if (granted) {
                tsLog("BINDING", "Permission granted, binding service now")
                bindBridgeService()
                startPermissionMonitoring()
            }
        }
    }

    // Held as class property to prevent GC
    private val shizukuPermissionChangeListener = Shizuku.OnBinderReceivedListener {
        tsLog("BINDER", "Binder received event fired")
        handler.post { checkShizukuPermission() }
    }

    private val shizukuBinderReceivedStickyListener = Shizuku.OnBinderReceivedListener {
        tsLog("BINDER_STICKY", "Sticky binder received event fired")
        val granted = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        tsLog("BINDER_STICKY", "checkSelfPermission=$granted")
        updateShizukuPermissionState(granted)
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        tsLog("BINDER_DEAD", "Binder died - shutting down")
        visibleToast("Shizuku desconectado! Verifique o app Shizuku")
        _uiState.value = _uiState.value.copy(
            shizukuAvailable = false, shizukuGranted = false,
            serviceConnected = false, bridgeActive = false,
            statusMessage = "Shizuku não disponível"
        )
        stopPermissionMonitoring()
    }

    private fun safeBinderAlive(): Boolean = try { Shizuku.pingBinder() } catch (_: Exception) { false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            inputManager = getSystemService(InputManager::class.java)
            inputManager.registerInputDeviceListener(this, null)
        } catch (e: Exception) {
            Log.e("CRASH_DEBUG", "inputManager init failed: ${e.message}", e)
        }

        // SAFE: Shizuku init — wrapped to prevent crash
        try {
            shizukuPermissionManager = ShizukuPermissionManager(this)
        } catch (e: Exception) {
            Log.e("CRASH_DEBUG", "ShizukuPermissionManager init failed: ${e.message}", e)
        }

        // Defer Shizuku listener registration to avoid crash during init
        handler.postDelayed({
            try {
                shizukuMonitor = ShizukuMonitor(this).apply {
                    addListener(object : ShizukuMonitor.ShizukuStatusListener {
                        override fun onShizukuStatusChanged(available: Boolean, permissionGranted: Boolean) {
                            try {
                                updateShizukuPermissionState(permissionGranted)
                            } catch (e: Exception) {
                                Log.e("CRASH_DEBUG", "onShizukuStatusChanged error: ${e.message}", e)
                            }
                        }
                        override fun onShizukuBinderDied() {
                            try {
                                _uiState.value = _uiState.value.copy(
                                    shizukuAvailable = false, shizukuGranted = false,
                                    serviceConnected = false, bridgeActive = false,
                                    statusMessage = "Shizuku não disponível"
                                )
                                stopPermissionMonitoring()
                            } catch (e: Exception) {
                                Log.e("CRASH_DEBUG", "onShizukuBinderDied error: ${e.message}", e)
                            }
                        }
                    })
                    setCheckInterval(500)
                    if (safeBinderAlive()) startMonitoring()
                }
            } catch (e: Exception) {
                Log.e("CRASH_DEBUG", "ShizukuMonitor init failed: ${e.message}", e)
            }

            try { Shizuku.addRequestPermissionResultListener(shizukuListener) } catch (e: Exception) { Log.e("CRASH_DEBUG", "addRequestPermissionResultListener failed: ${e.message}", e) }
            try { Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedStickyListener) } catch (e: Exception) { Log.e("CRASH_DEBUG", "addBinderReceivedSticky failed: ${e.message}", e) }
            try { Shizuku.addBinderReceivedListener(shizukuPermissionChangeListener) } catch (e: Exception) { Log.e("CRASH_DEBUG", "addBinderReceivedListener failed: ${e.message}", e) }
            try { Shizuku.addBinderDeadListener(shizukuBinderDeadListener) } catch (e: Exception) { Log.e("CRASH_DEBUG", "addBinderDeadListener failed: ${e.message}", e) }
        }, 500)

        try {
            refreshGamepads()
        } catch (e: Exception) {
            Log.e("CRASH_DEBUG", "refreshGamepads failed: ${e.message}", e)
        }

        setContent {
            SplitScreenInputBridgeTheme {
                val state by uiState.collectAsStateWithLifecycle()
                BridgeScreen(
                    state = state,
                    onRequestShizuku = { requestShizukuPermission() },
                    onBindPlayer1 = { descriptor -> bindPlayer(1, descriptor) },
                    onBindPlayer2 = { descriptor -> bindPlayer(2, descriptor) },
                    onToggleBridge = { toggleBridge() },
                    onRefreshDevices = { refreshGamepads() },
                    onRunDiagnostics = { runShizukuDiagnostics() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings(this@MainActivity) }
                )
            }
        }
    }

    private fun refreshGamepads() {
        Log.d("MainActivity", "Refreshing gamepads")
        val gamepads = InputDevice.getDeviceIds().toList()
            .mapNotNull { InputDevice.getDevice(it) }
            .filter { device ->
                device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                        device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            }
            .map { GamepadInfo(id = it.id, name = it.name, descriptor = it.descriptor) }

        Log.d("MainActivity", "Found ${gamepads.size} gamepads")
        _uiState.value = _uiState.value.copy(availableGamepads = gamepads)
    }

    private fun requestShizukuPermission() {
        tsLog("REQUEST", "Starting permission request flow")
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            tsLog("REQUEST", "ERROR: Shizuku version incompatible - version=${Shizuku.getVersion()} isPreV11=${Shizuku.isPreV11()}")
            visibleToast("Shizuku versão incompatível (necessário v11+)")
            _uiState.value = _uiState.value.copy(statusMessage = "Shizuku versão incompatível (necessário v11+)")
            return
        }
        tsLog("REQUEST", "Calling Shizuku.requestPermission($shizukuRequestCode)")
        Shizuku.requestPermission(shizukuRequestCode)
        visibleToast("Solicitando permissão Shizuku... Verifique o popup!")
    }

    private fun updateShizukuPermissionState(granted: Boolean) {
        Log.d("MainActivity", "Updating Shizuku permission state: granted=$granted")

        // Update UI state with new permission status
        val currentState = _uiState.value
        var newState = currentState.copy(
            shizukuAvailable = true,
            shizukuGranted = granted
        )

        // If permission was granted and service is not connected, try to connect
        if (granted && !currentState.serviceConnected) {
            Log.d("MainActivity", "Shizuku permission granted, attempting to bind service")
            bindBridgeService()
            newState = newState.copy(
                statusMessage = "Permissão concedida, conectando serviço..."
            )
        }

        // If permission was revoked, update service connection state
        if (!granted && currentState.serviceConnected) {
            Log.d("MainActivity", "Shizuku permission revoked, updating service state")
            newState = newState.copy(
                serviceConnected = false,
                bridgeActive = false,
                statusMessage = "Permissão Shizuku revogada"
            )
        }

        // Update state only if it has changed
        if (newState != currentState) {
            Log.d("MainActivity", "UI state changed, updating - old: shizukuAvailable=${currentState.shizukuAvailable}, shizukuGranted=${currentState.shizukuGranted}, serviceConnected=${currentState.serviceConnected}; new: shizukuAvailable=${newState.shizukuAvailable}, shizukuGranted=${newState.shizukuGranted}, serviceConnected=${newState.serviceConnected}")
            _uiState.value = newState
        } else {
            Log.d("MainActivity", "UI state unchanged")
        }

        // Start or stop permission monitoring based on permission status
        if (granted) {
            startPermissionMonitoring()
        } else {
            stopPermissionMonitoring()
        }
    }

    private fun checkShizukuPermission() {
        try {
            val isBinderAlive = Shizuku.pingBinder()
            val isPermissionGranted = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            val isAccessibilityEnabled = isAccessibilityServiceEnabled(this)

            tsLog("CHECK", "binderAlive=$isBinderAlive permissionGranted=$isPermissionGranted accessibilityEnabled=$isAccessibilityEnabled")

            // Update UI state if needed
            val currentState = _uiState.value
            var newState = currentState.copy(
                shizukuAvailable = isBinderAlive,
                shizukuGranted = isPermissionGranted,
                accessibilityServiceEnabled = isAccessibilityEnabled,
                // Set troubleshooting flags
                needsAccessibilitySetup = !isAccessibilityEnabled,
                needsShizukuSetup = isBinderAlive && !isPermissionGranted,
                // Set specific troubleshooting messages
                troubleshootingMessage = when {
                    !isBinderAlive -> "📱 Instale e inicie o app Shizuku"
                    !isPermissionGranted -> "🔑 Conceda permissão ao Shizuku no app Shizuku"
                    !isAccessibilityEnabled -> "⚙️ Habilite o serviço de acessibilidade nas Configurações > Acessibilidade"
                    else -> ""
                }
            )

            // If permission was revoked, update service connection state
            if (!isPermissionGranted && currentState.serviceConnected) {
                Log.d("MainActivity", "Shizuku permission revoked, updating service state")
                newState = newState.copy(
                    serviceConnected = false,
                    bridgeActive = false,
                    statusMessage = "Permissão Shizuku revogada"
                )
            }

            // If permission was granted and service is not connected, try to connect
            if (isPermissionGranted && !currentState.serviceConnected && isBinderAlive) {
                Log.d("MainActivity", "Shizuku permission granted and service not connected, attempting to bind")
                bindBridgeService()
                newState = newState.copy(
                    statusMessage = "Permissão concedida, conectando serviço..."
                )
            }

            // Update state only if it has changed
            if (newState != currentState) {
                Log.d("MainActivity", "UI state changed, updating - old: shizukuAvailable=${currentState.shizukuAvailable}, shizukuGranted=${currentState.shizukuGranted}, serviceConnected=${currentState.serviceConnected}, accessibilityEnabled=${currentState.accessibilityServiceEnabled}; new: shizukuAvailable=${newState.shizukuAvailable}, shizukuGranted=${newState.shizukuGranted}, serviceConnected=${newState.serviceConnected}, accessibilityEnabled=${newState.accessibilityServiceEnabled}")
                _uiState.value = newState
            } else {
                Log.d("MainActivity", "UI state unchanged")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking Shizuku permission", e)
        }
    }

    private fun startPermissionMonitoring() {
        Log.d("MainActivity", "Starting permission monitoring")
        handler.post(permissionCheckRunnable)
    }

    private fun stopPermissionMonitoring() {
        Log.d("MainActivity", "Stopping permission monitoring")
        handler.removeCallbacks(permissionCheckRunnable)
    }

    private fun bindBridgeService() {
        tsLog("BINDING", "Attempting to bind InputBridgeService")
        val intent = Intent(this, InputBridgeService::class.java)
        startForegroundService(intent)
        val result = bindService(intent, serviceConnection, 0)
        tsLog("BINDING", "bindService result=$result (true=OK, false=FAIL)")
        if (!result) {
            visibleToast("Falha ao conectar serviço interno!")
        }
    }

    private fun bindPlayer(player: Int, descriptor: String) {
        bridgeService?.assignGamepad(player, descriptor)
        val current = _uiState.value
        if (player == 1) {
            _uiState.value = current.copy(player1Descriptor = descriptor)
        } else {
            _uiState.value = current.copy(player2Descriptor = descriptor)
        }
    }

    private fun toggleBridge() {
        val active = _uiState.value.bridgeActive
        if (active) {
            bridgeService?.stopBridge()
        } else {
            bridgeService?.startBridge()
        }
        _uiState.value = _uiState.value.copy(bridgeActive = !active)
    }

    private fun runShizukuDiagnostics() {
        Log.d("MainActivity", "Running Shizuku diagnostics")
        Thread {
            try {
                val diagnosticResult = ShizukuDiagnosticUtil.performDiagnostic(this)
                ShizukuDiagnosticUtil.logDiagnosticResults(diagnosticResult)

                val suggestions = ShizukuDiagnosticUtil.getTroubleshootingSuggestions(diagnosticResult)

                // Log suggestions
                Log.i("MainActivity", "=== Troubleshooting Suggestions ===")
                suggestions.forEach { suggestion ->
                    Log.i("MainActivity", "Suggestion: $suggestion")
                }
                Log.i("MainActivity", "==================================")

                // Update UI with diagnostic results
                this@MainActivity.runOnUiThread {
                    val issueCount = diagnosticResult.issues.size
                    val message = if (issueCount == 0) {
                        "Diagnóstico completo: Nenhum problema encontrado"
                    } else {
                        "Diagnóstico completo: $issueCount problemas encontrados"
                    }

                    // Create detailed status message
                    val detailedMessage = buildString {
                        append("Shizuku: ")
                        append(if (diagnosticResult.shizukuAvailable) "Disponível" else "Não encontrado")
                        append(" | Permissão: ")
                        append(if (diagnosticResult.permissionGranted) "Concedida" else "Não concedida")
                        append(" | Binder: ")
                        append(if (diagnosticResult.binderAlive) "Ativo" else "Inativo")

                        if (diagnosticResult.binderAlive && !diagnosticResult.permissionGranted) {
                            append("\nPermissão pode estar dessincronizada - tente reiniciar os apps")
                        }

                        if (issueCount > 0) {
                            append("\nIssues: $issueCount")
                        }
                    }

                    _uiState.value = _uiState.value.copy(
                        statusMessage = message,
                        detailedStatus = detailedMessage
                    )
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error running diagnostics", e)
                this@MainActivity.runOnUiThread {
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "Erro no diagnóstico: ${e.message}",
                        detailedStatus = "Falha no diagnóstico - veja logs para detalhes"
                    )
                }
            }
        }.start()
    }

    override fun onInputDeviceAdded(deviceId: Int) = refreshGamepads()
    override fun onInputDeviceRemoved(deviceId: Int) = refreshGamepads()
    override fun onInputDeviceChanged(deviceId: Int) = refreshGamepads()

    override fun onResume() {
        super.onResume()
        // Check Shizuku permission immediately when activity resumes
        handler.post {
            checkShizukuPermission()
            // Also force a check with the monitor
            shizukuMonitor.forceCheckStatus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        inputManager.unregisterInputDeviceListener(this)
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        Shizuku.removeBinderReceivedListener(shizukuPermissionChangeListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedStickyListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        stopPermissionMonitoring()
        shizukuMonitor.stopMonitoring()
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {}
    }

    // --- Accessibility helpers (must be inside MainActivity to access contentResolver/startActivity) ---

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponentName = ComponentName(context, InputBridgeAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error opening accessibility settings", e)
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                context.startActivity(intent)
            } catch (e2: Exception) {
                Log.e("MainActivity", "Error opening main settings", e2)
            }
        }
    }
}

data class GamepadInfo(val id: Int, val name: String, val descriptor: String)

data class BridgeUiState(
    val shizukuAvailable: Boolean = false,
    val shizukuGranted: Boolean = false,
    val serviceConnected: Boolean = false,
    val bridgeActive: Boolean = false,
    val availableGamepads: List<GamepadInfo> = emptyList(),
    val player1Descriptor: String = "",
    val player2Descriptor: String = "",
    val statusMessage: String = "",
    val diagnosticMode: Boolean = false,
    val detailedStatus: String = "", // For showing more detailed diagnostic information
    val accessibilityServiceEnabled: Boolean = false, // Track accessibility service status
    val needsAccessibilitySetup: Boolean = false, // Whether user needs to enable accessibility
    val needsShizukuSetup: Boolean = false, // Whether user needs to grant Shizuku permission
    val troubleshootingMessage: String = "" // Specific troubleshooting guidance
)

@Composable
fun BridgeScreen(
    state: BridgeUiState,
    onRequestShizuku: () -> Unit,
    onBindPlayer1: (String) -> Unit,
    onBindPlayer2: (String) -> Unit,
    onToggleBridge: () -> Unit,
    onRefreshDevices: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "SplitScreen Input Bridge",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            StatusCard(state = state, onRequestShizuku = onRequestShizuku, onRunDiagnostics = onRunDiagnostics, onOpenAccessibility = onOpenAccessibilitySettings)

            GamepadBindingCard(
                title = "Player 1 (Nativo)",
                accentColor = Color(0xFF2196F3),
                boundDescriptor = state.player1Descriptor,
                gamepads = state.availableGamepads,
                onBind = onBindPlayer1,
                onRefresh = onRefreshDevices
            )

            GamepadBindingCard(
                title = "Player 2 (Bridge via Shizuku)",
                accentColor = Color(0xFFFF5722),
                boundDescriptor = state.player2Descriptor,
                gamepads = state.availableGamepads,
                onBind = onBindPlayer2,
                onRefresh = onRefreshDevices
            )

            BridgeControlCard(
                state = state,
                onToggle = onToggleBridge
            )
        }
    }
}

@Composable
fun StatusCard(state: BridgeUiState, onRequestShizuku: () -> Unit, onRunDiagnostics: () -> Unit, onOpenAccessibility: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Status do Sistema", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

            StatusRow(label = "Shizuku", ok = state.shizukuAvailable, text = if (state.shizukuAvailable) "Disponível" else "Não encontrado")
            StatusRow(label = "Permissão", ok = state.shizukuGranted, text = if (state.shizukuGranted) "Concedida" else "Pendente")
            StatusRow(label = "Serviço", ok = state.serviceConnected, text = if (state.serviceConnected) "Conectado" else "Desconectado")
            StatusRow(label = "Acessibilidade", ok = state.accessibilityServiceEnabled, text = if (state.accessibilityServiceEnabled) "Habilitada" else "Desabilitada")

            // Show troubleshooting message prominently
            if (state.troubleshootingMessage.isNotEmpty()) {
                Text(
                    text = state.troubleshootingMessage,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Show detailed status message if available
            if (state.detailedStatus.isNotEmpty()) {
                Text(
                    text = state.detailedStatus,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // ACTION BUTTONS - Shizuku
            if (!state.shizukuGranted && state.shizukuAvailable) {
                Button(
                    onClick = onRequestShizuku,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Conceder Permissão Shizuku")
                }
            }

            if (!state.shizukuAvailable) {
                Button(
                    onClick = onRunDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Shizuku não detectado - Diagnosticar")
                }
            }

            // ACTION BUTTONS - Accessibility
            if (!state.accessibilityServiceEnabled) {
                Button(
                    onClick = onOpenAccessibility,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                ) {
                    Text("Habilitar Serviço de Acessibilidade")
                }
            }

            // Diagnostic button for troubleshooting
            Button(
                onClick = onRunDiagnostics,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Diagnosticar Shizuku")
            }

            // Additional troubleshooting tips
            if (state.shizukuAvailable && !state.shizukuGranted) {
                Text(
                    text = "Dica: Se a permissão foi concedida mas não é detectada, tente forçar a parada de ambos os apps e reiniciar.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun StatusRow(label: String, ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = if (ok) Color(0xFF4CAF50) else Color(0xFFF44336),
                    shape = RoundedCornerShape(5.dp)
                )
        )
        Text("$label: $text", fontSize = 14.sp)
    }
}

@Composable
fun GamepadBindingCard(
    title: String,
    accentColor: Color,
    boundDescriptor: String,
    gamepads: List<GamepadInfo>,
    onBind: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isBound = boundDescriptor.isNotEmpty()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, accentColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = accentColor)
                if (isBound) {
                    Badge(containerColor = Color(0xFF4CAF50)) { Text("VINCULADO") }
                }
            }

            if (isBound) {
                Text(
                    text = "Descriptor: ${boundDescriptor.take(32)}…",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (gamepads.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Nenhum gamepad detectado", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRefresh) { Text("Atualizar") }
                }
            } else {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = gamepads.firstOrNull { it.descriptor == boundDescriptor }?.name ?: "Selecionar controle...",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        label = { Text("Gamepad") }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        gamepads.forEach { gamepad ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(gamepad.name, fontWeight = FontWeight.Medium)
                                        Text(
                                            "ID: ${gamepad.id} | ${gamepad.descriptor.take(24)}…",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    onBind(gamepad.descriptor)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BridgeControlCard(state: BridgeUiState, onToggle: () -> Unit) {
    val canActivate = state.shizukuGranted &&
            state.serviceConnected &&
            state.player1Descriptor.isNotEmpty() &&
            state.player2Descriptor.isNotEmpty() &&
            state.player1Descriptor != state.player2Descriptor

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.bridgeActive) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (state.bridgeActive) "BRIDGE ATIVA" else "Bridge Inativa",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = if (state.bridgeActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!canActivate && !state.bridgeActive) {
                Text(
                    text = when {
                        !state.shizukuGranted -> "Conceda permissão ao Shizuku primeiro"
                        !state.serviceConnected -> "Aguardando conexão do serviço..."
                        state.player1Descriptor.isEmpty() || state.player2Descriptor.isEmpty() -> "Vincule ambos os gamepads"
                        state.player1Descriptor == state.player2Descriptor -> "Selecione controles DIFERENTES para P1 e P2"
                        else -> ""
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = onToggle,
                enabled = canActivate || state.bridgeActive,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.bridgeActive) Color(0xFFF44336) else Color(0xFF4CAF50)
                )
            ) {
                Text(
                    text = if (state.bridgeActive) "Desativar Bridge" else "Ativar Bridge",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}
