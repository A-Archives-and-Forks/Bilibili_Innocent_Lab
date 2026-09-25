package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiDeclaredVideoRegistryTest {

    @Before @After fun reset() = AiDeclaredVideoRegistry.resetForTest()

    @Test fun addIsIdempotentAndIgnoresNonPositiveAids() {
        assertTrue(AiDeclaredVideoRegistry.add(10))
        assertFalse(AiDeclaredVideoRegistry.add(10))
        assertFalse(AiDeclaredVideoRegistry.add(0))
        assertFalse(AiDeclaredVideoRegistry.add(-1))
        assertFalse(AiDeclaredVideoRegistry.add(null))
        assertTrue(AiDeclaredVideoRegistry.contains(10))
        assertFalse(AiDeclaredVideoRegistry.contains(11))
        assertFalse(AiDeclaredVideoRegistry.contains(null))
    }

    @Test fun boundedKeepsTheNewestEntries() {
        val values = (1L..(AiDeclaredVideoRegistry.MAX_AIDS + 5L)).toList()
        val bounded = AiDeclaredVideoRegistry.bounded(values)
        assertEquals(AiDeclaredVideoRegistry.MAX_AIDS, bounded.size)
        assertFalse(1L in bounded)
        assertTrue(values.last() in bounded)
    }

    @Test fun decodeDropsOnlyTheBrokenLines() {
        assertEquals(listOf(1L, 3L), AiDeclaredVideoRegistry.decode("1\nabc\n-2\n0\n 3 \n"))
        assertEquals(emptyList<Long>(), AiDeclaredVideoRegistry.decode(null))
        val encoded = AiDeclaredVideoRegistry.encode(listOf(5L, 6L))
        assertEquals(listOf(5L, 6L), AiDeclaredVideoRegistry.decode(encoded))
    }

    @Test fun redirectGuardAllowsABoundedChainPerWindow() {
        var now = 0L
        val guard = AiRedirectGuard(maxRedirects = 3, windowMillis = 30_000L, clock = { now })
        assertTrue(guard.tryAcquire())
        assertTrue(guard.tryAcquire())
        assertTrue(guard.tryAcquire())
        // 第四次落在窗口内：连锁保险生效，改走提示页。
        assertFalse(guard.tryAcquire())
        now = 30_001L
        assertTrue(guard.tryAcquire())
    }
}
