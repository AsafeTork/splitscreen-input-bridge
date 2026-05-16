package com.example.splitscreenmanager.service

import android.view.KeyEvent
import com.example.splitscreenmanager.manager.SplitScreenController
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*

class InputAccessibilityServiceTest {

    private lateinit var service: InputAccessibilityService
    private lateinit var mockController: SplitScreenController

    @Before
    fun setup() {
        service = InputAccessibilityService()
        mockController = mock(SplitScreenController::class.java)
        InputAccessibilityService.setSplitScreenController(mockController)
    }

    @Test
    fun testPlayer2KeyEventConsumed() {
        // Setup player device IDs
        InputAccessibilityService.setPlayerDeviceIds(1, 2)

        // Mock controller to be active
        `when`(mockController.isActive()).thenReturn(true)

        // Create a key event for player 2
        val player2Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)
        player2Event.deviceId = 2
        player2Event.source = android.view.InputDevice.SOURCE_GAMEPAD

        // Test that player 2 events are consumed
        val result = service.onKeyEvent(player2Event)
        assertTrue("Player 2 key event should be consumed", result)

        // Verify that the key was forwarded
        verify(mockController).forwardPlayer2Key(KeyEvent.KEYCODE_A)
    }

    @Test
    fun testPlayer1KeyEventPassesThrough() {
        // Setup player device IDs
        InputAccessibilityService.setPlayerDeviceIds(1, 2)

        // Mock controller to be active
        `when`(mockController.isActive()).thenReturn(true)

        // Create a key event for player 1
        val player1Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B)
        player1Event.deviceId = 1
        player1Event.source = android.view.InputDevice.SOURCE_GAMEPAD

        // Test that player 1 events pass through
        val result = service.onKeyEvent(player1Event)
        assertFalse("Player 1 key event should pass through", result)

        // Verify that the key was NOT forwarded
        verify(mockController, never()).forwardPlayer2Key(anyInt())
    }

    @Test
    fun testNonGamepadEventPassesThrough() {
        // Create a non-gamepad key event
        val nonGamepadEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C)
        nonGamepadEvent.deviceId = 1
        nonGamepadEvent.source = android.view.InputDevice.SOURCE_KEYBOARD

        // Test that non-gamepad events pass through
        val result = service.onKeyEvent(nonGamepadEvent)
        assertFalse("Non-gamepad event should pass through", result)
    }

    @Test
    fun testInactiveControllerEventsPassThrough() {
        // Setup player device IDs
        InputAccessibilityService.setPlayerDeviceIds(1, 2)

        // Mock controller to be inactive
        `when`(mockController.isActive()).thenReturn(false)

        // Create a key event for player 2
        val player2Event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_D)
        player2Event.deviceId = 2
        player2Event.source = android.view.InputDevice.SOURCE_GAMEPAD

        // Test that events pass through when controller is inactive
        val result = service.onKeyEvent(player2Event)
        assertFalse("Event should pass through when controller is inactive", result)
    }

    @Test
    fun testLastCapturedDeviceIdUpdated() {
        // Create a gamepad event
        val gamepadEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_E)
        gamepadEvent.deviceId = 3
        gamepadEvent.source = android.view.InputDevice.SOURCE_GAMEPAD

        // Process the event
        service.onKeyEvent(gamepadEvent)

        // Verify that lastCapturedDeviceId was updated
        assertEquals("Last captured device ID should be updated", 3, InputAccessibilityService.getLastCapturedDeviceId())
    }
}