package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触点越出轮廓后的"贴边堆积"性质测试（纯 JVM）。
 *
 * 验收本体：手指拖出控件时高光**不消失**，而是钉在最近的轮廓边上，越往外拖越亮、越扁、
 * 越铺开；跨越轮廓的瞬间没有任何可见跳变；取向模型与流动模型共享同一套几何。
 */
class AdaptiveGlowEdgePileTest {
    private val density = 3f
    private val radiusPx = 192f
    private val barWidth = 960f
    private val barHeight = 192f
    private val cornerPx = barHeight / 2f
    private val oriented = GlowConfig.create(density, 12f, 1.5f, 720f, 84f)
    private val flowing = GlowConfig.create(
        density = density, maxTravelPx = 12f, travelEpsPx = 0f,
        velocityRefPxPerSec = 720f, edgeBandPx = 0f, axialBoost = 0f, oriented = false
    )

    private fun settle(cfg: GlowConfig, x: Float, y: Float, frames: Int = 120): GlowShape {
        val state = GlowState()
        val holder = GlowFrame()
        repeat(frames) {
            holder.press = 1f
            holder.centerX = x
            holder.centerY = y
            holder.boundsWidth = barWidth
            holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
            state.update(holder, 1f / 120f, radiusPx, 72, cfg)
        }
        return state.shape
    }

    @Test fun glowSurvivesLeavingTheOutline() {
        for (cfg in listOf(oriented, flowing)) {
            val outside = settle(cfg, barWidth / 2f, -200f)
            assertTrue("越界后仍可见", outside.visible)
            assertTrue(outside.alphaByte > 0)
            assertTrue("堆积量应饱和", outside.pileUnit > 0.95f)
        }
    }

    @Test fun pileGrowsMonotonicallyWithOvershoot() {
        for (cfg in listOf(oriented, flowing)) {
            var lastAlpha = -1f
            var lastPile = -1f
            var lastRatio = Float.MAX_VALUE
            for (step in 0..40) {
                val overshoot = cfg.pileRefPx * 1.5f * step / 40f
                val shape = settle(cfg, barWidth / 2f, -overshoot)
                assertTrue("alpha 随越界量单调不减 @$overshoot", shape.alphaUnit >= lastAlpha - 1e-4f)
                assertTrue("堆积量随越界量单调不减 @$overshoot", shape.pileUnit >= lastPile - 1e-4f)
                val ratio = shape.radiusX / shape.radiusY
                assertTrue("越往外越扁（法向/切向半径比单调不增）@$overshoot", ratio <= lastRatio + 1e-4f)
                lastAlpha = shape.alphaUnit
                lastPile = shape.pileUnit
                lastRatio = ratio
            }
            val edge = settle(cfg, barWidth / 2f, 0f)
            val far = settle(cfg, barWidth / 2f, -cfg.pileRefPx * 2f)
            assertTrue("远离边缘必须明显比贴边亮", far.alphaUnit > edge.alphaUnit * 1.5f)
            assertEquals(0f, edge.pileUnit, 1e-3f)
            assertEquals(1f, far.pileUnit, 1e-3f)
        }
    }

    @Test fun pilePinsToTheNearestEdgeAndAlignsToItsNormal() {
        for (cfg in listOf(oriented, flowing)) {
            val above = settle(cfg, barWidth / 2f, -300f)
            assertEquals("钉在上边", 0f, above.centerY, 2f)
            assertEquals(barWidth / 2f, above.centerX, 2f)
            assertEquals("主轴转到轮廓法向（竖直）", 0f, abs(shortestAxisDeltaDeg(above.rotationDeg, 90f)), 2f)
            assertTrue("沿法向压扁、沿切向铺开", above.radiusX < above.radiusY)

            val below = settle(cfg, barWidth / 2f, barHeight + 300f)
            assertEquals("钉在下边", barHeight, below.centerY, 2f)

            val right = settle(cfg, barWidth + 300f, barHeight / 2f)
            assertEquals("钉在右端", barWidth, right.centerX, 2f)
            assertEquals(barHeight / 2f, right.centerY, 2f)
            assertEquals("法向水平", 0f, abs(shortestAxisDeltaDeg(right.rotationDeg, 0f)), 2f)

            // 斜向拖出全圆角端头：钉在圆弧上而不是矩形角
            val corner = settle(cfg, barWidth + 300f, -300f)
            val dx = corner.centerX - (barWidth - cornerPx)
            val dy = corner.centerY - cornerPx
            assertEquals("钉在端头圆弧上", cornerPx, kotlin.math.sqrt(dx * dx + dy * dy), 3f)
        }
    }

    @Test fun crossingTheOutlineIsContinuousFrameToFrame() {
        for (cfg in listOf(oriented, flowing)) {
            val state = GlowState()
            val holder = GlowFrame()
            holder.boundsWidth = barWidth
            holder.boundsHeight = barHeight
            holder.cornerRadius = cornerPx
            var lastAlpha = -1
            var lastX = Float.NaN
            var lastY = Float.NaN
            var lastRx = Float.NaN
            var lastRy = Float.NaN
            var lastRot = Float.NaN
            // 从居中以 2px/帧（240px/s）匀速向上拖到轮廓外 300px，再原路拖回。
            val path = (0..200).map { barHeight / 2f - it * 2f } + (200 downTo 0).map { barHeight / 2f - it * 2f }
            for ((i, y) in path.withIndex()) {
                holder.press = 1f
                holder.velocityY = if (i <= 200) -240f else 240f
                holder.centerX = barWidth / 2f
                holder.centerY = y
                state.update(holder, 1f / 120f, radiusPx, 72, cfg)
                val s = state.shape
                assertTrue("第 $i 帧必须可见", s.visible)
                if (lastAlpha >= 0) {
                    assertTrue("第 $i 帧 alpha 跳变 ${abs(s.alphaByte - lastAlpha)}", abs(s.alphaByte - lastAlpha) <= 12)
                    assertTrue("第 $i 帧光心跳变", abs(s.centerX - lastX) <= 8f && abs(s.centerY - lastY) <= 8f)
                    assertTrue("第 $i 帧半径跳变", abs(s.radiusX - lastRx) / radiusPx <= 0.03f && abs(s.radiusY - lastRy) / radiusPx <= 0.03f)
                    assertTrue("第 $i 帧角度跳变 ${abs(shortestAxisDeltaDeg(lastRot, s.rotationDeg))}",
                        abs(shortestAxisDeltaDeg(lastRot, s.rotationDeg)) <= 14f)
                }
                lastAlpha = s.alphaByte
                lastX = s.centerX; lastY = s.centerY
                lastRx = s.radiusX; lastRy = s.radiusY
                lastRot = s.rotationDeg
            }
            assertTrue("拖回后堆积量归零", state.shape.pileUnit < 0.02f)
        }
    }

    @Test fun releaseOutsideStillFadesOut() {
        val state = GlowState()
        val holder = GlowFrame()
        holder.boundsWidth = barWidth; holder.boundsHeight = barHeight; holder.cornerRadius = cornerPx
        holder.centerX = barWidth / 2f; holder.centerY = -200f
        repeat(60) { holder.press = 1f; state.update(holder, 1f / 120f, radiusPx, 72, oriented) }
        assertTrue(state.shape.visible)
        repeat(60) { holder.press = 0f; state.update(holder, 1f / 120f, radiusPx, 72, oriented) }
        assertTrue("松手后即便在轮廓外也要熄灭", !state.shape.visible)
    }

    @Test fun extremeOvershootStaysFiniteAndBounded() {
        val wild = floatArrayOf(-1e9f, 1e9f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f)
        for (cfg in listOf(oriented, flowing)) for (x in wild) for (y in wild) {
            val shape = settle(cfg, x, y, frames = 8)
            for (v in floatArrayOf(shape.centerX, shape.centerY, shape.radiusX, shape.radiusY, shape.rotationDeg, shape.coreOffsetX, shape.alphaUnit, shape.pileUnit)) {
                assertTrue("有限 @($x,$y)", v.isFinite())
            }
            assertTrue(shape.centerX in 0f..barWidth && shape.centerY in 0f..barHeight)
            assertTrue(shape.pileUnit in 0f..1f)
            assertTrue(shape.alphaByte in 0..255)
        }
    }

    @Test fun outwardNormalMatchesHandComputedDirections() {
        val out = FloatArray(2)
        roundedRectOutwardNormal(barWidth / 2f, -50f, barWidth, barHeight, cornerPx, out)
        assertEquals(0f, out[0], 1e-4f); assertEquals(-1f, out[1], 1e-4f)
        roundedRectOutwardNormal(barWidth / 2f, barHeight + 50f, barWidth, barHeight, cornerPx, out)
        assertEquals(0f, out[0], 1e-4f); assertEquals(1f, out[1], 1e-4f)
        roundedRectOutwardNormal(barWidth + 50f, barHeight / 2f, barWidth, barHeight, cornerPx, out)
        assertEquals(1f, out[0], 1e-4f); assertEquals(0f, out[1], 1e-4f)
        roundedRectOutwardNormal(-50f, barHeight / 2f, barWidth, barHeight, cornerPx, out)
        assertEquals(-1f, out[0], 1e-4f); assertEquals(0f, out[1], 1e-4f)
        // 端头圆弧外 45°：法向指向圆弧圆心到触点的方向
        val cx = barWidth - cornerPx; val cy = cornerPx
        roundedRectOutwardNormal(cx + 100f, cy - 100f, barWidth, barHeight, cornerPx, out)
        assertEquals(0.7071f, out[0], 1e-3f); assertEquals(-0.7071f, out[1], 1e-3f)
        // 无效边界：退化为固定方向且不产生 NaN
        roundedRectOutwardNormal(1f, 1f, 0f, 0f, 0f, out)
        assertTrue(out[0].isFinite() && out[1].isFinite())
    }

    @Test fun axisDeltaIsModulo180() {
        assertEquals(0f, shortestAxisDeltaDeg(0f, 180f), 1e-4f)
        assertEquals(90f, shortestAxisDeltaDeg(0f, 90f), 1e-4f)
        assertEquals(-10f, shortestAxisDeltaDeg(0f, 170f), 1e-4f)
        assertEquals(10f, shortestAxisDeltaDeg(175f, 5f), 1e-4f)
        assertEquals(0f, shortestAxisDeltaDeg(Float.NaN, 30f), 1e-4f)
    }
}
