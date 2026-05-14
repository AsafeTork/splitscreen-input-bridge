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
            ShizukuUserService.isReady()
        } catch (e: Exception) {
            Log.e(TAG, "isReady failed: ${e.message}")
            false
        }
    }

    override fun getDeviceMolecularFingerprint(device: InputDevice): String {
        return DeviceFingerprintUtil.getEnhancedDeviceDescriptor(device)
    }
}
