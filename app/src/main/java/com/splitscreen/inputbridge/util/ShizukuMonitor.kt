package com.splitscreen.inputbridge.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.splitscreen.inputbridge.ShizukuUserService
import rikka.shizuku.Shizuku

/**
 * ShizukuMonitor — Enhanced monitoring of Shizuku permission and binder status
 *
 * This utility provides more responsive monitoring of Shizuku status changes
 * with configurable check intervals and callback mechanisms for immediate
 * UI updates when permission status changes.
 */
class ShizukuMonitor(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuMonitor"
        private const val DEFAULT_CHECK_INTERVAL_MS = 1000L // 1 second for foreground checks
    }

    interface ShizukuStatusListener {
        fun onShizukuStatusChanged(available: Boolean, permissionGranted: Boolean)
        fun onShizukuBinderDied()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<ShizukuStatusListener>()
    private var isMonitoring = false
    private var checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS
    private var lastAvailableState = false
    private var lastPermissionState = false

    private val permissionCheckRunnable = object : Runnable {
        override fun run() {
            checkShizukuStatus()
            if (isMonitoring) {
                handler.postDelayed(this, checkIntervalMs)
            }
        }
    }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        // Immediate check when binder is received
        handler.post {
            checkShizukuStatus()
        }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder died")
        handler.post {
            notifyBinderDied()
        }
    }

    fun addListener(listener: ShizukuStatusListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ShizukuStatusListener) {
        listeners.remove(listener)
    }

    fun setCheckInterval(intervalMs: Long) {
        checkIntervalMs = intervalMs
    }

    fun startMonitoring() {
        if (isMonitoring) return

        isMonitoring = true
        Log.d(TAG, "Starting Shizuku monitoring")

        // Add Shizuku listeners
        Shizuku.addBinderReceivedListener(shizukuBinderListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)

        // Start periodic checking
        handler.post(permissionCheckRunnable)
    }

    fun stopMonitoring() {
        if (!isMonitoring) return

        isMonitoring = false
        Log.d(TAG, "Stopping Shizuku monitoring")

        // Remove Shizuku listeners
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)

        // Stop periodic checking
        handler.removeCallbacks(permissionCheckRunnable)
    }

    private fun checkShizukuStatus() {
        try {
            val isBinderAlive = Shizuku.pingBinder()
            val isPermissionGranted = ShizukuUserService.isPermissionGranted()

            // Only notify if state has actually changed
            if (isBinderAlive != lastAvailableState || isPermissionGranted != lastPermissionState) {
                Log.d(TAG, "Shizuku status changed - available: $isBinderAlive, permission: $isPermissionGranted")
                lastAvailableState = isBinderAlive
                lastPermissionState = isPermissionGranted
                notifyStatusChanged(isBinderAlive, isPermissionGranted)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Shizuku status", e)
            // If we get an exception, assume binder is dead
            if (lastAvailableState) {
                lastAvailableState = false
                notifyBinderDied()
            }
        }
    }

    private fun notifyStatusChanged(available: Boolean, permissionGranted: Boolean) {
        listeners.forEach { listener ->
            try {
                listener.onShizukuStatusChanged(available, permissionGranted)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }

    private fun notifyBinderDied() {
        listeners.forEach { listener ->
            try {
                listener.onShizukuBinderDied()
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener of binder death", e)
            }
        }
    }

    /**
     * Performs an immediate check of Shizuku status and notifies listeners if changed
     */
    fun forceCheckStatus() {
        handler.post {
            checkShizukuStatus()
        }
    }
}