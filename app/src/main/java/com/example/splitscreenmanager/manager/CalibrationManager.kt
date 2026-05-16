package com.example.splitscreenmanager.manager

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class CalibrationData(
    val screenWidth: Float,
    val screenHeight: Float,
    val player2Top: Float,
    val player2Bottom: Float,
    val player2Left: Float,
    val player2Right: Float,
    val deadZoneX: Float = 0.1f,
    val deadZoneY: Float = 0.1f,
    val sensitivityX: Float = 1.0f,
    val sensitivityY: Float = 1.0f,
    val invertY: Boolean = false,
    val isPortrait: Boolean = false
) {
    val centerX: Float
        get() = (player2Left + player2Right) / 2f

    val centerY: Float
        get() = (player2Top + player2Bottom) / 2f

    val width: Float
        get() = player2Right - player2Left

    val height: Float
        get() = player2Bottom - player2Top
}

data class TouchPoint(
    val x: Float,
    val y: Float,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class CalibrationState {
    object Idle : CalibrationState()
    data class DetectingScreen(val progress: Float) : CalibrationState()
    data class CalibratingBoundary(
        val step: CalibrationStep,
        val currentPoint: PointF? = null,
        val isComplete: Boolean = false
    ) : CalibrationState()
    data class TestingControls(val position: PointF) : CalibrationState()
    data class Completed(val result: CalibrationData) : CalibrationState()
    data class Error(val message: String) : CalibrationState()
}

enum class CalibrationStep {
   TOP_LEFT,
   TOP_RIGHT,
   BOTTOM_LEFT,
   BOTTOM_RIGHT
}

class CalibrationManager(private val context: Context) {

    companion object {
        private const val TAG = "CalibrationManager"
        private const val PREFS_NAME = "CalibrationPrefs"
        private const val PREF_CALIBRATION_DATA = "calibration_data"
        private const val PREF_IS_CALIBRATED = "is_calibrated"
        private const val PREF_AUTO_DETECT = "auto_detect"

        // Default values for split-screen calibration
        private const val DEFAULT_SPLIT_RATIO = 0.5f // 50% split
        private const val DEFAULT_DEAD_ZONE = 0.15f
        private const val DEFAULT_SENSITIVITY = 1.0f
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val _calibrationState = MutableStateFlow<CalibrationState>(CalibrationState.Idle)
    val calibrationState: StateFlow<CalibrationState> = _calibrationState.asStateFlow()

    private val _currentCalibrationData = MutableStateFlow<CalibrationData?>(null)
    val currentCalibrationData: StateFlow<CalibrationData?> = _currentCalibrationData.asStateFlow()

    private val _touchPoints = MutableStateFlow<List<TouchPoint>>(emptyList())
    val touchPoints: StateFlow<List<TouchPoint>> = _touchPoints.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var detectionStartTime: Long = 0

    init {
        loadCalibrationData()
    }

    fun detectScreenLayout() {
        _calibrationState.value = CalibrationState.DetectingScreen(0f)
        detectionStartTime = System.currentTimeMillis()

        handler.postDelayed({
            performScreenDetection()
        }, 100)
    }

    private fun performScreenDetection() {
        try {
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)

            val screenWidth = metrics.widthPixels.toFloat()
            val screenHeight = metrics.heightPixels.toFloat()
            val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

            // Update progress periodically
            val runnable = object : Runnable {
                override fun run() {
                    val elapsed = System.currentTimeMillis() - detectionStartTime
                    val progress = (elapsed % 1000) / 1000f // 1-second cycle

                    if (_calibrationState.value is CalibrationState.DetectingScreen) {
                        _calibrationState.value = CalibrationState.DetectingScreen(progress)
                        handler.postDelayed(this, 50)
                    }
                }
            }
            handler.post(runnable)

            // Simulate detection for 2 seconds
            handler.postDelayed({
                handler.removeCallbacks(runnable)

                // Determine screen layout
                val calibrationData = if (isHdmiConnected()) {
                    val hdmiDisplay = getHdmiDisplay()
                    if (hdmiDisplay != null) {
                        // HDMI connected - use dual display mode
                        createDualDisplayCalibration(screenWidth, screenHeight, hdmiDisplay)
                    } else {
                        // Fallback to split-screen
                        createSplitScreenCalibration(screenWidth, screenHeight, isPortrait)
                    }
                } else {
                    // Single display - use split-screen
                    createSplitScreenCalibration(screenWidth, screenHeight, isPortrait)
                }

                _currentCalibrationData.value = calibrationData

                handler.postDelayed({
                    startBoundaryCalibration()
                }, 500)
            }, 2000)

        } catch (e: Exception) {
            _calibrationState.value = CalibrationState.Error("Failed to detect screen layout: ${e.message}")
        }
    }

    private fun createSplitScreenCalibration(screenWidth: Float, screenHeight: Float, isPortrait: Boolean): CalibrationData {
        return if (isPortrait) {
            // Vertical split
            val splitY = screenHeight * DEFAULT_SPLIT_RATIO
            CalibrationData(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                player2Top = splitY,
                player2Bottom = screenHeight,
                player2Left = 0f,
                player2Right = screenWidth,
                isPortrait = true
            )
        } else {
            // Horizontal split
            val splitX = screenWidth * DEFAULT_SPLIT_RATIO
            CalibrationData(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                player2Top = 0f,
                player2Bottom = screenHeight,
                player2Left = splitX,
                player2Right = screenWidth,
                isPortrait = false
            )
        }
    }

    private fun createDualDisplayCalibration(primaryWidth: Float, primaryHeight: Float, hdmiDisplay: Display): CalibrationData {
        val metrics = DisplayMetrics()
        hdmiDisplay.getRealMetrics(metrics)

        return CalibrationData(
            screenWidth = primaryWidth,
            screenHeight = primaryHeight,
            player2Top = 0f,
            player2Bottom = metrics.heightPixels.toFloat(),
            player2Left = 0f,
            player2Right = metrics.widthPixels.toFloat(),
            isPortrait = false
        )
    }

    fun startBoundaryCalibration() {
        _calibrationState.value = CalibrationState.CalibratingBoundary(CalibrationStep.TOP_LEFT)
        _touchPoints.value = emptyList()
    }

    fun addTouchPoint(x: Float, y: Float) {
        when (val state = _calibrationState.value) {
            is CalibrationState.CalibratingBoundary -> {
                val newPoint = PointF(x, y)
                _touchPoints.value = _touchPoints.value + TouchPoint(x, y)

                // Check if point is within valid range for current step
                val isCorrectPosition = validateTouchPosition(newPoint, state.step)

                if (isCorrectPosition) {
                    advanceCalibrationStep(state.step, newPoint)
                }
            }
            is CalibrationState.TestingControls -> {
                _touchPoints.value = _touchPoints.value + TouchPoint(x, y)
                _calibrationState.value = CalibrationState.TestingControls(PointF(x, y))
            }
            else -> {}
        }
    }

    private fun validateTouchPosition(point: PointF, step: CalibrationStep): Boolean {
        val calibration = _currentCalibrationData.value ?: return false

        val tolerance = 50f

        return when (step) {
            CalibrationStep.TOP_LEFT -> {
                point.x <= calibration.player2Left + tolerance &&
                point.y <= calibration.player2Top + tolerance
            }
            CalibrationStep.TOP_RIGHT -> {
                point.x >= calibration.player2Right - tolerance &&
                point.y <= calibration.player2Top + tolerance
            }
            CalibrationStep.BOTTOM_LEFT -> {
                point.x <= calibration.player2Left + tolerance &&
                point.y >= calibration.player2Bottom - tolerance
            }
            CalibrationStep.BOTTOM_RIGHT -> {
                point.x >= calibration.player2Right - tolerance &&
                point.y >= calibration.player2Bottom - tolerance
            }
        }
    }

    private fun advanceCalibrationStep(currentStep: CalibrationStep, point: PointF) {
        val calibration = _currentCalibrationState.value ?: return

        val nextStep = when (currentStep) {
            CalibrationStep.TOP_LEFT -> CalibrationStep.TOP_RIGHT
            CalibrationStep.TOP_RIGHT -> CalibrationStep.BOTTOM_RIGHT
            CalibrationStep.BOTTOM_RIGHT -> CalibrationStep.BOTTOM_LEFT
            CalibrationStep.BOTTOM_LEFT -> {
                // Calibration complete
                saveCalibrationData()
                _calibrationState.value = CalibrationState.Completed(calibration)
                return
            }
        }

        _calibrationState.value = CalibrationState.CalibratingBoundary(nextStep, point)
    }

    private val _currentCalibrationState: StateFlow<CalibrationData?>
        get() = _currentCalibrationData

    fun startTestMode() {
        _calibrationState.value = CalibrationState.TestingControls(PointF(0f, 0f))
        _touchPoints.value = emptyList()
    }

    fun transformInput(
        inputX: Float,
        inputY: Float,
        gamepadRange: Float = 1.0f
    ): Pair<Float, Float> {
        val calibration = _currentCalibrationData.value ?: return Pair(inputX, inputY)

        // Apply dead zone
        val normalizedX = if (inputX < calibration.deadZoneX && inputX > -calibration.deadZoneX) {
            0f
        } else {
            inputX * calibration.sensitivityX
        }

        val normalizedY = if (inputY < calibration.deadZoneY && inputY > -calibration.deadZoneY) {
            0f
        } else {
            (if (calibration.invertY) -inputY else inputY) * calibration.sensitivityY
        }

        // Map from gamepad space to touch space
        val touchX = calibration.centerX + (normalizedX * calibration.width / 2 * gamepadRange)
        val touchY = calibration.centerY + (normalizedY * calibration.height / 2 * gamepadRange)

        // Clamp to valid bounds
        val clampedX = touchX.coerceIn(calibration.player2Left, calibration.player2Right)
        val clampedY = touchY.coerceIn(calibration.player2Top, calibration.player2Bottom)

        return Pair(clampedX, clampedY)
    }

    fun transformCircularInput(
        analogX: Float,
        analogY: Float,
        applySquareMapping: Boolean = false
    ): Pair<Float, Float> {
        val calibration = _currentCalibrationData.value ?: return Pair(analogX, analogY)

        // Convert analog stick input to circular coordinates
        val magnitude = kotlin.math.sqrt(analogX * analogX + analogY * analogY)
        val angle = atan2(analogY, analogX)

        // Apply circular dead zone
        val adjustedMagnitude = if (magnitude < calibration.deadZoneX) {
            0f
        } else {
            ((magnitude - calibration.deadZoneX) / (1f - calibration.deadZoneX)) * calibration.sensitivityX
        }

        // Optional: Apply square mapping for more precise corner movements
        val (finalX, finalY) = if (applySquareMapping && magnitude > calibration.deadZoneX) {
            // Map circle to square for better precision
            val squareX = cos(angle) * kotlin.math.sqrt(2f) * adjustedMagnitude
            val squareY = sin(angle) * kotlin.math.sqrt(2f) * adjustedMagnitude
            Pair(squareX, squareY)
        } else {
            Pair(
                cos(angle) * adjustedMagnitude,
                sin(angle) * adjustedMagnitude
            )
        }

        // Map to touch coordinates
        return transformInput(
            finalX,
            if (calibration.invertY) -finalY else finalY,
            gamepadRange = 1f
        )
    }

    fun saveCalibrationData() {
        val calibration = _currentCalibrationData.value ?: return

        val serialized = "${calibration.screenWidth},${calibration.screenHeight}," +
                        "${calibration.player2Top},${calibration.player2Bottom}," +
                        "${calibration.player2Left},${calibration.player2Right}," +
                        "${calibration.deadZoneX},${calibration.deadZoneY}," +
                        "${calibration.sensitivityX},${calibration.sensitivityY}," +
                        "${calibration.invertY},${calibration.isPortrait}"

        prefs.edit()
            .putString(PREF_CALIBRATION_DATA, serialized)
            .putBoolean(PREF_IS_CALIBRATED, true)
            .putBoolean(PREF_AUTO_DETECT, _autoDetect.value)
            .apply()
    }

    private fun loadCalibrationData() {
        val calibrationStr = prefs.getString(PREF_CALIBRATION_DATA, null)
        if (calibrationStr != null) {
            try {
                val parts = calibrationStr.split(",")
                if (parts.size >= 12) {
                    _currentCalibrationData.value = CalibrationData(
                        screenWidth = parts[0].toFloat(),
                        screenHeight = parts[1].toFloat(),
                        player2Top = parts[2].toFloat(),
                        player2Bottom = parts[3].toFloat(),
                        player2Left = parts[4].toFloat(),
                        player2Right = parts[5].toFloat(),
                        deadZoneX = parts[6].toFloatOrNull() ?: DEFAULT_DEAD_ZONE,
                        deadZoneY = parts[7].toFloatOrNull() ?: DEFAULT_DEAD_ZONE,
                        sensitivityX = parts[8].toFloatOrNull() ?: DEFAULT_SENSITIVITY,
                        sensitivityY = parts[9].toFloatOrNull() ?: DEFAULT_SENSITIVITY,
                        invertY = parts[10].lowercase() == "true",
                        isPortrait = parts[11].lowercase() == "true"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load calibration data", e)
            }
        }
    }

    fun resetCalibration() {
        _currentCalibrationData.value = null
        _calibrationState.value = CalibrationState.Idle
        _touchPoints.value = emptyList()

        prefs.edit()
            .remove(PREF_CALIBRATION_DATA)
            .remove(PREF_IS_CALIBRATED)
            .apply()
    }

    fun setDeadZone(deadZoneX: Float, deadZoneY: Float) {
        _currentCalibrationData.value?.let { calibration ->
            _currentCalibrationData.value = calibration.copy(
                deadZoneX = deadZoneX,
                deadZoneY = deadZoneY
            )
            saveCalibrationData()
        }
    }

    fun setSensitivity(sensitivityX: Float, sensitivityY: Float) {
        _currentCalibrationData.value?.let { calibration ->
            _currentCalibrationData.value = calibration.copy(
                sensitivityX = sensitivityX,
                sensitivityY = sensitivityY
            )
            saveCalibrationData()
        }
    }

    fun setInvertY(invert: Boolean) {
        _currentCalibrationData.value?.let { calibration ->
            _currentCalibrationData.value = calibration.copy(invertY = invert)
            saveCalibrationData()
        }
    }

    private val _autoDetect = MutableStateFlow(prefs.getBoolean(PREF_AUTO_DETECT, true))
    val autoDetect: StateFlow<Boolean> = _autoDetect.asStateFlow()

    fun setAutoDetect(enabled: Boolean) {
        _autoDetect.value = enabled
        prefs.edit().putBoolean(PREF_AUTO_DETECT, enabled).apply()
    }

    fun isCalibrated(): Boolean {
        return prefs.getBoolean(PREF_IS_CALIBRATED, false) && _currentCalibrationData.value != null
    }

    private fun isHdmiConnected(): Boolean {
        return displayManager.displays.any { display ->
            display.displayId != Display.DEFAULT_DISPLAY &&
            (display.flags and Display.FLAG_PRESENTATION != 0)
        }
    }

    private fun getHdmiDisplay(): Display? {
        return displayManager.displays.find { display ->
            display.displayId != Display.DEFAULT_DISPLAY &&
            (display.flags and Display.FLAG_PRESENTATION != 0)
        }
    }

    fun getCalibrationForDisplay(displayId: Int): CalibrationData? {
        if (displayId == Display.DEFAULT_DISPLAY) {
            return _currentCalibrationData.value
        }

        // If HDMI is connected, return calibration for external display
        val hdmiDisplay = getHdmiDisplay()
        if (hdmiDisplay != null && hdmiDisplay.displayId == displayId) {
            val metrics = DisplayMetrics()
            hdmiDisplay.getRealMetrics(metrics)

            return _currentCalibrationData.value?.copy(
                player2Top = 0f,
                player2Bottom = metrics.heightPixels.toFloat(),
                player2Left = 0f,
                player2Right = metrics.widthPixels.toFloat()
            )
        }

        return null
    }

    fun clearTouchPoints() {
        _touchPoints.value = emptyList()
    }

    fun cancelCalibration() {
        handler.removeCallbacksAndMessages(null)
        _calibrationState.value = CalibrationState.Idle
        _touchPoints.value = emptyList()
    }
}