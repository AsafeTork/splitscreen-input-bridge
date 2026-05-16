package com.splitscreen.inputbridge.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.splitscreen.inputbridge.repository.ShizukuServiceInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WatchdogWorker — WorkManager worker for intelligent Shizuku health monitoring
 *
 * This worker performs periodic health checks on Shizuku and can trigger recovery
 * actions when issues are detected. It uses adaptive scheduling based on battery
 * level and system conditions.
 */
class WatchdogWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val shizukuService: com.splitscreen.inputbridge.repository.ShizukuServiceInterface = com.splitscreen.inputbridge.repository.ShizukuServiceRepository()

    companion object {
        private const val TAG = "WatchdogWorker"
        const val WORK_NAME = "ShizukuWatchdogWork"

        // Input keys
        const val KEY_BRIDGE_ACTIVE = "bridge_active"
        const val KEY_BATTERY_LEVEL = "battery_level"

        /**
         * Calculate adaptive watchdog interval based on conditions
         */
        fun calculateAdaptiveInterval(batteryLevel: Int, isCharging: Boolean): Long {
            // Base interval: 5 minutes
            var interval = 5L * 60 * 1000

            // Adjust based on battery level
            when {
                batteryLevel < 15 -> interval *= 3 // 15 minutes when battery critical
                batteryLevel < 30 -> interval *= 2 // 10 minutes when battery low
                isCharging -> interval = 2L * 60 * 1000 // 2 minutes when charging
            }

            return interval
        }
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                // Extract input data
                val bridgeActive = inputData.getBoolean(KEY_BRIDGE_ACTIVE, false)
                val batteryLevel = inputData.getInt(KEY_BATTERY_LEVEL, 100)

                Log.d(TAG, "Watchdog check: bridgeActive=$bridgeActive, batteryLevel=$batteryLevel%")

                if (!bridgeActive) {
                    Log.d(TAG, "Watchdog: Bridge is inactive, skipping health check")
                    return@withContext Result.success()
                }

                // Perform health check
                val isShizukuHealthy = checkShizukuHealth()

                if (!isShizukuHealthy) {
                    Log.w(TAG, "Watchdog: Shizuku health check failed, attempting recovery")
                    attemptRecovery()
                    return@withContext Result.retry() // Retry soon to verify recovery
                }

                Log.d(TAG, "Watchdog: Shizuku health check passed")
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Watchdog work failed: ${e.message}")
                Result.retry() // Retry on failure
            }
        }
    }

    private suspend fun checkShizukuHealth(): Boolean {
        return try {
            // Check if Shizuku is ready
            val isReady = shizukuService.isReady()

            if (!isReady) {
                Log.w(TAG, "Shizuku not ready - binder dead or permission revoked")
                return false
            }

            // Additional health check: verify we can execute a simple command
            val testResult = shizukuService.execShellCommand("echo test")

            if (testResult.trim() != "test") {
                Log.w(TAG, "Shizuku shell command failed - execution environment compromised")
                return false
            }

            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Shizuku security exception: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Health check exception: ${e.message}")
            false
        }
    }

    private suspend fun attemptRecovery() {
        try {
            // Attempt to reapply system hacks
            val result = shizukuService.execShellCommand("settings put global multi_window_focus_enabled 1")

            if (result.isNotBlank()) {
                Log.i(TAG, "Recovery: Successfully reapplied system hacks")
            } else {
                Log.w(TAG, "Recovery: Failed to reapply system hacks")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recovery attempt failed: ${e.message}")
        }
    }

}
