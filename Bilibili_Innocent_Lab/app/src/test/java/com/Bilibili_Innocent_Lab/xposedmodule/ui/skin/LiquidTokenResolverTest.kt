package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidSurfaceAlphaPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidEffectProfile
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidTokenResolver
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidVisualTuningPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidTokenResolverTest {

    @Test
    fun `resolver preserves visual tuning mapping`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)
            val parameters = LiquidTokenResolver.resolve(tuning)

            assertEquals(tuning.cardGlassAlpha, parameters.surfaceAlpha, 0f)
            assertEquals(tuning.modalGlassAlpha, parameters.modalSurfaceAlpha, 0f)
            assertEquals(tuning.motionGlassAlpha, parameters.motionSurfaceAlpha, 0f)
            assertEquals(tuning.cardFallbackAlpha, parameters.fallbackSurfaceAlpha, 0f)
            assertEquals(tuning.modalFallbackAlpha, parameters.fallbackModalSurfaceAlpha, 0f)
            assertEquals(
                tuning.motionFallbackAlpha,
                parameters.fallbackMotionSurfaceAlpha,
                0f
            )
            assertEquals(tuning.saturation, parameters.saturation, 0f)
            assertEquals(0f, parameters.scatteringStrength, 0f)
            assertTrue(parameters.highlightWidthDp <= 0.5f)
            // 标准档使用共享预滤波背景与细折射边，额外定向光学仍只在实时档启用。
            assertEquals(0f, parameters.specularStrength, 0f)
            assertEquals(0f, parameters.fresnelStrength, 0f)
            assertEquals(0f, parameters.causticLuminanceGain, 0f)
            assertEquals(0f, parameters.innerShadowStrength, 0f)
        }
    }

    @Test
    fun `surface role and backend matrix selects the intended alpha`() {
        val parameters = LiquidTokenResolver.resolve(LiquidVisualTuningPolicy.resolve(dark = true))

        assertEquals(
            parameters.surfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.CARD, false, parameters),
            0f
        )
        assertEquals(
            parameters.modalSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.MODAL, false, parameters),
            0f
        )
        assertEquals(
            parameters.fallbackSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.CARD, true, parameters),
            0f
        )
        assertEquals(
            parameters.fallbackModalSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.MODAL, true, parameters),
            0f
        )
        assertEquals(
            parameters.motionSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.MOTION_SURFACE, false, parameters),
            0f
        )
        assertEquals(
            parameters.fallbackMotionSurfaceAlpha,
            LiquidSurfaceAlphaPolicy.resolve(SurfaceRole.MOTION_SURFACE, true, parameters),
            0f
        )
    }

    @Test
    fun `floating surfaces composite over real content while solids stay opaque`() {
        // "对下取色"（2026-09-21）：底部导航胶囊/选中滑块这类浮在滚动内容上的表面，
        // 玻璃层必须以部分 alpha 输出，真实下层内容才能透入合成——只折射合成底图
        // 永远拿不到底下的列表内容。卡片/模态层下层就是窗口底色，保持全不透明。
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.FLOATING) < 1f)
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.SELECTED_ITEM) < 1f)
        assertEquals(1f, LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.CARD), 0f)
        assertEquals(1f, LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.MODAL), 0f)
        assertEquals(1f, LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.TOP_BAR), 0f)
        // 透出量必须够明显——只留一层近乎不可见的膜不算"通透"。
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.FLOATING) <= 0.7f)
        assertTrue(LiquidSurfaceAlphaPolicy.glassContentAlpha(SurfaceRole.SELECTED_ITEM) <= 0.7f)
    }

    @Test
    fun `realtime profile keeps optics visible while preserving readable fallback`() {
        val tuning = LiquidVisualTuningPolicy.resolve(dark = true)
        val standard = LiquidTokenResolver.resolve(tuning)
        val realtime = LiquidTokenResolver.resolve(
            tuning,
            LiquidEffectProfile.REALTIME_CAPTURE
        )

        assertTrue(realtime.refractionHeightDp > standard.refractionHeightDp)
        assertTrue(realtime.refractionAmountDp > standard.refractionAmountDp)
        assertTrue(realtime.interiorDistortionDp > 0f)
        // 边缘色散关闭后每像素少两次纹理采样，也不会再生成突兀彩边。
        assertEquals(0f, realtime.chromaticShiftDp, 0f)
        assertTrue(realtime.scatteringRadiusDp > 0f)
        assertTrue(realtime.scatteringStrength > 0f)
        assertTrue(realtime.surfaceAlpha < standard.surfaceAlpha)
        assertTrue(realtime.highlightWidthDp <= standard.highlightWidthDp)
        assertTrue(realtime.highlightAlpha < standard.highlightAlpha)
        assertEquals(standard.fallbackSurfaceAlpha, realtime.fallbackSurfaceAlpha, 0f)
    }

    @Test
    fun `realtime profile enables directional optics and standard profile does not`() {
        val tuning = LiquidVisualTuningPolicy.resolve(dark = true)
        val standard = LiquidTokenResolver.resolve(tuning)
        val realtime = LiquidTokenResolver.resolve(
            tuning,
            LiquidEffectProfile.REALTIME_CAPTURE
        )

        assertTrue(realtime.specularStrength > 0f)
        assertTrue(realtime.fresnelStrength > 0f)
        assertTrue(realtime.causticLuminanceGain > 0f)
        assertTrue(realtime.innerShadowStrength > 0f)
        // 边缘亮度由 shader 的定向高光承担后，均匀白描边必须明显让位。
        assertTrue(realtime.highlightAlpha <= 0.22f)
        assertTrue(realtime.specularStrength <= 0.20f)
        assertTrue(realtime.fresnelStrength <= 0.08f)
        assertTrue(realtime.causticLuminanceGain <= 0.55f)
        // 光源方位角约定 L = (cos θ, sin θ)、y 轴向下；-145° 指向左上方。
        assertEquals(-145f, realtime.highlightAngleDegrees, 0f)
        assertEquals(standard.highlightAngleDegrees, realtime.highlightAngleDegrees, 0f)
    }

    @Test
    fun `diffused surfaces retain a narrow lens without wide bright or recessed bands`() {
        listOf(false, true).forEach { dark ->
            val tuning = LiquidVisualTuningPolicy.resolve(dark)
            LiquidEffectProfile.entries.forEach { profile ->
                val parameters = LiquidTokenResolver.resolve(tuning, profile)
                assertTrue(parameters.refractionHeightDp in 1f..12f)
                assertTrue(parameters.refractionAmountDp in 1f..7f)
                assertTrue(parameters.depthEffect <= .16f)
                assertTrue(parameters.interiorDistortionDp <= 1.75f)
                assertTrue(parameters.specularStrength <= .06f)
                assertTrue(parameters.fresnelStrength <= .025f)
                assertTrue(parameters.innerShadowStrength <= .018f)
                assertTrue(parameters.effectPaddingDp >= parameters.refractionHeightDp +
                    parameters.interiorDistortionDp + parameters.scatteringRadiusDp)
                assertEquals(tuning.saturation, parameters.saturation, 0f)
            }
        }
    }
}
