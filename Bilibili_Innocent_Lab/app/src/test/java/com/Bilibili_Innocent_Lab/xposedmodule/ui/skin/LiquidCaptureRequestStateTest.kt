package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidCaptureRequestState
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LiquidCaptureRequestStateTest {
    @Test fun oldSuccessAndFailureAfterStopResumeCannotPublishOrTouchFailureCounters() {
        for (success in listOf(true, false)) {
            val state = LiquidCaptureRequestState()
            val old = state.begin()!!
            state.invalidate() // stop, then resume before the old PixelCopy callback arrives
            assertNull("An in-flight bitmap cannot be reused", state.begin())
            var published = 0; var failures = 0; var throughputSamples = 0
            if (state.complete(old) == LiquidCaptureRequestState.Completion.CURRENT) {
                if (success) published++ else failures++
                throughputSamples++
            }
            assertEquals(0, published); assertEquals(0, failures); assertEquals(0, throughputSamples)
            val fresh = state.begin()!!
            assertEquals(LiquidCaptureRequestState.Completion.CURRENT, state.complete(fresh))
        }
    }

    @Test fun resizeReleaseAndCloseInvalidateButDoNotUnlockTheNativeWriteEarly() {
        val state = LiquidCaptureRequestState()
        val pending = state.begin()!!
        repeat(3) { state.invalidate(); assertNull(state.begin()) }
        assertEquals(LiquidCaptureRequestState.Completion.STALE, state.complete(pending))
        assertNotNull(state.begin())
    }

    @Test fun duplicateOldCallbackCannotClearANewerNativeRequest() {
        val state = LiquidCaptureRequestState()
        val first = state.begin()!!
        assertEquals(LiquidCaptureRequestState.Completion.CURRENT, state.complete(first))
        val next = state.begin()!!
        assertEquals(LiquidCaptureRequestState.Completion.FOREIGN, state.complete(first))
        assertNull(state.begin())
        assertEquals(LiquidCaptureRequestState.Completion.CURRENT, state.complete(next))
    }

    // 2026-09-20：回弹边界环移除后，请求不再携带边界 retirement 列表，
    // 断言从 MaskAndRetirements 收窄为 Mask。
    @Test fun productionCallbackOwnsItsSourceRootSizeAndMask() {
        val relative = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/liquid/LiquidActivityRenderer.kt"
        val source = sequenceOf(File(relative), File("app/$relative")).first(File::isFile).readText()
        val callback = source.substringAfter("private fun handleRealtimeCaptureResult(").substringBefore("private fun applyCaptureThroughputSample")
        assertTrue(callback.indexOf("realtimeCaptureInFlight !== request") < callback.indexOf("realtimeCaptureInFlight = null"))
        assertTrue(callback.indexOf("Completion.CURRENT") < callback.indexOf("sanitizeRealtimeCapture(request)"))
        assertTrue(callback.indexOf("Completion.CURRENT") < callback.indexOf("val workStartedNanos"))
        assertTrue(callback.contains("root.width != request.width || root.height != request.height"))
        assertTrue(source.contains("handleRealtimeCaptureResult(request, result)"))
        assertTrue(source.contains("realtimeCaptureCanvas.drawPath(request.mask, suppressionPaint)"))
        val close = source.substringAfter("override fun close()")
        assertTrue(close.contains("captureRequests.invalidate()"))
        assertFalse(close.contains("realtimeCaptureInFlight = null"))
    }
}
