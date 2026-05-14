package com.splitscreen.inputbridge.repository

import android.view.InputEvent

/**
 * ShizukuServiceInterface — Interface for Shizuku operations to enable testing and mocking
 *
 * This interface abstracts all Shizuku operations, allowing for easy unit testing
 * and dependency injection. Implementations can be swapped between production
 * and test environments.
 */
interface ShizukuServiceInterface {

    /**
     * Injects an input event via Shizuku's privileged binder
     * @param event The input event to inject
     * @return true if injection was successful, false otherwise
     */
    fun injectInputEvent(event: InputEvent): Boolean

    /**
     * Executes a shell command through the Shizuku process
     * @param command The shell command to execute
     * @return The command output as a string
     */
    fun execShellCommand(command: String): String

    /**
     * Gets the current value of a global system setting
     * @param key The setting key to query
     * @return The setting value as a string
     */
    fun getGlobalSetting(key: String): String

    /**
     * Checks if Shizuku is ready (binder alive and permission granted)
     * @return true if Shizuku is ready for use, false otherwise
     */
    fun isReady(): Boolean

    /**
     * Enhanced device descriptor that combines multiple hardware identifiers
     * @param device The input device to fingerprint
     * @return A molecular fingerprint string unique to this device
     */
    fun getDeviceMolecularFingerprint(device: android.view.InputDevice): String
}
