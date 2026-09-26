package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerCodecForcePolicyTest {
    @Test
    fun `follow host leaves fnval and options untouched`() {
        assertNull(PlayerCodecForcePolicy.expectedFnval(2048L, PlayerCodecPreference.FOLLOW_HOST))
        assertNull(PlayerCodecForcePolicy.optionValue("decoder_type", PlayerDecodeMode.FOLLOW_HOST))
    }

    @Test
    fun `av1 sets the av1 fnval bit while h264 and h265 clear it`() {
        assertEquals(2048L, PlayerCodecForcePolicy.expectedFnval(0L, PlayerCodecPreference.AV1))
        assertEquals(0L, PlayerCodecForcePolicy.expectedFnval(2048L, PlayerCodecPreference.H264))
        assertEquals(16L, PlayerCodecForcePolicy.expectedFnval(2064L, PlayerCodecPreference.H265))
    }

    @Test
    fun `decode policy changes all known option families`() {
        assertEquals(1L, PlayerCodecForcePolicy.optionValue("mediacodec-hevc", PlayerDecodeMode.FORCE_HARDWARE))
        assertEquals(0L, PlayerCodecForcePolicy.optionValue("mediacodec-all-videos", PlayerDecodeMode.FORCE_SOFTWARE))
        assertEquals(1L, PlayerCodecForcePolicy.optionValue("decoder_type", PlayerDecodeMode.FORCE_SOFTWARE))
        assertNull(PlayerCodecForcePolicy.optionValue("unknown-option", PlayerDecodeMode.FORCE_SOFTWARE))
    }

    @Test
    fun `decode policy keeps unknown option value types and values`() {
        assertEquals(1L, PlayerCodecForcePolicy.recognizedOptionValue("mediacodec", 1))
        assertEquals(0L, PlayerCodecForcePolicy.recognizedOptionValue("mediacodec", "0"))
        assertNull(PlayerCodecForcePolicy.recognizedOptionValue("mediacodec", 2.5))
        assertNull(PlayerCodecForcePolicy.recognizedOptionValue("mediacodec", "future-mode"))
        assertNull(PlayerCodecForcePolicy.recognizedOptionValue("decoder_type", 2L))
    }

    /** 方案硬约束 R4：AV1 × 强制软解不可用，一律降级 H.264；其他组合原样。 */
    @Test
    fun `av1 with forced software decoding is downgraded to h264`() {
        assertEquals(PlayerCodecPreference.H264,
            PlayerCodecForcePolicy.effectivePreference(PlayerCodecPreference.AV1, PlayerDecodeMode.FORCE_SOFTWARE))
        assertEquals(PlayerCodecPreference.AV1,
            PlayerCodecForcePolicy.effectivePreference(PlayerCodecPreference.AV1, PlayerDecodeMode.FORCE_HARDWARE))
        assertEquals(PlayerCodecPreference.AV1,
            PlayerCodecForcePolicy.effectivePreference(PlayerCodecPreference.AV1, PlayerDecodeMode.FOLLOW_HOST))
        assertEquals(PlayerCodecPreference.H265,
            PlayerCodecForcePolicy.effectivePreference(PlayerCodecPreference.H265, PlayerDecodeMode.FORCE_SOFTWARE))
        // 降级后的请求改写确实把 AV1 位清掉。
        assertEquals(16L, PlayerCodecForcePolicy.expectedFnval(16L or PlayerCodecForcePolicy.AV1_FNVAL,
            PlayerCodecForcePolicy.effectivePreference(PlayerCodecPreference.AV1, PlayerDecodeMode.FORCE_SOFTWARE)))
    }

    /** 跟随宿主编码 + 强制软解：不改偏好，只让服务端不下发 AV1 流。 */
    @Test
    fun `follow host with forced software decoding only strips the av1 capability`() {
        assertEquals(true, PlayerCodecForcePolicy.stripsAv1Only(PlayerCodecPreference.FOLLOW_HOST, PlayerDecodeMode.FORCE_SOFTWARE))
        assertEquals(false, PlayerCodecForcePolicy.stripsAv1Only(PlayerCodecPreference.FOLLOW_HOST, PlayerDecodeMode.FORCE_HARDWARE))
        assertEquals(false, PlayerCodecForcePolicy.stripsAv1Only(PlayerCodecPreference.H264, PlayerDecodeMode.FORCE_SOFTWARE))
        assertEquals(4048L - 2048L, PlayerCodecForcePolicy.withoutAv1(4048L))
        assertEquals(16L, PlayerCodecForcePolicy.withoutAv1(16L))
    }
}
