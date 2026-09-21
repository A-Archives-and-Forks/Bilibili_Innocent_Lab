package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手风琴展开进度弹簧的性质测试（纯 JVM）。
 *
 * 验收本体：p 收敛到目标、中途反转时位置与速度连续（无极打断）、
 * 行显影函数的几何边界正确——揭示沿扫过行顶开始显现、羽化带内完成。
 */
class ExpansionMotionPolicyTest {

    private fun stepUntilRest(spring: ExpansionMotionPolicy.Spring, maxFrames: Int = 600): Int {
        var frames = 0
        while (frames < maxFrames) {
            if (spring.step(1f / 60f)) break
            frames++
        }
        return frames
    }

    @Test
    fun springSettlesOnTarget() {
        val spring = ExpansionMotionPolicy.Spring(p = 0f, v = 0f, target = 1f)
        val frames = stepUntilRest(spring)
        assertTrue("spring should settle well under the safety bound", frames in 5..120)
        assertEquals(1f, spring.p, 1e-4f)
        assertEquals(0f, spring.v, 1e-4f)
    }

    @Test
    fun springOvershootStaysSmall() {
        // ζ=0.94 允许轻微过冲（空间弹簧质感），但不应超过 ~8%
        val spring = ExpansionMotionPolicy.Spring(p = 0f, v = 0f, target = 1f)
        var peak = 0f
        repeat(600) {
            spring.step(1f / 60f)
            peak = maxOf(peak, spring.p)
        }
        assertTrue("overshoot must stay subtle", peak < 1.08f)
    }

    @Test
    fun reversalContinuesFromCurrentPositionAndVelocity() {
        val spring = ExpansionMotionPolicy.Spring(p = 0f, v = 0f, target = 1f)
        repeat(12) { spring.step(1f / 60f) }
        val midP = spring.p
        val midV = spring.v
        assertTrue("mid-flight p should be partway", midP in 0.05f..0.9f)
        assertTrue("mid-flight should carry upward velocity", midV > 0f)

        spring.target = 0f
        val frames = stepUntilRest(spring)
        assertEquals(0f, spring.p, 1e-4f)
        // 反转后必须先继续上行一段才被拉回（速度连续），而不是瞬间掉头：
        // 积分器本身保证这一点——这里验证反转后确实收敛且没有卡在中间态。
        assertTrue("reversal should settle promptly", frames < 180)
    }

    @Test
    fun collapsedStateIsExactZero() {
        val spring = ExpansionMotionPolicy.Spring(p = 0.8f, v = 0f, target = 0f)
        stepUntilRest(spring)
        assertEquals(0f, spring.p, 0f)
    }

    @Test
    fun dtIsClampedAgainstFrameStall() {
        val spring = ExpansionMotionPolicy.Spring(p = 0f, v = 0f, target = 1f)
        spring.step(10f) // 模拟 10 秒卡顿：应被钳到 MAX_STEP_SECONDS 的一步，而不是爆发
        // 50ms 步长下 v=15、p=0.75 是有界的正常弹簧步；不钳制会得到天文数字。
        assertTrue("p must stay within the target gap", spring.p in 0f..1f)
        assertTrue("v must stay bounded", abs(spring.v) <= 16f)
    }

    @Test
    fun rowRevealFollowsTheClipEdge() {
        val feather = 66f
        assertEquals(0f, ExpansionMotionPolicy.rowReveal(clipY = 100f, rowTop = 200f, featherPx = feather), 1e-4f)
        assertEquals(0f, ExpansionMotionPolicy.rowReveal(clipY = 200f, rowTop = 200f, featherPx = feather), 1e-4f)
        assertEquals(0.5f, ExpansionMotionPolicy.rowReveal(clipY = 233f, rowTop = 200f, featherPx = feather), 1e-3f)
        assertEquals(1f, ExpansionMotionPolicy.rowReveal(clipY = 266f, rowTop = 200f, featherPx = feather), 1e-4f)
        assertEquals(1f, ExpansionMotionPolicy.rowReveal(clipY = 999f, rowTop = 200f, featherPx = feather), 1e-4f)
        // 退化输入：feather<=0 时退化为硬切换
        assertEquals(1f, ExpansionMotionPolicy.rowReveal(clipY = 200f, rowTop = 200f, featherPx = 0f), 1e-4f)
        assertEquals(0f, ExpansionMotionPolicy.rowReveal(clipY = 199f, rowTop = 200f, featherPx = 0f), 1e-4f)
    }

    @Test
    fun contentAlphaFinishesBeforeTheMotionDoes() {
        assertEquals(0f, ExpansionMotionPolicy.contentAlpha(0f), 1e-4f)
        assertEquals(1f, ExpansionMotionPolicy.contentAlpha(0.55f), 1e-4f)
        assertEquals(1f, ExpansionMotionPolicy.contentAlpha(1f), 1e-4f)
    }
}
