package com.splitscreen.inputbridge.repository

import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import com.splitscreen.inputbridge.ShizukuUserService

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
        return try {
            // Extract device info from /proc/bus/input/devices
            val deviceInfo = ShizukuUserService.execShellCommand(
                "cat /proc/bus/input/devices | grep -A 10 'N: Name=\"${device.name}\"'"
            )

            // Parse unique ID (EVIOCGUNIQ equivalent)
            val uniqueId = deviceInfo.lines().find { it.startsWith("U: Uniq=") }
                ?.substringAfter("=")?.trim() ?: ""

            // Parse physical path (EVIOCGPHYS equivalent - may contain MAC for Bluetooth)
            val physicalPath = deviceInfo.lines().find { it.startsWith("P: ") }
                ?.substringAfter("P: ")?.trim() ?: ""

            // Parse device ID components (EVIOCGID equivalent)
            val idLine = deviceInfo.lines().find { it.startsWith("I: Bus=") }
            val busType = idLine?.substringAfter("Bus=")?.substringBefore(" ")?.toIntOrNull() ?: 0
            val vendorId = idLine?.substringAfter("Vendor=")?.substringBefore(" ")?.toIntOrNull() ?: 0
            val productId = idLine?.substringAfter("Product=")?.substringBefore(" ")?.toIntOrNull() ?: 0
            val version = idLine?.substringAfter("Version=")?.toIntOrNull() ?: 0

            // Create molecular fingerprint combining all immutable identifiers
            "${device.descriptor}|$uniqueId|$physicalPath|$busType:$vendorId:$productId:$version"
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException in fingerprinting: ${e.message}")
            device.descriptor
        } catch (e: IllegalStateException) {
            Log.w(TAG, "IllegalStateException in fingerprinting: ${e.message}")
            device.descriptor
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create molecular fingerprint: ${e.message}")
            device.descriptor
        }
    }
}
