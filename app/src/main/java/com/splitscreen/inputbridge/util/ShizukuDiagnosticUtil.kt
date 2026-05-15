package com.splitscreen.inputbridge.util

import android.content.Context
import android.util.Log
import com.splitscreen.inputbridge.ShizukuUserService
import rikka.shizuku.Shizuku

/**
 * ShizukuDiagnosticUtil — Utility for diagnosing Shizuku permission and connectivity issues
 *
 * This utility provides comprehensive diagnostic capabilities for troubleshooting
 * Shizuku-related issues, including permission status, binder health, and system
 * configuration checks.
 */
object ShizukuDiagnosticUtil {

    private const val TAG = "ShizukuDiagnostic"

    data class DiagnosticResult(
        val shizukuAvailable: Boolean,
        val shizukuVersion: Int,
        val permissionGranted: Boolean,
        val binderAlive: Boolean,
        val shellExecutionWorking: Boolean,
        val systemSettingsAccessible: Boolean,
        val binderReceived: Boolean,
        val permissionCheckMethod: String,
        val issues: List<String>
    )

    /**
     * Performs a comprehensive diagnostic of Shizuku status
     */
    fun performDiagnostic(context: Context): DiagnosticResult {
        Log.i(TAG, "Starting Shizuku diagnostic")
        val issues = mutableListOf<String>()

        val shizukuVersion = try {
            Shizuku.getVersion()
        } catch (e: Exception) {
            issues.add("Failed to get Shizuku version: ${e.message}")
            -1
        }

        if (shizukuVersion > 0 && shizukuVersion < 11) {
            issues.add("Shizuku version $shizukuVersion is below minimum required version 11")
        }

        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            issues.add("Shizuku binder is not responding: ${e.message}")
            false
        }

        val binderReceived = try {
            Shizuku.getBinder() != null
        } catch (e: Exception) {
            issues.add("Failed to get Shizuku binder: ${e.message}")
            false
        }

        val permissionGranted = try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            issues.add("Permission check failed: ${e.message}")
            false
        }

        val permissionCheckMethod = if (permissionGranted) "Direct" else "N/A"

        if (!permissionGranted) {
            issues.add("Shizuku permission not granted")
        }

        // Check shell execution
        val shellExecutionWorking = if (binderAlive && permissionGranted) {
            try {
                val result = ShizukuUserService.execShellCommand("echo diagnostic_test")
                "diagnostic_test" == result.trim()
            } catch (e: Exception) {
                issues.add("Shell execution failed: ${e.message}")
                false
            }
        } else {
            issues.add("Cannot test shell execution - binder or permission issue")
            false
        }

        // Check system settings access
        val systemSettingsAccessible = if (binderAlive && permissionGranted) {
            try {
                val result = ShizukuUserService.execShellCommand("settings get global multi_window_focus_enabled")
                result.isNotEmpty()
            } catch (e: Exception) {
                issues.add("System settings access failed: ${e.message}")
                false
            }
        } else {
            issues.add("Cannot test system settings access - binder or permission issue")
            false
        }

        // Additional checks
        if (binderAlive && permissionGranted) {
            // Check if we can access input devices
            try {
                val inputDevices = ShizukuUserService.execShellCommand("getevent -i")
                if (inputDevices.isBlank()) {
                    issues.add("No input devices found via getevent")
                }
            } catch (e: Exception) {
                issues.add("Input device enumeration failed: ${e.message}")
            }
        }

        Log.i(TAG, "Diagnostic completed - Issues found: ${issues.size}")
        issues.forEach { issue ->
            Log.w(TAG, "Diagnostic issue: $issue")
        }

        return DiagnosticResult(
            shizukuAvailable = true,
            shizukuVersion = shizukuVersion,
            permissionGranted = permissionGranted,
            binderAlive = binderAlive,
            shellExecutionWorking = shellExecutionWorking,
            systemSettingsAccessible = systemSettingsAccessible,
            binderReceived = binderReceived,
            permissionCheckMethod = permissionCheckMethod,
            issues = issues
        )
    }

    /**
     * Provides troubleshooting suggestions based on diagnostic results
     */
    fun getTroubleshootingSuggestions(result: DiagnosticResult): List<String> {
        val suggestions = mutableListOf<String>()

        if (!result.shizukuAvailable) {
            suggestions.add("Install or update Shizuku Manager app from GitHub releases")
            suggestions.add("Ensure Shizuku library is properly included in the app build")
            return suggestions
        }

        if (result.shizukuVersion in 1..10) {
            suggestions.add("Update Shizuku Manager to version 11 or higher")
        }

        if (!result.binderAlive) {
            suggestions.add("Start Shizuku service in Shizuku Manager app")
            suggestions.add("Check if Shizuku Manager has proper root or ADB access")
            suggestions.add("Restart Shizuku Manager service")
        }

        if (!result.permissionGranted) {
            suggestions.add("Grant permission to this app in Shizuku Manager")
            suggestions.add("Check if app appears in Shizuku permission requests")
            suggestions.add("Try revoking and re-granting permission")
        }

        if (result.binderAlive && result.permissionGranted && !result.shellExecutionWorking) {
            suggestions.add("Check if Shizuku has proper shell execution permissions")
            suggestions.add("Verify device security settings aren't blocking shell commands")
        }

        if (result.issues.isNotEmpty()) {
            suggestions.add("Review the specific issues found in the diagnostic")
        }

        if (result.binderAlive && !result.permissionGranted) {
            suggestions.add("Permission appears granted in Shizuku app but not detected by this app")
            suggestions.add("Try force stopping both this app and Shizuku Manager, then restart both")
            suggestions.add("Check if the app appears in Shizuku's permission management screen")
            suggestions.add("Try revoking and re-granting permission multiple times")
        }

        if (suggestions.isEmpty()) {
            suggestions.add("No specific issues detected - problem may be intermittent or UI-related")
            suggestions.add("Try force stopping and restarting the app")
        }

        return suggestions
    }

    /**
     * Logs diagnostic results for debugging
     */
    fun logDiagnosticResults(result: DiagnosticResult) {
        Log.i(TAG, "=== Shizuku Diagnostic Results ===")
        Log.i(TAG, "Shizuku Available: ${result.shizukuAvailable}")
        Log.i(TAG, "Shizuku Version: ${result.shizukuVersion}")
        Log.i(TAG, "Permission Granted: ${result.permissionGranted}")
        Log.i(TAG, "Permission Check Method: ${result.permissionCheckMethod}")
        Log.i(TAG, "Binder Alive: ${result.binderAlive}")
        Log.i(TAG, "Binder Received: ${result.binderReceived}")
        Log.i(TAG, "Shell Execution Working: ${result.shellExecutionWorking}")
        Log.i(TAG, "System Settings Accessible: ${result.systemSettingsAccessible}")
        Log.i(TAG, "Issues Found: ${result.issues.size}")
        result.issues.forEach { issue ->
            Log.w(TAG, "Issue: $issue")
        }
        Log.i(TAG, "================================")
    }
}