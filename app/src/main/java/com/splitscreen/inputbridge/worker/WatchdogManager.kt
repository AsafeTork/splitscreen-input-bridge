package com.splitscreen.inputbridge.worker

import android.content.Context
import android.os.BatteryManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.splitscreen.inputbridge.repository.ShizukuServiceInterface
import java.util.concurrent.TimeUnit

/**
 * WatchdogManager — Intelligent WorkManager-based watchdog scheduling
 *
 * This class manages the lifecycle of the watchdog worker, providing adaptive
 * scheduling based on battery level, charging state, and bridge activity.
 */
class WatchdogManager(private val context: Context, private val shizukuService: ShizukuServiceInterface) {

    companion object {
        private const val TAG = "WatchdogManager"
    }

    private val workManager: WorkManager = WorkManager.getInstance(context)

    fun startWatchdog(bridgeActive: Boolean) {
        if (!bridgeActive) {
            stopWatchdog()
            return
        }

        // Get current battery level
        val batteryLevel = getBatteryLevel()
        val isCharging = isDeviceCharging()

        // Calculate adaptive interval
        val intervalMinutes = WatchdogWorker.calculateAdaptiveInterval(batteryLevel, isCharging) / (60 * 1000)

        // Create constraints - only run when device is not in battery saver
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        // Create periodic work request
        val watchdogWork = PeriodicWorkRequestBuilder<WatchdogWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    WatchdogWorker.KEY_BRIDGE_ACTIVE to true,
                    WatchdogWorker.KEY_BATTERY_LEVEL to batteryLevel
                )
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                1,
                TimeUnit.MINUTES
            )
            .addTag("shizuku_watchdog")
            .build()

        // Enqueue the work with unique name to replace any existing watchdog
        workManager.enqueueUniquePeriodicWork(
            WatchdogWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            watchdogWork
        )
    }

    fun stopWatchdog() {
        workManager.cancelUniqueWork(WatchdogWorker.WORK_NAME)
    }

    fun updateWatchdogParameters(bridgeActive: Boolean) {
        // If bridge becomes inactive, stop watchdog
        if (!bridgeActive) {
            stopWatchdog()
            return
        }

        // If bridge is active, restart watchdog with fresh parameters
        startWatchdog(true)
    }

    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryLevel.coerceIn(0, 100)
        } catch (e: Exception) {
            100 // Default to full battery if we can't determine
        }
    }

    private fun isDeviceCharging(): Boolean {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val chargeStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            chargeStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                    chargeStatus == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            false
        }
    }

    fun cleanup() {
        stopWatchdog()
    }
}
