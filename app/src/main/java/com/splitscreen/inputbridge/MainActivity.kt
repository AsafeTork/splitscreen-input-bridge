@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.splitscreen.inputbridge

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.IBinder
import android.view.InputDevice
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
import com.splitscreen.inputbridge.ui.theme.SplitScreenInputBridgeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity(), InputManager.InputDeviceListener {

    private lateinit var inputManager: InputManager
    private var bridgeService: InputBridgeService? = null

    private val _uiState = MutableStateFlow(BridgeUiState())
    private val uiState: StateFlow<BridgeUiState> = _uiState

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridgeService = (binder as? InputBridgeService.LocalBinder)?.getService()
            bridgeService?.setStatusCallback { p1, p2, active ->
                _uiState.value = _uiState.value.copy(
                    player1Descriptor = p1,
                    player2Descriptor = p2,
                    bridgeActive = active
                )
            }
            _uiState.value = _uiState.value.copy(serviceConnected = true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            _uiState.value = _uiState.value.copy(serviceConnected = false, bridgeActive = false)
        }
    }

    private val shizukuRequestCode = 100
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == shizukuRequestCode) {
            val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
            _uiState.value = _uiState.value.copy(shizukuGranted = granted)
            if (granted) bindBridgeService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        inputManager = getSystemService(InputManager::class.java)
        inputManager.registerInputDeviceListener(this, null)

        Shizuku.addRequestPermissionResultListener(shizukuListener)
        Shizuku.addBinderReceivedListenerSticky {
            val granted = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            _uiState.value = _uiState.value.copy(
                shizukuAvailable = true,
                shizukuGranted = granted
            )
            if (granted) bindBridgeService()
        }
        Shizuku.addBinderDeadListener {
            _uiState.value = _uiState.value.copy(shizukuAvailable = false, shizukuGranted = false)
        }

        refreshGamepads()

        setContent {
            SplitScreenInputBridgeTheme {
                val state by uiState.collectAsStateWithLifecycle()
                BridgeScreen(
                    state = state,
                    onRequestShizuku = { requestShizukuPermission() },
                    onBindPlayer1 = { descriptor -> bindPlayer(1, descriptor) },
                    onBindPlayer2 = { descriptor -> bindPlayer(2, descriptor) },
                    onToggleBridge = { toggleBridge() },
                    onRefreshDevices = { refreshGamepads() }
                )
            }
        }
    }

    private fun refreshGamepads() {
        val gamepads = InputDevice.getDeviceIds()
            .mapNotNull { InputDevice.getDevice(it) }
            .filter { device ->
                device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                        device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            }
            .map { GamepadInfo(id = it.id, name = it.name, descriptor = it.descriptor) }

        _uiState.value = _uiState.value.copy(availableGamepads = gamepads)
    }

    private fun requestShizukuPermission() {
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            _uiState.value = _uiState.value.copy(statusMessage = "Shizuku versão incompatível (necessário v11+)")
            return
        }
        Shizuku.requestPermission(shizukuRequestCode)
    }

    private fun bindBridgeService() {
        val intent = Intent(this, InputBridgeService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, 0)
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

    override fun onInputDeviceAdded(deviceId: Int) = refreshGamepads()
    override fun onInputDeviceRemoved(deviceId: Int) = refreshGamepads()
    override fun onInputDeviceChanged(deviceId: Int) = refreshGamepads()

    override fun onDestroy() {
        super.onDestroy()
        inputManager.unregisterInputDeviceListener(this)
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {}
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
    val statusMessage: String = ""
)

@Composable
fun BridgeScreen(
    state: BridgeUiState,
    onRequestShizuku: () -> Unit,
    onBindPlayer1: (String) -> Unit,
    onBindPlayer2: (String) -> Unit,
    onToggleBridge: () -> Unit,
    onRefreshDevices: () -> Unit
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

            StatusCard(state = state, onRequestShizuku = onRequestShizuku)

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
fun StatusCard(state: BridgeUiState, onRequestShizuku: () -> Unit) {
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

            if (!state.shizukuGranted && state.shizukuAvailable) {
                Button(
                    onClick = onRequestShizuku,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Conceder Permissão Shizuku")
                }
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
