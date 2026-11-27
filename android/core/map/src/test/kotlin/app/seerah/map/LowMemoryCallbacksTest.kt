package app.seerah.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LowMemoryCallbacksTest {
    @Suppress("DEPRECATION")
    @Test
    fun shouldTrimMapMemory_coversForegroundLowAndBackgroundLevels() {
        assertFalse(shouldTrimMapMemory(0))
        assertFalse(shouldTrimMapMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))

        assertTrue(shouldTrimMapMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(shouldTrimMapMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(shouldTrimMapMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertTrue(shouldTrimMapMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertTrue(shouldTrimMapMemory(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertTrue(shouldTrimMapMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }

    @Suppress("DEPRECATION")
    @Test
    fun onLowMemory_triggersTrimCallbackOnce() {
        var calls = 0
        val callbacks = LowMemoryCallbacks { calls++ }

        callbacks.onLowMemory()

        assertEquals(1, calls)
    }

    @Suppress("DEPRECATION")
    @Test
    fun onTrimMemory_skipsMildForegroundPressure_andTrimsAboveThreshold() {
        var calls = 0
        val callbacks = LowMemoryCallbacks { calls++ }

        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals(0, calls)

        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        callbacks.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)

        assertEquals(3, calls)
    }

    @Test
    fun onConfigurationChanged_doesNotTriggerTrim() {
        var calls = 0
        val callbacks = LowMemoryCallbacks { calls++ }

        callbacks.onConfigurationChanged(Configuration())

        assertEquals(0, calls)
    }
}
