package com.splitscreen.inputbridge.example

import android.content.Context
import android.util.Log
import com.splitscreen.inputbridge.repository.ShizukuPermissionManager
import com.splitscreen.inputbridge.repository.ShizukuServiceInterface
import com.splitscreen.inputbridge.repository.ShizukuServiceRepository

/**
 * ShizukuPermissionExample — Example usage of Shizuku permission management
 *
 * This example demonstrates how to properly handle Shizuku permissions in your application.
 * It shows continuous monitoring, automatic permission requests, and state management.
 */
class ShizukuPermissionExample(private val context: Context) {

    private val shizukuPermissionManager = ShizukuPermissionManager(context)
    private val shizukuService: ShizukuServiceInterface = ShizukuServiceRepository()

    companion object {
        private const val TAG = "ShizukuPermissionExample"
    }

    /**
     * Initialize Shizuku permission management
     *
     * This method sets up continuous monitoring of Shizuku permissions and
     * handles automatic recovery when permissions change.
     */
    fun initializeShizukuPermissionManagement() {
        // Set up permission callback to handle permission changes
        shizukuPermissionManager.setPermissionCallback { isGranted ->
            if (isGranted) {
                Log.i(TAG, "Shizuku permission granted")
                // Start your service or perform actions that require Shizuku
                startShizukuDependentFeatures()
            } else {
                Log.w(TAG, "Shizuku permission revoked")
                // Stop your service or disable features that require Shizuku
                stopShizukuDependentFeatures()
            }
        }

        // Start continuous permission monitoring
        shizukuPermissionManager.startPermissionMonitoring()

        // Check initial permission status
        if (shizukuService.isReady()) {
            Log.i(TAG, "Shizuku is ready, starting dependent features")
            startShizukuDependentFeatures()
        } else {
            Log.w(TAG, "Shizuku not ready, waiting for permission")
        }
    }

    /**
     * Request Shizuku permission from user
     *
     * This method requests Shizuku permission from the user with proper
     * version checking and error handling.
     */
    fun requestShizukuPermission(requestCode: Int) {
        shizukuPermissionManager.requestShizukuPermission(requestCode)
    }

    /**
     * Check if Shizuku is healthy and ready to use
     *
     * This method performs a comprehensive health check including:
     * - Binder availability
     * - Permission status
     * - Execution capability
     */
    fun isShizukuHealthy(): Boolean {
        return shizukuPermissionManager.isShizukuHealthy()
    }

    /**
     * Start features that depend on Shizuku
     *
     * This method is called when Shizuku permissions are granted and
     * the service is ready to perform privileged operations.
     */
    private fun startShizukuDependentFeatures() {
        Log.i(TAG, "Starting Shizuku-dependent features")
        // Implement your Shizuku-dependent features here
        // For example:
        // - Input event injection
        // - System setting modifications
        // - Shell command execution
    }

    /**
     * Stop features that depend on Shizuku
     *
     * This method is called when Shizuku permissions are revoked or
     * the service becomes unavailable.
     */
    private fun stopShizukuDependentFeatures() {
        Log.i(TAG, "Stopping Shizuku-dependent features")
        // Implement cleanup for Shizuku-dependent features here
        // For example:
        // - Stop input event injection
        // - Revert system setting changes
        // - Clean up resources
    }

    /**
     * Cleanup resources
     *
     * This method should be called when the application is destroyed
     * to properly clean up Shizuku monitoring resources.
     */
    fun cleanup() {
        shizukuPermissionManager.stopPermissionMonitoring()
        Log.i(TAG, "Shizuku permission management cleaned up")
    }

    /**
     * Handle permission recovery
     *
     * This method demonstrates how to handle permission recovery
     * when Shizuku permissions are temporarily lost.
     */
    fun handlePermissionRecovery() {
        // Check if Shizuku is healthy
        if (isShizukuHealthy()) {
            Log.i(TAG, "Shizuku is healthy, resuming operations")
            startShizukuDependentFeatures()
        } else {
            Log.w(TAG, "Shizuku is not healthy, requesting permission")
            // Request permission if not granted
            if (!shizukuPermissionManager.isShizukuPermissionGranted()) {
                requestShizukuPermission(100) // Use appropriate request code
            }
        }
    }
}