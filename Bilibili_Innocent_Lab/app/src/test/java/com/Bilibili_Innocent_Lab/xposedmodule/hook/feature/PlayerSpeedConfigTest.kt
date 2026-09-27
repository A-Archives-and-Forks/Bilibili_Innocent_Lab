package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class PlayerSpeedConfigTest {
    @Test fun `range is bounded with follow host sentinel`() {
        assertNull(PlayerSpeedConfig.multiplier(0))
        assertEquals(0.1f, PlayerSpeedConfig.multiplier(10))
        assertEquals(0.25f, PlayerSpeedConfig.multiplier(25))
        assertEquals(4f, PlayerSpeedConfig.multiplier(400))
        assertEquals(8f, PlayerSpeedConfig.multiplier(800))
        listOf(-1, 1, 9, 801, Int.MAX_VALUE).forEach {
            assertEquals(0, PlayerSpeedConfig.normalize(it))
            assertNull(PlayerSpeedConfig.multiplier(it))
        }
    }
    @Test fun `decimal parsing never rounds invalid input into range`() {
        listOf("NaN", "Infinity", "", " ", "1.001", "0.099", "0.09", "8.001", "8.01", "-1", "1,25").forEach {
            assertNull(it, PlayerSpeedConfig.parseMultiplier(it))
        }
        assertEquals(125, PlayerSpeedConfig.parseMultiplier(" 1.25 "))
        assertEquals(10, PlayerSpeedConfig.parseMultiplier("0.1"))
        assertEquals(10, PlayerSpeedConfig.parseMultiplier(".10"))
        assertEquals(25, PlayerSpeedConfig.parseMultiplier("0.25"))
        assertEquals(400, PlayerSpeedConfig.parseMultiplier("4.00"))
        assertEquals(475, PlayerSpeedConfig.parseMultiplier("4.75"))
        assertEquals(800, PlayerSpeedConfig.parseMultiplier("8"))
        assertEquals("0.1", PlayerSpeedConfig.formatMultiplier(10))
        assertEquals("8", PlayerSpeedConfig.formatMultiplier(800))
    }
    @Test fun `all supported values round trip without floating point drift`() {
        PlayerSpeedConfig.supportedPercents.forEach {
            assertEquals(it, PlayerSpeedConfig.parseMultiplier(PlayerSpeedConfig.formatMultiplier(it)))
        }
    }
    @Test fun `disable wins without changing saved custom speed`() {
        assertEquals(0, PlayerSpeedConfig.effectiveLongPressPercent(true, 275))
        assertEquals(275, PlayerSpeedConfig.effectiveLongPressPercent(false, 275))
    }
}
