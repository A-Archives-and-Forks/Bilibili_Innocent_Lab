package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidRealtimeCapturePolicy as Policy
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LiquidFeedbackIsolationTest {
    @Test fun ownedOutputHasZeroRecursiveContributionAndUnmaskedPixelsStayLive() {
        val alpha = Policy.BASE_SUPPRESSION_ALPHA
        // The cached stable backdrop is opaque. Test every possible captured channel and several backgrounds.
        for (clean in listOf(0, 19, 128, 240, 255)) for (captured in 0..255) {
            val sanitized = (clean * alpha + captured * (255 - alpha)) / 255
            assertEquals("Own labels must not survive input sanitation", clean, sanitized)
            val outsideMask = (clean * 0 + captured * (255 - 0)) / 255
            assertEquals("No mask must preserve the actual live capture", captured, outsideMask)
        }
        assertEquals(3, Policy.BUFFER_COUNT)
        assertEquals(1_000_000L, Policy.TARGET_SAMPLE_PIXELS)
        assertEquals(120f, Policy.MAX_TARGET_FRAMES_PER_SECOND, 0f)
    }

    @Test fun sourceWiringFreezesMasksBeforeCaptureAndSanitationReplacesOwnedOutput() {
        val renderer = source("LiquidActivityRenderer")
        val request = renderer.substringAfter("private fun requestRealtimeCapture(").substringBefore("private fun handleRealtimeCaptureResult")
        assertTrue(request.indexOf("buildSuppressionMask") < request.indexOf("PixelCopy.request"))
        val result = renderer.substringAfter("private fun handleRealtimeCaptureResult").substringBefore("private fun applyCaptureThroughputSample")
        assertTrue(result.indexOf("sanitizeRealtimeCapture") < result.indexOf("bindPreparedBackendsToBackdrop"))
        val mask = renderer.substringAfter("private fun buildSuppressionMask(").substringBefore("private fun sanitizeRealtimeCapture")
        assertTrue(mask.contains("realtimeCaptureMask.addRoundRect"))
        // 未绘制边界环的 viewport 不进入抑制遮罩：遮罩只覆盖真实玻璃表面。
        assertFalse(mask.contains("stretchViewports"))
    }

    /**
     * 超出回弹不再绘制边界采样环（2026-09-20 用户要求去掉红圈中的那一圈模糊描边）。
     *
     * 这条钉住"移除"本身：环的绘制、记账与 retired 机制都不许静默复活；而系统 stretch 与
     * 回弹光学强度提升是不同机制，必须保留。
     */
    @Test fun overscrollKeepsTheSystemStretchButPaintsNoBoundaryRing() {
        val renderer = source("LiquidActivityRenderer")
        listOf("drawStretchBoundary", "stretchBoundaryFootprints", "stretchViewports",
            "LiquidStretchBoundaryFootprint", "LiquidBoundaryCaptureState", "LiquidBoundaryMaskGeometry",
            "STRETCH_EDGE").forEach {
            assertFalse("renderer must not resurrect the boundary ring: $it", renderer.contains(it))
        }
        assertTrue(renderer.contains("onStretchDistanceChanged"))
        val viewport = source("LiquidStretchViewport")
        assertFalse("viewport must not draw any boundary ring", viewport.contains("drawBoundary"))
        assertTrue(viewport.contains("onStretchDistance("))
        assertTrue(viewport.contains("LiquidStretchOverscrollPolicy.dominantEdge"))
        assertTrue(viewport.contains("bottomEffect.draw(this)"))
        val policy = source("LiquidRealtimeCapturePolicy")
        listOf("stretchBoundaryVisibility", "stretchBoundaryBandPx", "stretchPaintedBandPx",
            "stretchFeedbackBandDp").forEach {
            assertFalse("policy must not resurrect the boundary ring: $it", policy.contains(it))
        }
        assertTrue(policy.contains("fun stretchOpticalIntensity"))
    }

    /**
     * 回弹光学增益必须沿方向投射到表面边缘（2026-09-21 用户实证：四边等亮的高光描边
     * 违反方向直觉）。方向从 viewport 的主导边出发，经 renderer 的 stretchDirY 进
     * shader，再按边缘外法线点积无极分配——对侧边缘保持基准强度。
     */
    @Test fun stretchOpticsFollowTheActiveEdgeDirection() {
        val renderer = source("LiquidActivityRenderer")
        val handler = renderer.substringAfter("private fun onStretchDistanceChanged(")
            .substringBefore("@MainThread", "MISSING")
        assertTrue(handler.contains("LiquidStretchEdge.TOP"))
        assertTrue(handler.contains("LiquidStretchEdge.BOTTOM"))
        assertTrue(renderer.contains("stretchEdgeDirY"))
        val driver = source("LiquidBackendDriver")
        assertTrue(driver.contains("stretchDirY"))
        val refraction = source("LiquidRefractionBackendApi33")
        assertTrue(refraction.contains("stretchDirY"))
        // shader 内必须有"法线投影 → 方向性增益"两步，缺一则退回四边等亮。
        assertTrue(refraction.contains("stretchFacing"))
        assertTrue(refraction.contains("edgeBoost"))
        assertTrue(refraction.contains("dot("))
    }

    private fun source(name: String): String = sequenceOf(
        File("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/liquid/$name.kt"),
        File("app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/liquid/$name.kt")
    ).first(File::isFile).readText()
}
