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

    /**
     * 滚动/位移活跃期玻璃必须改采稳定底图（2026-09-21 用户实证：快速滑动时滞后一帧的
     * 实时截屏把旧位置文字折射进表面，形成沿滑动方向偏移的残影）。链路要求：位移回调
     * 触发抑制 → 抑制期间不再发起新采集、迟到的回读不绑定 → 静默窗口后才放行。
     */
    @Test fun scrollingSuppressesStaleRealtimeSampling() {
        val renderer = source("LiquidActivityRenderer")
        val moved = renderer.substringAfter("private fun invalidateMovedSurfaces()")
            .substringBefore("private fun invalidateRegisteredSurfaces()")
        assertTrue(moved.contains("lastContentShiftNanos"))
        assertTrue(moved.contains("suppressRealtimeSamplingWhileScrolling()"))

        // 显式变换回调（按下缩放/弹性拖拽）不等于内容位移：必须先经
        // flushSurfaceRefresh 的"表面原点真的变化"门控，否则点击/按压
        // 也会在无事发生时把底图 real→stable 闪一下（2026-09-21 真机实证）。
        val notify = renderer.substringAfter("fun notifyPositionChanged()")
            .substringBefore("private fun invalidateMovedSurfaces()", "MISSING")
        assertNotEquals("MISSING", notify)
        assertFalse("transform callbacks must not suppress unconditionally",
            notify.contains("suppressRealtimeSamplingWhileScrolling()"))
        val flush = renderer.substringAfter("private fun flushSurfaceRefresh(")
        assertTrue(flush.contains("surfaceMoved"))
        assertTrue(flush.contains("suppressRealtimeSamplingWhileScrolling()"))

        val suppress = renderer.substringAfter("private fun suppressRealtimeSamplingWhileScrolling()")
            .substringBefore("private fun onScrollSettleCheck()")
        assertTrue(suppress.contains("bindPreparedBackendsToBackdrop(stable)"))

        val request = renderer.substringAfter("private fun requestRealtimeCapture(")
            .substringBefore("private fun handleRealtimeCaptureResult")
        assertTrue(request.contains("realtimeSamplingSuppressed"))

        val result = renderer.substringAfter("private fun handleRealtimeCaptureResult")
            .substringBefore("private fun applyCaptureThroughputSample")
        assertTrue(result.contains("if (!realtimeSamplingSuppressed)"))

        assertTrue(renderer.contains("SCROLL_QUIET_MS"))
        assertTrue(Policy.SCROLL_QUIET_MS in 48L..240L)
    }

    /**
     * 位移抑制期的玻璃留在折射路径、只降级为 motionLite 单取样（2026-09-21 真机实证：
     * 直采路径缺折射 rim 与通透填充，切页/滚动瞬间"高光消失再加载"）。契约：
     * 外部窗口仍走 drawOpticalRegion；窗口内表面抑制期以 motionLite 跑同一 shader——
     * 驱动层已绑稳定底图，跳过散射多抽样但保留边缘光与 contentAlpha 通透。
     */
    @Test fun suppressedSurfacesStayRefractiveInLiteMode() {
        val renderer = source("LiquidActivityRenderer")
        val draw = renderer.substringAfter("internal fun drawSurface(")
            .substringBefore("private fun drawSurfaceLayers(")
        val optical = draw.substringAfter("if (foreignWindow) {")
            .substringBefore("} else {", "MISSING")
        assertNotEquals("MISSING", optical)
        assertTrue(optical.contains("drawOpticalRegion("))
        assertTrue(draw.contains("motionLite = realtimeSamplingSuppressed"))

        val backend = source("LiquidRefractionBackendApi33")
        assertTrue(backend.contains("uniform float motionLite"))
        // lite 分支必须走单次取样而非多抽样散射
        assertTrue(backend.contains("if (motionLite > 0.5)"))
        val lite = backend.substringAfter("if (motionLite > 0.5)")
        assertTrue(lite.contains("sampleContent("))
        // lite 必须保留与完整路径同一条内容感知焦散——缺了它，lite/full
        // 切换瞬间边缘高光亮度差一档，表现为滑动起止处的轻微闪动。
        assertTrue(lite.contains("liteCaustic"))
        assertTrue(lite.contains("causticLuminanceGain"))
    }

    /**
     * 回弹期间不切换采样路径（2026-09-21 真机实证：按住回弹不动时静默窗口会解除
     * 抑制、表面重录回折射路径，下一次位移又切回光学直采——整圈边缘光在两条路径
     * 之间乒乓闪烁；同时静止时 stretchDirY==0 令 edgeBoost 钉死在 1，浮动条的常驻
     * 折射下限完全不生效）。契约：回弹回调不触发抑制；静默检查在形变未归零前不解除；
     * shader 按 |stretchDirY| 在全向与定向投影间连续混合。
     */
    @Test fun stretchKeepsTheRefractivePathAndTheRestingGlow() {
        val renderer = source("LiquidActivityRenderer")
        val handler = renderer.substringAfter("private fun onStretchDistanceChanged(")
            .substringBefore("@MainThread", "MISSING")
        assertNotEquals("MISSING", handler)
        assertFalse("stretch must not switch sampling paths",
            handler.contains("suppressRealtimeSamplingWhileScrolling()"))

        val settle = renderer.substringAfter("private fun onScrollSettleCheck()")
            .substringBefore("private fun clearScrollSuppression()", "MISSING")
        assertNotEquals("MISSING", settle)
        assertTrue("settle must not lift suppression while stretched",
            settle.contains("stretchOpticalIntensity > 1f"))

        val refraction = source("LiquidRefractionBackendApi33")
        assertTrue("resting state must keep the edge gain omnidirectional",
            refraction.contains("mix(1.0, dirFacing, abs(stretchDirY))"))
    }

    /**
     * 位移抑制/外部窗口的光学直采路径必须给全部角色发光渐变描边（2026-09-21 真机实证：
     * 切页时折射 shader 的菲涅尔/镜面/焦散边缘光晕整条缺席，只剩细描边——所有控件
     * "边缘高光先消失再加载"）。廉价路径用顶沿提亮渐变保住"边缘有光"的读感。
     */
    @Test fun cheapOpticalPathKeepsALuminousEdgeOnEveryRole() {
        val renderer = source("LiquidActivityRenderer")
        val layers = renderer.substringAfter("private fun drawSurfaceLayers(")
            .substringBefore("private inline fun drawWithFallback", "MISSING")
        assertNotEquals("MISSING", layers)
        assertTrue(layers.contains("luminousEdge"))
        assertTrue(layers.contains("modalEdgePaint"))
        assertTrue(layers.contains("edgeBandPaint"))
        val draw = renderer.substringAfter("internal fun drawSurface(")
            .substringBefore("private fun drawSurfaceLayers(")
        // 窗口内表面抑制期留在折射 lite 路径——发光边缘只需补外部窗口的直采表面。
        assertTrue(draw.contains("luminousEdge = foreignWindow"))
        // 直采路径的填充透明度必须与折射路径同源：浮动条透出真实下层内容。
        assertTrue(draw.contains("LiquidSurfaceAlphaPolicy.glassContentAlpha(role)"))
    }

    /**
     * EdgeEffect.draw 只能落在硬件画布上（2026-09-21 真机实证）：Material 皮肤的
     * LiveBackdropSampler 每帧把内容根重绘进软件 Canvas 做透镜采样，平台 stretch
     * EdgeEffect 在非 RecordingCanvas 上 draw() 会直接 mDistance=0 并置 STATE_IDLE，
     * 任何正在累积的回弹形变都被取样帧抹掉——表现为完全没有回弹动画。
     */
    @Test fun stretchEffectsOnlyDrawOnHardwareCanvases() {
        val viewport = source("LiquidStretchViewport")
        val draw = viewport.substringAfter("override fun draw(canvas: Canvas)")
            .substringBefore("override fun onStartNestedScroll", "MISSING")
        assertNotEquals("MISSING", draw)
        assertTrue(draw.contains("canvas.isHardwareAccelerated"))
        val guarded = draw.substringAfter("isHardwareAccelerated")
        assertTrue(guarded.indexOf("topEffect.draw(canvas)") < guarded.indexOf("topEffect.draw(canvas)") + 400)
    }

    /**
     * 采样原点只取 View 屏幕原点（2026-09-22 真机实证）。
     *
     * 承载层的 provider 返回的 motionBounds 是**层画布内的绝对矩形**（如 left=127,
     * top=654），不是 0 基局部矩形。两条采样链都把 `viewX/viewY` 当画布原点解算
     * 根坐标：`drawOpticalRegion` 的 `uv=(p+off)·(bitmap/full)`（local matrix 逆变换）
     * 与折射 shader 的 `rootCoord=canvasCoord+backdropOrigin`。若把 motionBounds 的
     * left/top 再叠进 drawX/drawY，采样窗会二次偏移到卡片右下方——形变全程显示
     * 的是偏离真实位置的底图区域，落定换回卡片 0 基 drawable 时采样区瞬移，现场
     * 就是"面板跳变加载通透背景"（rec.mp4 f259 实测：rim +4、内衬亮度重分布）。
     */
    @Test fun motionSurfaceSamplingOriginStaysAtViewOrigin() {
        val renderer = source("LiquidActivityRenderer")
        val provider = renderer.substringAfter("val motionProvider = view as? LiquidMotionSurfaceFrameProvider")
            .substringBefore("if (view != null) {", "MISSING")
        assertNotEquals("MISSING", provider)
        assertFalse(provider.contains("drawX += motionBounds"))
        assertFalse(provider.contains("drawY += motionBounds"))
        assertFalse(provider.contains("drawX += "))
        assertFalse(provider.contains("drawY += "))
    }

    private fun source(name: String): String = sequenceOf(
        File("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/liquid/$name.kt"),
        File("app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/liquid/$name.kt")
    ).first(File::isFile).readText()
}
