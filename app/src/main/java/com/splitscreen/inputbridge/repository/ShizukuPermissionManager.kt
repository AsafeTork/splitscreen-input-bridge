package com.splitscreen.inputbridge.repository

import android.content.Context
import android.util.Log
import androidx.work.*
import com.splitscreen.inputbridge.ShizukuUserService
import com.splitscreen.inputbridge.util.ShizukuDiagnosticUtil
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * ShizukuPermissionManager — Manages Shizuku permission state and automatic requests
 *
 * This class provides continuous monitoring of Shizuku permission status and
 * can automatically request permission when it's revoked, ensuring the bridge
 * service maintains proper functionality.
 *
 * Enhanced with more responsive permission detection and better state management.
 */
class ShizukuPermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuPermissionManager"
        private const val PERMISSION_CHECK_WORK_NAME = "shizuku_permission_check"
    }

    private val workManager = WorkManager.getInstance(context)
    private var permissionCallback: ((Boolean) -> Unit)? = null

    fun setPermissionCallback(callback: (Boolean) -> Unit) {
        permissionCallback = callback
    }

    /**
     * Starts continuous monitoring of Shizuku permission status
     * Uses both WorkManager for background checks and more frequent foreground checks
     */
    fun startPermissionMonitoring() {
        // Create periodic work request to check permission status
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        // More frequent checks (every 5 minutes instead of 15)
        val permissionCheckWork = PeriodicWorkRequestBuilder<ShizukuPermissionWorker>(5, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .addTag(PERMISSION_CHECK_WORK_NAME)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERMISSION_CHECK_WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            permissionCheckWork
        )

        Log.d(TAG, "Started Shizuku permission monitoring with 5-minute intervals")
    }

    /**
     * Stops continuous monitoring of Shizuku permission status
     */
    fun stopPermissionMonitoring() {
        workManager.cancelAllWorkByTag(PERMISSION_CHECK_WORK_NAME)
        Log.d(TAG, "Stopped Shizuku permission monitoring")
    }

    /**
     * Checks current Shizuku permission status
     */
    fun isShizukuPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Shizuku permission: ${e.message}")
            false
        }
    }

    /**
     * Requests Shizuku permission if not granted
     */
    fun requestShizukuPermission(requestCode: Int) {
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            Log.w(TAG, "Shizuku version incompatible (required v11+)")
            return
        }

        if (!isShizukuPermissionGranted()) {
            try {
                Shizuku.requestPermission(requestCode)
                Log.i(TAG, "Requested Shizuku permission with requestCode: $requestCode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request Shizuku permission: ${e.message}")
            }
        }
    }

    /**
     * Checks if Shizuku binder is alive
     */
    fun isShizukuBinderAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Shizuku binder: ${e.message}")
            false
        }
    }

    /**
     * Performs a comprehensive health check of Shizuku
     */
    fun isShizukuHealthy(): Boolean {
        return try {
            val binderAlive = isShizukuBinderAlive()
            val permissionGranted = isShizukuPermissionGranted()

            if (!binderAlive) {
                Log.w(TAG, "Shizuku binder is not alive")
                return false
            }

            if (!permissionGranted) {
                Log.w(TAG, "Shizuku permission not granted")
                return false
            }

            // Test execution capability
            val testResult = ShizukuUserService.execShellCommand("echo test")
            if (testResult.trim() != "test") {
                Log.w(TAG, "Shizuku shell execution test failed")
                return false
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku health check failed: ${e.message}")
            false
        }
    }

    /**
     * Performs an enhanced health check with recovery attempts
     * This method tries multiple approaches to verify and recover Shizuku state
     */
    fun performEnhancedHealthCheck(): ShizukuHealthStatus {
        return try {
            val binderAlive = isShizukuBinderAlive()
            var permissionGranted = isShizukuPermissionGranted()
            var recoveryAttempted = false
            var recoverySuccessful = false

            if (binderAlive && !permissionGranted) {
                Log.w(TAG, "Binder alive but permission not granted - attempting recovery")
                recoveryAttempted = true

                try {
                    Shizuku.getBinder()
                    permissionGranted = isShizukuPermissionGranted()

                    if (permissionGranted) {
                        recoverySuccessful = true
                        Log.i(TAG, "Permission recovery successful")
                    } else {
                        Log.w(TAG, "Permission recovery unsuccessful")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Permission recovery attempt failed: ${e.message}")
                }
            }

            // Test execution capability if permission is granted
            var shellExecutionWorking = false
            if (permissionGranted) {
                try {
                    val testResult = ShizukuUserService.execShellCommand("echo test")
                    shellExecutionWorking = (testResult.trim() == "test")
                } catch (e: Exception) {
                    Log.e(TAG, "Shell execution test failed: ${e.message}")
                }
            }

            ShizukuHealthStatus(
                binderAlive = binderAlive,
                permissionGranted = permissionGranted,
                shellExecutionWorking = shellExecutionWorking,
                recoveryAttempted = recoveryAttempted,
                recoverySuccessful = recoverySuccessful
            )
        } catch (e: Exception) {
            Log.e(TAG, "Enhanced Shizuku health check failed: ${e.message}")
            ShizukuHealthStatus(
                binderAlive = false,
                permissionGranted = false,
                shellExecutionWorking = false,
                recoveryAttempted = false,
                recoverySuccessful = false
            )
        }
    }

    /**
     * Data class to hold detailed Shizuku health status
     */
    data class ShizukuHealthStatus(
        val binderAlive: Boolean,
        val permissionGranted: Boolean,
        val shellExecutionWorking: Boolean,
        val recoveryAttempted: Boolean,
        val recoverySuccessful: Boolean
    )

    /**
     * Performs a quick health check of Shizuku without shell execution test
     * Useful for frequent UI updates
     */
    fun isShizukuHealthyQuick(): Boolean {
        return try {
            val binderAlive = isShizukuBinderAlive()
            val permissionGranted = isShizukuPermissionGranted()

            binderAlive && permissionGranted
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku quick health check failed: ${e.message}")
            false
        }
    }
}

/**
 * ShizukuPermissionWorker — WorkManager worker for periodic permission checks
 */
class ShizukuPermissionWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "ShizukuPermissionWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            val permissionManager = ShizukuPermissionManager(applicationContext)

            // Perform comprehensive diagnostic
            val diagnosticResult = ShizukuDiagnosticUtil.performDiagnostic(applicationContext)
            ShizukuDiagnosticUtil.logDiagnosticResults(diagnosticResult)

            // Check if Shizuku is healthy
            val isHealthy = permissionManager.isShizukuHealthy()

            if (!isHealthy) {
                Log.w(TAG, "Shizuku is not healthy, diagnostic shows ${diagnosticResult.issues.size} issues")

                // If binder is dead, we can't do much except log
                if (!permissionManager.isShizukuBinderAlive()) {
                    Log.e(TAG, "Shizuku binder is dead, cannot recover automatically")
                    return Result.failure()
                }

                // If permission is revoked, we need to request it again
                if (!permissionManager.isShizukuPermissionGranted()) {
                    Log.i(TAG, "Shizuku permission revoked, needs manual reauthorization")
                    // We can't automatically request permission from a background worker
                    // This should be handled by the UI layer
                    return Result.success()
                }
            }

            Log.d(TAG, "Shizuku health check passed")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku permission worker failed: ${e.message}")
            Result.retry()
        }
    }
}