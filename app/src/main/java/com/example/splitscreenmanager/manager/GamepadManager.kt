package com.example.splitscreenmanager.manager

import android.content.Context
import android.content.SharedPreferences
import android.hardware.input.InputManager
import android.hardware.input.InputManager.InputDeviceListener
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class GamepadInfo(
    val deviceId: Int,
    val name: String,
    val descriptor: String,
    val vendorId: Int,
    val productId: Int,
    val isPlayer1: Boolean = false,
    val isPlayer2: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
)

data class GamepadBinding(
    val deviceId: Int,
    val descriptor: String,
    val player: Int,
    val timestamp: Long = System.currentTimeMillis()
)

class GamepadManager(private val context: Context) {

    companion object {
        private const val TAG = "GamepadManager"
        private const val PREFS_NAME = "GamepadPrefs"
        private const val PREF_PLAYER1_BINDING = "player1_binding"
        private const val PREF_PLAYER2_BINDING = "player2_binding"
        private const val BINDING_TIMEOUT = 10000L // 10 seconds
        private val GAMEPAD_VENDORS = setOf(
            "Sony", "Microsoft", "Nintendo", "Logitech", "Razer", "SteelSeries",
            "Mad Catz", "Hori", "PDP", "PowerA", "8BitDo", "MOGA", "Generic"
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val deviceInfoCache = ConcurrentHashMap<Int, GamepadInfo>()
    private val deviceTimestamps = ConcurrentHashMap<Int, Long>()

    private val _connectedGamepads = MutableStateFlow<List<GamepadInfo>>(emptyList())
    val connectedGamepads: StateFlow<List<GamepadInfo>> = _connectedGamepads.asStateFlow()

    private val _bindingState = MutableStateFlow<BindingState>(BindingState.Idle)
    val bindingState: StateFlow<BindingState> = _bindingState.asStateFlow()

    private val _player1Device = MutableStateFlow<GamepadInfo?>(null)
    val player1Device: StateFlow<GamepadInfo?> = _player1Device.asStateFlow()

    private val _player2Device = MutableStateFlow<GamepadInfo?>(null)
    val player2Device: StateFlow<GamepadInfo?> = _player2Device.asStateFlow()

    private val inputDeviceListener = object : InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            updateGamepadList()
            Log.d(TAG, "Device added: $deviceId")
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            deviceInfoCache.remove(deviceId)
            deviceTimestamps.remove(deviceId)
            updateGamepadList()
            checkBoundDevices()
            Log.d(TAG, "Device removed: $deviceId")
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            updateGamepadList()
            Log.d(TAG, "Device changed: $deviceId")
        }
    }

    sealed class BindingState {
        object Idle : BindingState()
        data class Binding(val player: Int, val startTime: Long = System.currentTimeMillis()) : BindingState()
        data class Success(val player: Int, val deviceInfo: GamepadInfo) : BindingState()
        data class Timeout(val player: Int) : BindingState()
        data class Error(val player: Int, val message: String) : BindingState()
    }

    init {
        inputManager.registerInputDeviceListener(inputDeviceListener, null)
        loadSavedBindings()
        updateGamepadList()
    }

    fun updateGamepadList() {
        val devices = inputManager.inputDeviceIds.toList().mapNotNull { id ->
            val device = inputManager.getInputDevice(id)
            if (device != null && isGamepad(device)) {
                val info = GamepadInfo(
                    deviceId = device.id,
                    name = device.name,
                    descriptor = device.descriptor,
                    vendorId = if (device.vendorId != 0) device.vendorId else 0,
                    productId = if (device.productId != 0) device.productId else 0,
                    isPlayer1 = device.id == _player1Device.value?.deviceId,
                    isPlayer2 = device.id == _player2Device.value?.deviceId,
                    lastSeen = deviceTimestamps[device.id] ?: System.currentTimeMillis()
                )
                deviceInfoCache[device.id] = info
                info
            } else null
        }
        _connectedGamepads.value = devices
    }

    private fun isGamepad(device: InputDevice): Boolean {
        val sources = device.sources
        val hasGamepadSource = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        val hasJoystickSource = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        val nameMatchesVendor = GAMEPAD_VENDORS.any { vendor ->
            device.name.contains(vendor, ignoreCase = true)
        } || device.name.contains("Controller", ignoreCase = true)

        return (hasGamepadSource || hasJoystickSource) &&
               (nameMatchesVendor || device.descriptor.contains("joystick", ignoreCase = true))
    }

    fun startBinding(player: Int) {
        Log.d(TAG, "Starting binding for Player $player")
        _bindingState.value = BindingState.Binding(player)

        // Clear any existing device registration for this player
        clearPlayerBinding(player)
    }

    fun registerKeypress(keyEvent: KeyEvent) {
        val currentState = _bindingState.value
        if (currentState is BindingState.Binding) {
            val deviceId = keyEvent.deviceId
            val device = inputManager.getInputDevice(deviceId)

            if (device != null && isGamepad(device)) {
                val existingInfo = deviceInfoCache[deviceId]
                if (existingInfo != null) {
                    bindDevice(existingInfo, currentState.player)
                    _bindingState.value = BindingState.Success(currentState.player, existingInfo)
                } else {
                    _bindingState.value = BindingState.Error(
                        currentState.player,
                        "Device not recognized as gamepad"
                    )
                }
            }
        }
    }

    private fun bindDevice(deviceInfo: GamepadInfo, player: Int) {
        val binding = GamepadBinding(
            deviceId = deviceInfo.deviceId,
            descriptor = deviceInfo.descriptor,
            player = player
        )

        if (player == 1) {
            _player1Device.value = deviceInfo
            prefs.edit()
                .putString(PREF_PLAYER1_BINDING, serializeBinding(binding))
                .apply()
        } else {
            _player2Device.value = deviceInfo
            prefs.edit()
                .putString(PREF_PLAYER2_BINDING, serializeBinding(binding))
                .apply()
        }

        updateGamepadList()
    }

    private fun loadSavedBindings() {
        val player1BindingStr = prefs.getString(PREF_PLAYER1_BINDING, null)
        val player2BindingStr = prefs.getString(PREF_PLAYER2_BINDING, null)

        player1BindingStr?.let { str ->
            val binding = deserializeBinding(str)
            findDeviceById(binding?.deviceId, binding?.descriptor)?.let { device ->
                _player1Device.value = device.copy(isPlayer1 = true)
            }
        }

        player2BindingStr?.let { str ->
            val binding = deserializeBinding(str)
            findDeviceById(binding?.deviceId, binding?.descriptor)?.let { device ->
                _player2Device.value = device.copy(isPlayer2 = true)
            }
        }
    }

    private fun findDeviceById(deviceId: Int?, descriptor: String?): GamepadInfo? {
        val currentGamepads = inputManager.inputDeviceIds.toList().mapNotNull { id ->
            val device = inputManager.getInputDevice(id)
            if (device != null && isGamepad(device)) {
                GamepadInfo(
                    deviceId = device.id,
                    name = device.name,
                    descriptor = device.descriptor,
                    vendorId = device.vendorId,
                    productId = device.productId
                )
            } else null
        }

        if (deviceId != null) {
            val match = currentGamepads.find { it.deviceId == deviceId }
            if (match != null) return match
        }

        if (descriptor != null) {
            val match = currentGamepads.find { it.descriptor == descriptor }
            if (match != null) return match
        }

        return null
    }

    fun checkBindingTimeout() {
        val currentState = _bindingState.value
        if (currentState is BindingState.Binding) {
            val elapsed = System.currentTimeMillis() - currentState.startTime
            if (elapsed > BINDING_TIMEOUT) {
                _bindingState.value = BindingState.Timeout(currentState.player)
            }
        }
    }

    fun cancelBinding() {
        if (_bindingState.value is BindingState.Binding) {
            _bindingState.value = BindingState.Idle
        }
    }

    fun clearPlayerBinding(player: Int) {
        if (player == 1) {
            _player1Device.value = null
            prefs.edit().remove(PREF_PLAYER1_BINDING).apply()
        } else {
            _player2Device.value = null
            prefs.edit().remove(PREF_PLAYER2_BINDING).apply()
        }
        updateGamepadList()
    }

    private fun checkBoundDevices() {
        // Check if bound devices are still connected
        _player1Device.value?.let { device ->
            if (!deviceInfoCache.containsKey(device.deviceId)) {
                _player1Device.value = null
            }
        }

        _player2Device.value?.let { device ->
            if (!deviceInfoCache.containsKey(device.deviceId)) {
                _player2Device.value = null
            }
        }
    }

    fun getDeviceForPlayer(player: Int): Int? {
        return when (player) {
            1 -> _player1Device.value?.deviceId
            2 -> _player2Device.value?.deviceId
            else -> null
        }
    }

    fun autoDetectAndBind() {
        val gamepads = _connectedGamepads.value
        when {
            gamepads.isNotEmpty() && _player1Device.value == null -> {
                bindDevice(gamepads[0], 1)
                Log.d(TAG, "Auto-bound Player 1 to ${gamepads[0].name}")
            }
            gamepads.size > 1 && _player2Device.value == null -> {
                bindDevice(gamepads[1], 2)
                Log.d(TAG, "Auto-bound Player 2 to ${gamepads[1].name}")
            }
        }
    }

    private fun serializeBinding(binding: GamepadBinding): String {
        return "${binding.deviceId},${binding.descriptor},${binding.player},${binding.timestamp}"
    }

    private fun deserializeBinding(str: String): GamepadBinding? {
        try {
            val parts = str.split(",")
            if (parts.size == 4) {
                return GamepadBinding(
                    deviceId = parts[0].toInt(),
                    descriptor = parts[1],
                    player = parts[2].toInt(),
                    timestamp = parts[3].toLong()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize binding", e)
        }
        return null
    }

    fun release() {
        inputManager.unregisterInputDeviceListener(inputDeviceListener)
        deviceInfoCache.clear()
        deviceTimestamps.clear()
    }
}