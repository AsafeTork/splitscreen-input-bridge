package com.example.splitscreenmanager.manager

import com.example.splitscreenmanager.viewmodel.AppViewModel
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import android.view.KeyEvent
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SplitScreenControllerTest {

    @MockK
    private lateinit var mockViewModel: AppViewModel

    private lateinit var controller: SplitScreenController

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        every { mockViewModel.injectInputEvent(any()) } returns Unit
        controller = SplitScreenController(mockViewModel)
    }

    @Test
    fun testSetScreenDimensions_Valid() {
        controller.setScreenDimensions(1920, 1080)
        assertEquals(1920, controller.getScreenWidth())
        assertEquals(1080, controller.getScreenHeight())
        assertEquals(false, controller.isActive())
    }

    @Test
    fun testSetScreenDimensions_Invalid() {
        assertFailsWith<IllegalArgumentException> {
            controller.setScreenDimensions(-1, 1080)
        }

        assertFailsWith<IllegalArgumentException> {
            controller.setScreenDimensions(1920, 0)
        }
    }

    @Test
    fun testActivateDeactivate() {
        assertEquals(false, controller.isActive())

        controller.activate()
        assertEquals(true, controller.isActive())

        controller.deactivate()
        assertEquals(false, controller.isActive())
    }

    @Test
    fun testForwardPlayer2Key_WhenInactive() {
        controller.forwardPlayer2Key(KeyEvent.KEYCODE_A)
        verify(exactly = 0) { mockViewModel.injectInputEvent(any()) }
    }

    @Test
    fun testForwardPlayer2Key_WhenActive() {
        controller.activate()
        controller.forwardPlayer2Key(KeyEvent.KEYCODE_A)

        verify(exactly = 2) { mockViewModel.injectInputEvent(any()) }
    }

    @Test
    fun testForwardPlayer2Key_InvalidKeyCode() {
        controller.activate()
        controller.forwardPlayer2Key(-1)
        controller.forwardPlayer2Key(KeyEvent.KEYCODE_MAX + 1)

        verify(exactly = 0) { mockViewModel.injectInputEvent(any()) }
    }

    @Test
    fun testRemapYForPlayer2_WhenInactive() {
        controller.setScreenDimensions(1080, 1920)
        val originalY = 100f
        val result = controller.remapYForPlayer2(originalY)
        assertEquals(originalY, result)
    }

    @Test
    fun testRemapYForPlayer2_WhenActive() {
        controller.setScreenDimensions(1080, 1920)
        controller.activate()

        val originalY = 100f
        val expectedY = 100f + (1920 / 2f)
        val result = controller.remapYForPlayer2(originalY)

        assertEquals(expectedY, result)
    }

    @Test
    fun testGetSplitLineY() {
        controller.setScreenDimensions(1080, 1920)
        val splitLine = controller.getSplitLineY()
        assertEquals(960f, splitLine)
    }

    @Test
    fun testIsInPlayer2Zone() {
        controller.setScreenDimensions(1080, 1920)

        // Top half should return false
        assertEquals(false, controller.isInPlayer2Zone(400f))

        // Bottom half should return true
        assertEquals(true, controller.isInPlayer2Zone(1000f))

        // Exactly on the line should return false
        assertEquals(false, controller.isInPlayer2Zone(960f))
    }

    @Test
    fun testGetScreenDimensions() {
        controller.setScreenDimensions(1920, 1080)
        assertEquals(1920, controller.getScreenWidth())
        assertEquals(1080, controller.getScreenHeight())
    }

    @Test
    fun testDefaultDimensions() {
        // Controller should have default dimensions
        assertEquals(1080, controller.getScreenWidth())
        assertEquals(2400, controller.getScreenHeight())
    }
}
