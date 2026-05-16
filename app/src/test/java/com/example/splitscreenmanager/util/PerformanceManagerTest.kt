package com.example.splitscreenmanager.util

import com.example.splitscreenmanager.viewmodel.AppViewModel
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@ExperimentalCoroutinesApi
class PerformanceManagerTest {

    @MockK
    private lateinit var mockViewModel: AppViewModel

    private lateinit var performanceManager: PerformanceManager
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        // Mock the vmScope to use test dispatcher
        coEvery { mockViewModel.vmScope } returns kotlinx.coroutines.CoroutineScope(testDispatcher)

        // Mock reportError to do nothing
        coEvery { mockViewModel.reportError(any(), any()) } just runs

        // Mock execShellCommand to return empty string by default
        coEvery { mockViewModel.execShellCommand(any()) } returns ""

        performanceManager = PerformanceManager(mockViewModel)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testApplyDeepOptimizations_CallsExpectedMethods() = runTest {
        // Setup mock to return a valid PID for testing
        coEvery { mockViewModel.execShellCommand("pidof test.package") } returns "1234"

        performanceManager.applyDeepOptimizations("test.package", "test.package2")

        // Verify system settings were applied
        coVerify { mockViewModel.execShellCommand("settings put global sustained_performance_mode 1") }
        coVerify { mockViewModel.execShellCommand("settings put global force_gpu_rendering 1") }
        coVerify { mockViewModel.execShellCommand("settings put global hwui_disable_vsync true") }

        // Verify input optimizations were applied
        coVerify { mockViewModel.execShellCommand("setprop persist.sys.input.flushing 0") }
        coVerify { mockViewModel.execShellCommand("setprop debug.input.dispatch_policy 1") }
        coVerify { mockViewModel.execShellCommand("setprop windowsmgr.max_events_per_sec 240") }

        // Verify bloatware suspension
        coVerify { mockViewModel.execShellCommand("pm disable-user --user 0 com.samsung.android.game.gos") }
        coVerify { mockViewModel.execShellCommand("pm disable-user --user 0 com.xiaomi.joyose") }

        // Verify process optimization was called (5 attempts * 2 packages)
        // Each attempt tries to optimize both packages
        coVerify(atLeast = 1) { mockViewModel.execShellCommand("pidof test.package") }
        coVerify(atLeast = 1) { mockViewModel.execShellCommand("pidof test.package2") }
    }

    @Test
    fun testRestoreSystemSettings_CallsExpectedMethods() = runTest {
        performanceManager.restoreSystemSettings()

        // Verify system settings were restored
        coVerify { mockViewModel.execShellCommand("settings put global sustained_performance_mode 0") }
        coVerify { mockViewModel.execShellCommand("settings put global force_gpu_rendering 0") }
        coVerify { mockViewModel.execShellCommand("settings put global hwui_disable_vsync false") }

        // Verify input settings were restored
        coVerify { mockViewModel.execShellCommand("setprop persist.sys.input.flushing 1") }
        coVerify { mockViewModel.execShellCommand("setprop debug.input.dispatch_policy 0") }
        coVerify { mockViewModel.execShellCommand("setprop windowsmgr.max_events_per_sec 60") }

        // Verify bloatware re-enabled
        coVerify { mockViewModel.execShellCommand("pm enable com.samsung.android.game.gos") }
        coVerify { mockViewModel.execShellCommand("pm enable com.xiaomi.joyose") }
    }

    @Test
    fun testOptimizeProcesses_WithValidPID() = runTest {
        // Setup mock to return a valid PID
        coEvery { mockViewModel.execShellCommand("pidof test.package") } returns "1234"

        performanceManager.applyDeepOptimizations("test.package", "test.package2")

        // Advance time to allow the optimization attempts to run
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify process optimization commands were called for valid PID
        coVerify { mockViewModel.execShellCommand("renice -20 1234") }
        coVerify { mockViewModel.execShellCommand("taskset -p f0 1234") }
    }

    @Test
    fun testOptimizeProcesses_WithInvalidPID() = runTest {
        // Setup mock to return invalid PID
        coEvery { mockViewModel.execShellCommand("pidof test.package") } returns "invalid"

        performanceManager.applyDeepOptimizations("test.package", "test.package2")

        // Advance time to allow the optimization attempts to run
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify process optimization commands were NOT called for invalid PID
        coVerify(exactly = 0) { mockViewModel.execShellCommand("renice -20 invalid") }
        coVerify(exactly = 0) { mockViewModel.execShellCommand("taskset -p f0 invalid") }
    }

    @Test
    fun testOptimizeProcesses_WithEmptyPID() = runTest {
        // Setup mock to return empty PID
        coEvery { mockViewModel.execShellCommand("pidof test.package") } returns ""

        performanceManager.applyDeepOptimizations("test.package", "test.package2")

        // Advance time to allow the optimization attempts to run
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify process optimization commands were NOT called for empty PID
        coVerify(exactly = 0) { mockViewModel.execShellCommand("renice -20 ") }
        coVerify(exactly = 0) { mockViewModel.execShellCommand("taskset -p f0 ") }
    }
}