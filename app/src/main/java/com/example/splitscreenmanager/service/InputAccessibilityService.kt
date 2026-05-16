package com.example.splitscreenmanager.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityEvent
import com.example.splitscreenmanager.manager.SplitScreenController

class InputAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "InputAccessibility"
        private const val SOURCE_GAMEPAD_MASK = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK

        @Volatile
        var lastCapturedDeviceId: Int? = null

        @Volatile
        var player1DeviceId: Int? = null

        @Volatile
        var player2DeviceId: Int? = null

        @Volatile
        var splitScreenController: SplitScreenController? = null

        fun setPlayerDeviceIds(player1Id: Int?, player2Id: Int?) {
            player1DeviceId = player1Id
            player2DeviceId = player2Id
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val deviceId = event.deviceId
        val source = event.source

        // Fast path: check if it's a gamepad event
        if ((source and SOURCE_GAMEPAD_MASK) == 0) {
            return super.onKeyEvent(event)
        }

        lastCapturedDeviceId = deviceId

        val controller = splitScreenController
        if (controller == null || !controller.isActive()) {
            return super.onKeyEvent(event)
        }

        // Early exit if this is player 1
        if (deviceId != player2DeviceId) {
            return super.onKeyEvent(event)
        }

        // Player 2: consume the event and forward it via shell
        // so it reaches the bottom app in split-screen
        if (event.action == KeyEvent.ACTION_DOWN) {
            controller.forwardPlayer2Key(event.keyCode)
        }

        // Consume ALL Player 2 events (DOWN and UP) to prevent
        // them from reaching the focused (Player 1) app
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Empty implementation as we only handle key events
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Connected - Controller differentiation ready")
    }

    override fun onDestroy() {
        super.onDestroy()
        splitScreenController?.deactivate()
        Log.d(TAG, "Accessibility Service Destroyed")
    }
}