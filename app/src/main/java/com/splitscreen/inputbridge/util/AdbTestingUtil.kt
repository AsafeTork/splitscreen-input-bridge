package com.splitscreen.inputbridge.util

import android.content.Context
import android.util.Log
import com.splitscreen.inputbridge.ShizukuUserService

/**
 * AdbTestingUtil — Utility for testing Shizuku functionality via ADB commands
 *
 * This utility provides methods that can be called via ADB to test various
 * Shizuku functionalities and diagnose issues, especially useful when the
 * device is on a local network as mentioned by the user.
 */
object AdbTestingUtil {

    private const val TAG = "AdbTestingUtil"

    /**
     * Tests basic Shizuku connectivity and permission status
     * Can be called via: adb shell am broadcast -n com.splitscreen.inputbridge/.util.AdbTestingUtil --es action "testConnectivity"
     */
    fun testConnectivity(context: Context): String {
        return try {
            val binderAlive = ShizukuUserService.isReady()
            val permissionGranted = ShizukuUserService.isPermissionGranted()

            val result = "Connectivity Test Results:\n" +
                    "Binder Alive: $binderAlive\n" +
                    "Permission Granted: $permissionGranted\n" +
                    "Ready Status: ${ShizukuUserService.isReady()}"

            Log.i(TAG, result)
            result
        } catch (e: Exception) {
            val error = "Connectivity test failed: ${e.message}"
            Log.e(TAG, error, e)
            error
        }
    }

    /**
     * Tests shell command execution capability
     * Can be called via: adb shell am broadcast -n com.splitscreen.inputbridge/.util.AdbTestingUtil --es action "testShell" --es command "echo test"
     */
    fun testShellCommand(command: String): String {
        return try {
            val result = ShizukuUserService.execShellCommand(command)
            val output = "Shell Command Test Results:\n" +
                    "Command: $command\n" +
                    "Output: $result"

            Log.i(TAG, output)
            output
        } catch (e: Exception) {
            val error = "Shell command test failed: ${e.message}"
            Log.e(TAG, error, e)
            error
        }
    }

    /**
     * Tests input injection capability
     * Can be called via: adb shell am broadcast -n com.splitscreen.inputbridge/.util.AdbTestingUtil --es action "testInput"
     */
    fun testInputInjection(): String {
        return try {
            // This is just a test to see if we can access the input manager
            // Actual injection would require MotionEvent objects
            val testCommand = "input tap 100 100"
            val result = ShizukuUserService.execShellCommand(testCommand)

            val output = "Input Injection Test Results:\n" +
                    "Test command: $testCommand\n" +
                    "Result: ${if (result.isNotBlank()) "Success" else "Failed"}"

            Log.i(TAG, output)
            output
        } catch (e: Exception) {
            val error = "Input injection test failed: ${e.message}"
            Log.e(TAG, error, e)
            error
        }
    }

    /**
     * Performs comprehensive diagnostic
     * Can be called via: adb shell am broadcast -n com.splitscreen.inputbridge/.util.AdbTestingUtil --es action "diagnose"
     */
    fun performComprehensiveDiagnostic(context: Context): String {
        return try {
            val diagnosticResult = ShizukuDiagnosticUtil.performDiagnostic(context)
            ShizukuDiagnosticUtil.logDiagnosticResults(diagnosticResult)

            val suggestions = ShizukuDiagnosticUtil.getTroubleshootingSuggestions(diagnosticResult)

            val result = StringBuilder()
            result.append("=== Comprehensive Diagnostic Results ===\n")
            result.append("Shizuku Available: ${diagnosticResult.shizukuAvailable}\n")
            result.append("Shizuku Version: ${diagnosticResult.shizukuVersion}\n")
            result.append("Permission Granted: ${diagnosticResult.permissionGranted}\n")
            result.append("Binder Alive: ${diagnosticResult.binderAlive}\n")
            result.append("Shell Execution Working: ${diagnosticResult.shellExecutionWorking}\n")
            result.append("System Settings Accessible: ${diagnosticResult.systemSettingsAccessible}\n")
            result.append("Issues Found: ${diagnosticResult.issues.size}\n")

            diagnosticResult.issues.forEach { issue ->
                result.append("Issue: $issue\n")
            }

            result.append("\n=== Troubleshooting Suggestions ===\n")
            suggestions.forEach { suggestion ->
                result.append("Suggestion: $suggestion\n")
            }

            val output = result.toString()
            Log.i(TAG, output)
            output
        } catch (e: Exception) {
            val error = "Comprehensive diagnostic failed: ${e.message}"
            Log.e(TAG, error, e)
            error
        }
    }

    /**
     * Common ADB commands for testing Shizuku:
     *
     * 1. Check if app can be started:
     *    adb shell am start -n com.splitscreen.inputbridge/.MainActivity
     *
     * 2. Send broadcast to trigger tests:
     *    adb shell am broadcast -a com.splitscreen.inputbridge.TEST_CONNECTIVITY
     *
     * 3. Check Shizuku service status:
     *    adb shell dumpsys activity services | grep -i shizuku
     *
     * 4. Check app permissions:
     *    adb shell dumpsys package com.splitscreen.inputbridge | grep permission
     *
     * 5. Force stop and restart app:
     *    adb shell am force-stop com.splitscreen.inputbridge
     *    adb shell am start -n com.splitscreen.inputbridge/.MainActivity
     *
     * 6. Check Shizuku logs:
     *    adb logcat | grep -i shizuku
     *
     * 7. Test shell execution:
     *    adb shell sh -c "echo test"
     */
}