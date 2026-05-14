package com.splitscreen.inputbridge.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * AdbTestReceiver — BroadcastReceiver for handling ADB test commands
 *
 * This receiver allows testing Shizuku functionality via ADB commands,
 * which is particularly useful for the user's local network setup.
 *
 * To use:
 * adb shell am broadcast -n com.splitscreen.inputbridge/.util.AdbTestReceiver --es action "connectivity"
 */
class AdbTestReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AdbTestReceiver"
        const val ACTION_TEST_CONNECTIVITY = "connectivity"
        const val ACTION_TEST_SHELL = "shell"
        const val ACTION_TEST_INPUT = "input"
        const val ACTION_DIAGNOSE = "diagnose"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: return
        val result = intent.getStringExtra("command") ?: ""

        Log.d(TAG, "Received ADB test command: $action")

        val response = when (action) {
            ACTION_TEST_CONNECTIVITY -> {
                AdbTestingUtil.testConnectivity(context)
            }
            ACTION_TEST_SHELL -> {
                AdbTestingUtil.testShellCommand(result)
            }
            ACTION_TEST_INPUT -> {
                AdbTestingUtil.testInputInjection()
            }
            ACTION_DIAGNOSE -> {
                AdbTestingUtil.performComprehensiveDiagnostic(context)
            }
            else -> {
                "Unknown action: $action"
            }
        }

        Log.i(TAG, "Test result: $response")

        // For ADB commands, we just log the results
        // In a real implementation, you might want to show a Toast or notification
    }
}