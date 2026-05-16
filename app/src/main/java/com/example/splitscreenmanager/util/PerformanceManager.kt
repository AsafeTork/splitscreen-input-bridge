package com.example.splitscreenmanager.util

import android.util.Log
import com.example.splitscreenmanager.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * PerformanceManager handles system-level performance optimizations for split-screen applications.
 * It manages system settings, process priorities, and core affinity to improve application performance.
 *
 * Key features:
 * - System performance settings optimization
 * - Input latency reduction
 * - Bloatware suspension
 * - Process priority and core affinity management
 * - Automatic restoration of system settings
 */
class PerformanceManager(private val viewModel: AppViewModel) {

    companion object {
        private const val TAG = "PerformanceManager"

        // Timing constants
        private const val COMMAND_DELAY_MS = 100L
        private const val PROCESS_OPTIMIZATION_INTERVAL_MS = 2000L
        private const val MAX_OPTIMIZATION_ATTEMPTS = 5

        // Performance tuning constants
        private const val BIG_CORES_MASK = "f0" // Typically cores 4,5,6,7 in 8-core SOCs
        private const val MAX_PROCESS_PRIORITY = -20

        // System performance settings
        private val PERFORMANCE_SETTINGS = listOf(
            "settings put global sustained_performance_mode 1",
            "settings put global force_gpu_rendering 1",
            "settings put global hwui_disable_vsync true"
        )

        // Input optimization settings
        private val INPUT_OPTIMIZATIONS = listOf(
            "setprop persist.sys.input.flushing 0",
            "setprop debug.input.dispatch_policy 1",
            "setprop windowsmgr.max_events_per_sec 240"
        )

        // Known bloatware packages that may interfere with performance
        private val BLOATWARE_PACKAGES = listOf(
            "com.samsung.android.game.gos",
            "com.xiaomi.joyose"
        )

        // Restoration commands
        private val RESTORE_SETTINGS = listOf(
            "settings put global sustained_performance_mode 0",
            "settings put global force_gpu_rendering 0",
            "settings put global hwui_disable_vsync false"
        )

        private val RESTORE_INPUT = listOf(
            "setprop persist.sys.input.flushing 1",
            "setprop debug.input.dispatch_policy 0",
            "setprop windowsmgr.max_events_per_sec 60"
        )
    }

    /**
     * Applies deep performance optimizations for the specified packages.
     *
     * @param packageA First package name to optimize
     * @param packageB Second package name to optimize
     */
    suspend fun applyDeepOptimizations(packageA: String, packageB: String) {
        withContext(Dispatchers.IO) {
            try {
                logPerformanceEvent("Applying optimizations for $packageA and $packageB")

                // Apply optimizations in sequence
                applySystemSettings(PERFORMANCE_SETTINGS)
                applySystemSettings(INPUT_OPTIMIZATIONS)
                suspendBloatware()
                optimizeProcesses(packageA, packageB)

                logPerformanceEvent("Optimizations applied successfully")
            } catch (e: Exception) {
                handleError("Error applying optimizations", e)
            }
        }
    }

    /**
     * Restores all system settings to their default values.
     * This should be called when performance optimizations are no longer needed.
     */
    suspend fun restoreSystemSettings() {
        withContext(Dispatchers.IO) {
            try {
                applySystemSettings(RESTORE_SETTINGS)
                applySystemSettings(RESTORE_INPUT)
                restoreBloatware()

                Log.d(TAG, "System settings restored successfully")
                logPerformanceEvent("System settings restored")
            } catch (e: Exception) {
                handleError("Error restoring system settings", e)
            }
        }
    }

    // =========================================================================
    // Private Helper Methods
    // =========================================================================

    /**
     * Applies a list of system settings commands with appropriate delays.
     *
     * @param commands List of shell commands to execute
     */
    private suspend fun applySystemSettings(commands: List<String>) {
        commands.forEach { command ->
            executeCommandWithDelay(command, COMMAND_DELAY_MS)
        }
    }

    /**
     * Suspends known bloatware packages that may interfere with performance.
     */
    private suspend fun suspendBloatware() {
        BLOATWARE_PACKAGES.forEach { packageName ->
            executeCommandWithDelay("pm disable-user --user 0 $packageName", COMMAND_DELAY_MS)
        }
    }

    /**
     * Restores bloatware packages to their normal state.
     */
    private suspend fun restoreBloatware() {
        BLOATWARE_PACKAGES.forEach { packageName ->
            executeCommandWithDelay("pm enable $packageName", COMMAND_DELAY_MS)
        }
    }

    /**
     * Optimizes process priority and core affinity for the specified packages.
     * Makes multiple attempts to ensure processes are optimized as they start.
     *
     * @param packages Package names to optimize
     */
    private suspend fun optimizeProcesses(vararg packages: String) {
        repeat(MAX_OPTIMIZATION_ATTEMPTS) { attempt ->
            delay(PROCESS_OPTIMIZATION_INTERVAL_MS)

            packages.forEach { pkg ->
                optimizeSingleProcess(pkg)
            }
        }
    }

    /**
     * Optimizes a single process by setting maximum priority and big core affinity.
     *
     * @param packageName Name of the package to optimize
     */
    private suspend fun optimizeSingleProcess(packageName: String) {
        var pid: String? = null
        try {
            pid = viewModel.execShellCommand("pidof $packageName").trim()

            if (isValidPid(pid)) {
                // Set maximum priority
                executeCommandWithDelay("renice $MAX_PROCESS_PRIORITY $pid", COMMAND_DELAY_MS)

                // Set big core affinity
                executeCommandWithDelay("taskset -p $BIG_CORES_MASK $pid", COMMAND_DELAY_MS)

                logKernelEvent("Optimized $packageName (PID: $pid) on Big Cores")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to optimize process $packageName (PID: ${pid ?: "unknown"})", e)
        }
    }

    /**
     * Executes a shell command with a specified delay afterward.
     *
     * @param command Command to execute
     * @param delayMs Delay in milliseconds after execution
     */
    private suspend fun executeCommandWithDelay(command: String, delayMs: Long) {
        viewModel.execShellCommand(command)
        delay(delayMs)
    }

    /**
     * Validates if a string represents a valid PID.
     *
     * @param pid String to validate
     * @return true if valid PID, false otherwise
     */
    private fun isValidPid(pid: String): Boolean {
        return pid.isNotEmpty() && pid.all { it.isDigit() }
    }

    /**
     * Logs a performance-related event through the view model.
     *
     * @param message Message to log
     */
    private fun logPerformanceEvent(message: String) {
        viewModel.reportError("Performance", message)
    }

    /**
     * Logs a kernel-related event through the view model.
     *
     * @param message Message to log
     */
    private fun logKernelEvent(message: String) {
        viewModel.reportError("Kernel", message)
    }

    /**
     * Handles errors by logging and reporting through the view model.
     *
     * @param context Context message describing the error
     * @param e Exception that occurred
     */
    private fun handleError(context: String, e: Exception) {
        Log.e(TAG, context, e)
        viewModel.reportError("Performance", "$context: ${e.message}")
    }
}