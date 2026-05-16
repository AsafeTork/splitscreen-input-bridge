package com.example.splitscreenmanager.viewmodel

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.test.core.app.ApplicationProvider
import com.example.splitscreenmanager.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.mockito.Mockito
import org.mockito.kotlin.whenever

class AppViewModelTest {

    @get:Rule
    val rule: TestRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testToggleSelection() {
        val viewModel = AppViewModel()
        val mockApp = Mockito.mock(AppInfo::class.java)
        whenever(mockApp.packageName).thenReturn("com.test.app")
        whenever(mockApp.name).thenReturn("Test App")

        // Test initial state
        assert(viewModel.selectedApps.isEmpty())

        // Add first app
        viewModel.toggleSelection(mockApp)
        assert(viewModel.selectedApps.size == 1)
        assert(viewModel.selectedApps[0] == mockApp)

        // Add second app
        val mockApp2 = Mockito.mock(AppInfo::class.java)
        whenever(mockApp2.packageName).thenReturn("com.test.app2")
        whenever(mockApp2.name).thenReturn("Test App 2")
        viewModel.toggleSelection(mockApp2)
        assert(viewModel.selectedApps.size == 2)

        // Try to add third app (should not be added)
        val mockApp3 = Mockito.mock(AppInfo::class.java)
        whenever(mockApp3.packageName).thenReturn("com.test.app3")
        whenever(mockApp3.name).thenReturn("Test App 3")
        viewModel.toggleSelection(mockApp3)
        assert(viewModel.selectedApps.size == 2)

        // Remove first app
        viewModel.toggleSelection(mockApp)
        assert(viewModel.selectedApps.size == 1)
        assert(viewModel.selectedApps[0] == mockApp2)
    }

    @Test
    fun testIsButtonEnabled() {
        val viewModel = AppViewModel()
        val mockApp = Mockito.mock(AppInfo::class.java)
        whenever(mockApp.packageName).thenReturn("com.test.app")

        // Test with 0 apps
        assert(!viewModel.isButtonEnabled())

        // Test with 1 app
        viewModel.toggleSelection(mockApp)
        assert(!viewModel.isButtonEnabled())

        // Test with 2 apps
        val mockApp2 = Mockito.mock(AppInfo::class.java)
        whenever(mockApp2.packageName).thenReturn("com.test.app2")
        viewModel.toggleSelection(mockApp2)
        assert(viewModel.isButtonEnabled())
    }

    @Test
    fun testSelectedAppsOrdered() {
        val viewModel = AppViewModel()
        val mockApp1 = Mockito.mock(AppInfo::class.java)
        whenever(mockApp1.packageName).thenReturn("com.test.app1")
        whenever(mockApp1.name).thenReturn("App 1")

        val mockApp2 = Mockito.mock(AppInfo::class.java)
        whenever(mockApp2.packageName).thenReturn("com.test.app2")
        whenever(mockApp2.name).thenReturn("App 2")

        // Add apps in order
        viewModel.toggleSelection(mockApp1)
        viewModel.toggleSelection(mockApp2)

        val orderedApps = viewModel.selectedAppsOrdered
        assert(orderedApps.size == 2)
        assert(orderedApps[0] == mockApp1) // First selected
        assert(orderedApps[1] == mockApp2) // Second selected
    }

    @Test
    fun testReportError() {
        val viewModel = AppViewModel()
        val initialLogs = viewModel.systemLogs.value.size

        viewModel.reportError("TestCommand", "Test error message")

        val updatedLogs = viewModel.systemLogs.value
        assert(updatedLogs.size == initialLogs + 1)
        assert(updatedLogs[0].command == "TestCommand")
        assert(updatedLogs[0].error == "Test error message")
    }
}