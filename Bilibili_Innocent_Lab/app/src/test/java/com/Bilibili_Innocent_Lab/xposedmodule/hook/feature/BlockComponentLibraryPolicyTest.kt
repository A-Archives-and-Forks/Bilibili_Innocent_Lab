package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockComponentLibraryPolicyTest {
    @Test
    fun `pool keyword match is exact and empty names fail open`() {
        assertTrue(ComponentLibraryPoolMatcher.matches("Base Component Library", emptyList(), setOf("base component library")))
        assertTrue(ComponentLibraryPoolMatcher.matches(null, listOf("基础组件库"), setOf("基础组件库")))
        assertFalse(ComponentLibraryPoolMatcher.matches(null, emptyList()))
        assertFalse(ComponentLibraryPoolMatcher.matches("ordinary resource", listOf("video")))
        assertFalse(ComponentLibraryPoolMatcher.matches("Base Component Library Extra", emptyList(), setOf("base component library")))
    }

    @Test
    fun `custom keyword set does not silently use the default set`() {
        assertTrue(ComponentLibraryPoolMatcher.matches("safe-pool", emptyList(), setOf("safe-pool")))
        assertFalse(ComponentLibraryPoolMatcher.matches("基础组件库", emptyList(), setOf("other")))
    }
}
