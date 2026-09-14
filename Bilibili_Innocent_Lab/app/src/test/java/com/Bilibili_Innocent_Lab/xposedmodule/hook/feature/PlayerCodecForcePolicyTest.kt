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

}
