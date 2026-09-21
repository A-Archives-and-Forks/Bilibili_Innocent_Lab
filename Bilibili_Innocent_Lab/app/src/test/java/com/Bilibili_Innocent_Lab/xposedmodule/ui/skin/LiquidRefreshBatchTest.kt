package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRefreshBatch
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRefreshVisibilityPolicy
import org.junit.Assert.*
import org.junit.Test

class LiquidRefreshBatchTest {
    @Test fun preDrawReadsFinalTransformsOnceAndMergesNewContentInTheSameWindow() {
        val batch = LiquidRefreshBatch()
        var position = 0
        var scans = 0
        position = 10; batch.mark(false)
        position = 35; batch.mark(false)
        batch.mark(true)
        position = 80; batch.mark(false)
        val flags = batch.take()
        if (flags != 0) scans++
        assertEquals(80, position)
        assertEquals(LiquidRefreshBatch.POSITION or LiquidRefreshBatch.CONTENT, flags)
        assertEquals(1, scans)
        repeat(120) { assertEquals("Idle pre-draw never starts more work", 0, batch.take()) }
    }

    @Test fun dialogAndActivityPendingRefreshesAreIndependent() {
        val activity = LiquidRefreshBatch(); val dialog = LiquidRefreshBatch()
        activity.mark(false); dialog.mark(true)
        assertEquals(LiquidRefreshBatch.CONTENT, dialog.take())
        assertEquals(LiquidRefreshBatch.POSITION, activity.take())
        dialog.mark(false)
        assertEquals(0, activity.take())
        assertEquals(LiquidRefreshBatch.POSITION, dialog.take())
    }

    @Test fun onlyFinitePureTranslationMayUseTheUnscaledScreenAabb() {
        fun matrix() = floatArrayOf(1f, 0f, 123.25f, 0f, 1f, -89.5f, 0f, 0f, 1f)
        assertTrue(LiquidRefreshVisibilityPolicy.isTranslationOnly(matrix()))
        for ((index, value) in listOf(0 to .98f, 1 to .2f, 3 to -.2f, 4 to 1.1f, 6 to .01f, 7 to .01f, 8 to .9f, 2 to Float.NaN)) {
            val transformed = matrix().also { it[index] = value }
            assertFalse(LiquidRefreshVisibilityPolicy.isTranslationOnly(transformed))
        }
        // Only window rejection is used: no assumption that an ancestor clips its children/padding.
        assertFalse(LiquidRefreshVisibilityPolicy.intersectsWindow(10f, 300f, 90f, 360f, 0f, 0f, 100f, 200f, 34f))
        assertTrue(LiquidRefreshVisibilityPolicy.intersectsWindow(10f, 210f, 90f, 230f, 0f, 0f, 100f, 200f, 34f))
    }
}
