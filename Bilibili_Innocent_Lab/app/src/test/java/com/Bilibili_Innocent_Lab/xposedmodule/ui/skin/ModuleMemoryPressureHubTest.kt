package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.ModuleMemoryPressureHub
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.ModuleMemoryPressureListener
import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleMemoryPressureHubTest {

    @Test
    fun `release walks current listeners and unregister stops further calls`() {
        val counts = mutableListOf(0, 0)
        val first = ModuleMemoryPressureListener { counts[0] += 1 }
        val second = ModuleMemoryPressureListener { counts[1] += 1 }
        ModuleMemoryPressureHub.addListener(first)
        ModuleMemoryPressureHub.addListener(second)
        try {
            ModuleMemoryPressureHub.releaseGraphics()
            assertEquals(listOf(1, 1), counts)
            ModuleMemoryPressureHub.removeListener(second)
            ModuleMemoryPressureHub.releaseGraphics()
            assertEquals(listOf(2, 1), counts)
            ModuleMemoryPressureHub.persistForImminentKill()
            assertEquals(listOf(2, 1), counts)
        } finally {
            ModuleMemoryPressureHub.removeListener(first)
            ModuleMemoryPressureHub.removeListener(second)
        }
    }
}
