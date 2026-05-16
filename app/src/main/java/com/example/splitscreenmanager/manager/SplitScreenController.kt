package com.example.splitscreenmanager.manager

import android.view.KeyEvent
import android.util.Log
import com.example.splitscreenmanager.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Manages controller differentiation for split-screen gaming.
 *
 * Strategy:
 * - Player 1's controller goes to the TOP app (normal routing via Android)
 * - Player 2's controller events are intercepted by AccessibilityService,
 *   consumed (blocked from going to the focused app), and re-injected
 *   via Shizuku with Y-offset to target the BOTTOM app's screen area
 *
 * For button/key events: forwarded via `input keyevent` shell command
 * For joystick/touch events: injected via IInputManager with Y-offset
 *   that shifts coordinates to the bottom half of the screen
 *
 * This controller is thread-safe and optimized for low-latency input handling.
 */
class SplitScreenController(private val viewModel: AppViewModel) {

    companion object {
        private const val TAG = "SplitScreenCtrl"
        private const val DEFAULT_SCREEN_WIDTH = 1080
        private const val DEFAULT_SCREEN_HEIGHT = 2400
    }

    @Volatile
    private var screenHeight: Int = DEFAULT_SCREEN_HEIGHT
    @Volatile
    private var screenWidth: Int = DEFAULT_SCREEN_WIDTH
    @Volatile
    private var isSplitScreenActive: Boolean = false

    // Cache the half height value to avoid repeated division operations
    @Volatile
    private var halfHeight: Float = DEFAULT_SCREEN_HEIGHT / 2f

    /**
     * Sets the screen dimensions and updates the internal half-height cache.
     *
     * @param width Screen width in pixels (must be positive)
     * @param height Screen height in pixels (must be positive)
     * @throws IllegalArgumentException if width or height are not positive
     */
    fun setScreenDimensions(width: Int, height: Int) {
        require(width > 0) { "Width must be positive" }
        require(height > 0) { "Height must be positive" }

        screenWidth = width
        screenHeight = height
        halfHeight = height / 2f
        Log.d(TAG, "Screen dimensions updated: ${width}x${height}")
    }

    /**
     * Activates split-screen controller differentiation.
     * This enables remapping of Player 2's input events.
     */
    fun activate() {
        isSplitScreenActive = true
        Log.d(TAG, "Split-screen controller differentiation ACTIVATED")
    }

    /**
     * Deactivates split-screen controller differentiation.
     * Player 2's input events will no longer be remapped.
     */
    fun deactivate() {
        isSplitScreenActive = false
        Log.d(TAG, "Split-screen controller differentiation DEACTIVATED")
    }

    /**
     * Checks if split-screen controller differentiation is currently active.
     *
     * @return true if active, false otherwise
     */
    fun isActive(): Boolean = isSplitScreenActive

    /**
     * Called when Player 2 presses a button on their gamepad.
     * We forward it via Shizuku binder for low latency.
     *
     * @param keyCode The key code of the button pressed (e.g., KeyEvent.KEYCODE_A)
     */
    fun forwardPlayer2Key(keyCode: Int) {
        if (!isSplitScreenActive) {
            Log.v(TAG, "forwardPlayer2Key called but split-screen is inactive")
            return
        }

        // Validate keyCode range
        if (keyCode <= KeyEvent.KEYCODE_UNKNOWN || keyCode >= KeyEvent.getMaxKeyCode()) {
            Log.w(TAG, "Invalid keyCode: $keyCode")
            return
        }

        val now = android.os.SystemClock.uptimeMillis()

        // Create and forward down event
        val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        viewModel.injectInputEvent(downEvent)

        // Create and forward up event
        val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        viewModel.injectInputEvent(upEvent)
    }

    /**
     * Calculate the Y-offset for Player 2's touch/joystick events.
     * In split-screen, the bottom app occupies the lower half of the screen,
     * so Player 2's touch coordinates need to be shifted down by half the screen height.
     *
     * @param originalY The original Y coordinate from Player 2's input
     * @return The remapped Y coordinate for the bottom app's coordinate system
     */
    fun remapYForPlayer2(originalY: Float): Float {
        if (!isSplitScreenActive) {
            return originalY
        }

        // Use cached halfHeight value for better performance
        return originalY + halfHeight
    }

    /**
     * Get the vertical midpoint of the screen (where split-screen divider is).
     * This value is cached and updated when screen dimensions change.
     *
     * @return Y coordinate of the split line in pixels
     */
    fun getSplitLineY(): Float = halfHeight

    /**
     * Check if a Y coordinate is in Player 2's zone (bottom half).
     *
     * @param y The Y coordinate to check
     * @return true if the coordinate is in the bottom half, false otherwise
     */
    fun isInPlayer2Zone(y: Float): Boolean = y > halfHeight

    /**
     * Gets the current screen width.
     *
     * @return Screen width in pixels
     */
    fun getScreenWidth(): Int = screenWidth

    /**
     * Gets the current screen height.
     *
     * @return Screen height in pixels
     */
    fun getScreenHeight(): Int = screenHeight
}