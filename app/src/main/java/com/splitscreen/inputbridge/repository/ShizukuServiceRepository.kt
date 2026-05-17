package com.splitscreen.inputbridge.repository

import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import com.splitscreen.inputbridge.ShizukuUserService
import com.splitscreen.inputbridge.util.DeviceFingerprintUtil

/**
 * ShizukuServiceRepository — Production implementation of ShizukuServiceInterface
 *
 * This class adapts the existing ShizukuUserService object to the new interface,
 * providing a clean separation between interface and implementation.
 */
class ShizukuServiceRepository : ShizukuServiceInterface {

    companion object {
        private const val TAG = "ShizukuServiceRepo"
    }

    override fun injectInputEvent(event: InputEvent): Boolean {
        return try {
            ShizukuUserService.injectInputEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent failed: ${e.message}")
            false
        }
    }

    override fun execShellCommand(command: String): String {
        return try {
            ShizukuUserService.execShellCommand(command)
        } catch (e: Exception) {
            Log.e(TAG, "execShellCommand failed: ${e.message}")
            ""
        }
    }

    override fun getGlobalSetting(key: String): String {
        return try {
            ShizukuUserService.getGlobalSetting(key)
        } catch (e: Exception) {
            Log.e(TAG, "getGlobalSetting failed: ${e.message}")
            ""
        }
    }

    override fun isReady(): Boolean {
        return try {
            val isReady = ShizukuUserService.isReady()
            if (!isReady) {
                Log.w(TAG, "Shizuku service not ready")
            }
            isReady
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in isReady: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException in isReady: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "isReady failed: ${e.message}")
            false
        }
    }

    override fun getDeviceMolecularFingerprint(device: InputDevice): String {
        return DeviceFingerprintUtil.getEnhancedDeviceDescriptor(device)
    }

    override fun startLinuxReader(
        callback: com.splitscreen.inputbridge.ILinuxInputCallback,
        p1V: Int, p1P: Int, p1N: String,
        p2V: Int, p2P: Int, p2N: String
    ) {
        ShizukuUserService.startLinuxReader(callback, p1V, p1P, p1N, p2V, p2P, p2N)
    }

    override fun stopLinuxReader() {
        ShizukuUserService.stopLinuxReader()
    }
}
