package com.splitscreen.inputbridge.util

import android.util.Log
import android.view.InputDevice
import com.splitscreen.inputbridge.ShizukuUserService

/**
 * Utility class for device fingerprinting operations
 *
 * This class consolidates all device fingerprinting logic to avoid duplication
 * and ensure consistent device identification across the application.
 */
object DeviceFingerprintUtil {

    private const val TAG = "DeviceFingerprintUtil"

    /**
     * Computes a unique fingerprint for a device by extracting hardware identifiers
     * from /proc/bus/input/devices via Shizuku shell. This includes:
     * - EVIOCGUNIQ (unique ID like MAC address for Bluetooth devices)
     * - EVIOCGPHYS (physical path)
     * - Bus/Vendor/Product/Version (EVIOCGID)
     */
    fun computeDeviceFingerprint(descriptor: String): String {
        return try {
            // Extract device name from descriptor (last part after |)
            val deviceName = descriptor.split('|').lastOrNull()?.trim().orEmpty()

            // Query /proc/bus/input/devices for this device
            val deviceInfo = ShizukuUserService.execShellCommand(
                "cat /proc/bus/input/devices | grep -A 10 'N: Name=\"${deviceName}\"'"
            )

            // Parse unique ID (EVIOCGUNIQ equivalent)
            val uniqueId = deviceInfo.lines().find { it.startsWith("U: Uniq=") }
                ?.substringAfter("U: Uniq=")?.trim() ?: ""

            // Parse physical path (EVIOCGPHYS equivalent)
            val physicalPath = deviceInfo.lines().find { it.startsWith("P: ") }
                ?.substringAfter("P: ")?.trim() ?: ""

            // Parse device ID components (EVIOCGID equivalent)
            val idLine = deviceInfo.lines().find { it.startsWith("I: Bus=") }
            val busType = idLine?.substringAfter("Bus=")?.substringBefore(" ")?.toIntOrNull() ?: 0
            val vendorId = idLine?.substringAfter("Vendor=")?.substringBefore(" ")?.toIntOrNull() ?: 0
            val productId = idLine?.substringAfter("Product=")?.substringBefore(" ")?.toIntOrNull() ?: 0
            val version = idLine?.substringAfter("Version=")?.toIntOrNull() ?: 0

            // Create molecular fingerprint combining all immutable identifiers
            "${descriptor}|${uniqueId}|${physicalPath}|${busType}:${vendorId}:${productId}:${version}"

        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute device fingerprint: ${e.message}")
            descriptor // Fallback to base descriptor
        }
    }

    /**
     * Gets enhanced device descriptor using molecular fingerprinting for reliable
     * device differentiation, even for identical controllers.
     */
    fun getEnhancedDeviceDescriptor(device: InputDevice): String {
        return computeDeviceFingerprint(device.descriptor)
    }
}