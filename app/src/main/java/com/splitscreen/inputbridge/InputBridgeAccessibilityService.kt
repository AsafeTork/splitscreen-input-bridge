package com.splitscreen.inputbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

/**
 * InputBridgeAccessibilityService — Gamepad event interceptor.
 *
 * Accessibility Services with the `canRequestFilterKeyEvents` flag and
 * `android.permission.BIND_ACCESSIBILITY_SERVICE` can intercept input events
 * BEFORE they reach the focused window.
 *
 * Why Accessibility Service for interception:
 *   Standard Android apps cannot intercept input events globally (no InputFilter
 *   access without INJECT_EVENTS permission). The AccessibilityService.onKeyEvent()
 *   and onMotionEvent() callbacks (API 33+) fire for all input events regardless
 *   of which window is focused, making it the correct intercept point.
 *
 * Event routing strategy:
 *   - KEY_EVENT (button presses): routed to InputBridgeService for descriptor-based dispatch
 *   - MOTION_EVENT (analog axes): same
 *   The service returns true to CONSUME the event (block native delivery) when
 *   the event belongs to Player 2's gamepad and has been injected to the secondary viewport.
 */
class InputBridgeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "InputBridgeA11yService"
    }

    private var bridgeService: InputBridgeService? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bridgeService = (binder as? InputBridgeService.LocalBinder)?.getService()
            Log.i(TAG, "Connected to InputBridgeService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            Log.w(TAG, "Disconnected from InputBridgeService")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = serviceInfo.apply {
            flags = flags or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            
            // API 33+ motion event interception - this BLOCKS native events from the rest of the system
            motionEventSources = android.view.InputDevice.SOURCE_GAMEPAD or android.view.InputDevice.SOURCE_JOYSTICK
            Log.d(TAG, "Native motion events blocked for Gamepad/Joystick (API 33+)")
        }

        val intent = Intent(this, InputBridgeService::class.java)
        bindService(intent, connection, BIND_AUTO_CREATE)
        Log.i(TAG, "AccessibilityService connected, binding to InputBridgeService")
    }

    /**
     * Intercepts all key events (gamepad buttons) before they reach focused window.
     * Returns true to consume (block) the event for Player 2's gamepad.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val service = bridgeService ?: run {
            Log.w(TAG, "onKeyEvent: BridgeService not connected")
            return false
        }

        val device = android.view.InputDevice.getDevice(event.deviceId) ?: return false
        
        // --- SELECTIVE INTERCEPTION ---
        val playerNumber = service.getPlayerForDevice(event.deviceId)
        
        if (playerNumber == 1) {
            // Player 1 is focused. Pass through natively.
            return false
        }
        
        if (playerNumber == 2) {
            // Player 2 is in background. Translate to Touch.
            service.onGamepadKeyEvent(event)
            return true
        }

        return false
    }

    /**
     * Intercepts analog axis motion events (joysticks, triggers).
     * API 33+ only — requires `android:canObserveGenericMotionEvents` in the service config.
     */
    override fun onMotionEvent(event: MotionEvent) {
        val service = bridgeService ?: return
        
        val playerNumber = service.getPlayerForDevice(event.deviceId)
        if (playerNumber == 1) return // Pass through natively
        
        if (playerNumber == 2) {
            service.onGamepadMotionEvent(event)
        }
    }

    /**
     * Converts a gamepad button KeyEvent into a synthetic MotionEvent for unified processing.
     * Only converts events from SOURCE_GAMEPAD/SOURCE_JOYSTICK devices.
     */
    private fun createMotionFromKeyEvent(keyEvent: KeyEvent): MotionEvent? {
        val device = android.view.InputDevice.getDevice(keyEvent.deviceId) ?: return null
        val isGamepad = device.sources and android.view.InputDevice.SOURCE_GAMEPAD ==
                android.view.InputDevice.SOURCE_GAMEPAD

        if (!isGamepad) return null

        val action = if (keyEvent.action == KeyEvent.ACTION_DOWN)
            MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP

        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = 0f
            y = 0f
            pressure = if (keyEvent.action == KeyEvent.ACTION_DOWN) 1f else 0f
        })

        return MotionEvent.obtain(
            keyEvent.downTime,
            keyEvent.eventTime,
            action,
            1, props, coords,
            keyEvent.metaState, 0,
            1f, 1f,
            keyEvent.deviceId, 0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unbindService(connection)
        } catch (_: Exception) {}
    }
}
