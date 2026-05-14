package com.splitscreen.inputbridge.repository

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ControllerRegistry — Repository pattern for managing gamepad controllers
 *
 * This class provides a complete repository implementation with Flow/StateFlow
 * for reactive controller management. It handles device discovery, state tracking,
 * and provides a clean API for UI components to observe controller changes.
 */
class ControllerRegistry(private val context: Context) : InputManager.InputDeviceListener {

    companion object {
        private const val TAG = "ControllerRegistry"
        private const val PREFS_NAME = "ControllerRegistryPrefs"
        private const val KEY_PLAYER1 = "player1_descriptor"
        private const val KEY_PLAYER2 = "player2_descriptor"
    }

    private val inputManager: InputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Coroutine scope for async operations
    private val registryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // StateFlow for reactive state management
    private val _controllersState = MutableStateFlow(ControllersState())
    val controllersState: StateFlow<ControllersState> = _controllersState.asStateFlow()

    init {
        // Load persisted assignments
        loadPersistedAssignments()

        // Register for input device events
        inputManager.registerInputDeviceListener(this, null)

        // Initial scan for connected devices
        refreshConnectedDevices()
    }

    private fun loadPersistedAssignments() {
        val player1Descriptor = sharedPrefs.getString(KEY_PLAYER1, "") ?: ""
        val player2Descriptor = sharedPrefs.getString(KEY_PLAYER2, "") ?: ""

        _controllersState.value = _controllersState.value.copy(
            player1Descriptor = player1Descriptor,
            player2Descriptor = player2Descriptor
        )

        Log.d(TAG, "Loaded persisted assignments: P1=$player1Descriptor, P2=$player2Descriptor")
    }

    fun refreshConnectedDevices() {
        registryScope.launch {
            val gamepads = InputDevice.getDeviceIds().toList()
                .mapNotNull { InputDevice.getDevice(it) }
                .filter { device ->
                    device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                            device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
                }
                .map { device ->
                    ControllerInfo(
                        id = device.id,
                        name = device.name,
                        descriptor = device.descriptor,
                        isConnected = true
                    )
                }

            _controllersState.value = _controllersState.value.copy(
                availableControllers = gamepads
            )

            Log.d(TAG, "Refreshed ${gamepads.size} connected gamepads")
        }
    }

    fun assignController(player: Int, descriptor: String): Boolean {
        if (player != 1 && player != 2) {
            Log.e(TAG, "Invalid player number: $player")
            return false
        }

        // Validate descriptor exists in available controllers
        val descriptorExists = _controllersState.value.availableControllers
            .any { it.descriptor == descriptor }

        if (!descriptorExists && descriptor.isNotEmpty()) {
            Log.w(TAG, "Cannot assign non-existent descriptor: $descriptor")
            return false
        }

        // Persist assignment
        sharedPrefs.edit().apply {
            if (player == 1) {
                putString(KEY_PLAYER1, descriptor)
                _controllersState.value = _controllersState.value.copy(
                    player1Descriptor = descriptor
                )
            } else {
                putString(KEY_PLAYER2, descriptor)
                _controllersState.value = _controllersState.value.copy(
                    player2Descriptor = descriptor
                )
            }
        }.apply()

        Log.i(TAG, "Player $player assigned descriptor: $descriptor")
        return true
    }

    fun clearAssignment(player: Int): Boolean {
        return assignController(player, "")
    }

    fun getAssignedController(player: Int): ControllerInfo? {
        val descriptor = if (player == 1) {
            _controllersState.value.player1Descriptor
        } else {
            _controllersState.value.player2Descriptor
        }

        return _controllersState.value.availableControllers
            .find { it.descriptor == descriptor }
    }

    fun isFullyConfigured(): Boolean {
        val state = _controllersState.value
        return state.player1Descriptor.isNotEmpty() &&
               state.player2Descriptor.isNotEmpty() &&
               state.player1Descriptor != state.player2Descriptor
    }

    // InputManager.InputDeviceListener callbacks
    override fun onInputDeviceAdded(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId)
        if (isGamepadDevice(device)) {
            registryScope.launch {
                val controller = ControllerInfo(
                    id = device.id,
                    name = device.name,
                    descriptor = device.descriptor,
                    isConnected = true
                )

                val currentControllers = _controllersState.value.availableControllers.toMutableList()
                currentControllers.add(controller)

                _controllersState.value = _controllersState.value.copy(
                    availableControllers = currentControllers
                )

                Log.d(TAG, "Gamepad added: ${device.name} (id=$deviceId)")
            }
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId)
        if (isGamepadDevice(device)) {
            registryScope.launch {
                val currentControllers = _controllersState.value.availableControllers.toMutableList()
                val removedCount = currentControllers.removeAll { it.id == deviceId }

                if (removedCount > 0) {
                    _controllersState.value = _controllersState.value.copy(
                        availableControllers = currentControllers
                    )

                    // Clear assignments if the removed device was assigned
                    val state = _controllersState.value
                    if (state.player1Descriptor == device.descriptor) {
                        clearAssignment(1)
                    }
                    if (state.player2Descriptor == device.descriptor) {
                        clearAssignment(2)
                    }

                    Log.d(TAG, "Gamepad removed: ${device.name} (id=$deviceId)")
                }
            }
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        // Device configuration changed - refresh the list
        refreshConnectedDevices()
    }

    private fun isGamepadDevice(device: InputDevice?): Boolean {
        if (device == null) return false

        return device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
               device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    fun cleanup() {
        inputManager.unregisterInputDeviceListener(this)
        registryScope.cancel()
        Log.d(TAG, "ControllerRegistry cleaned up")
    }

    /**
     * Controller information data class
     */
    data class ControllerInfo(
        val id: Int,
        val name: String,
        val descriptor: String,
        val isConnected: Boolean
    )

    /**
     * Complete state of the controller registry
     */
    data class ControllersState(
        val availableControllers: List<ControllerInfo> = emptyList(),
        val player1Descriptor: String = "",
        val player2Descriptor: String = "",
        val lastUpdated: Long = System.currentTimeMillis()
    )
}
