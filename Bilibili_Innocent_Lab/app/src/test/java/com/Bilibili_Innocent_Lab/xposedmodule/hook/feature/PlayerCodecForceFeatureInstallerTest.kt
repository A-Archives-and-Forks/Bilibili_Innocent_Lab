package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.app.playerunite.v1.CodeType
import com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq
import com.bapis.bilibili.app.playerunite.v1.PlayerMoss
import com.bapis.bilibili.app.playerunite.v1.VideoVod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerCodecForceFeatureInstallerTest {
    private val loader = javaClass.classLoader!!

    private fun environment(registrar: PlayerPortTestRegistrar) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, _ -> }
    )

    @Test
    fun `request hook copies the nested vod and sets av1 preference`() {
        val registrar = PlayerPortTestRegistrar()
        val result = PlayerCodecForceFeatureInstaller(
            PlayerCodecPreference.AV1.value,
            PlayerDecodeMode.FOLLOW_HOST.value
        ).install(environment(registrar))

        assertEquals(FeatureInstallResult.Installed(1, false), result)
        val source = PlayViewUniteReq(VideoVod(CodeType.CODE_UNKNOWN, 16))
        val args = arrayOf<Any?>(source)
        val actual = registrar.invoke(
            "player_codec_force.request.PlayViewUniteReq",
            PlayerMoss(), args
        ) { callbackArgs -> callbackArgs[0] } as PlayViewUniteReq

        assertFalse(actual === source)
        assertFalse(actual.getVod() === source.getVod())
        assertEquals(CodeType.CODEAV1, actual.getVod().getPreferCodecType())
        assertEquals(2064, actual.getVod().getFnval())
        assertEquals(CodeType.CODE_UNKNOWN, source.getVod().getPreferCodecType())
        assertEquals(16, source.getVod().getFnval())
    }

    @Test
    fun `decode mode registers bundle and native option guards`() {
        val registrar = PlayerPortTestRegistrar()
        val result = PlayerCodecForceFeatureInstaller(
            PlayerCodecPreference.FOLLOW_HOST.value,
            PlayerDecodeMode.FORCE_SOFTWARE.value
        ).install(environment(registrar))

        assertEquals(FeatureInstallResult.Installed(3), result)
        assertEquals(3, registrar.hooks.size)
        assertTrue(registrar.hooks.containsKey("player_codec_force.bundle"))
        registrar.hooks.filterKeys { it.startsWith("player_codec_force.option.") }.forEach { (id, entry) ->
            val type = entry.member.parameterTypes[2]
            val args = if (type == String::class.java) {
                arrayOf<Any?>(4, "mediacodec", "1")
            } else {
                arrayOf<Any?>(4, "mediacodec", 1L)
            }
            val rewritten = registrar.invoke(
                id,
                tv.danmaku.ijk.media.player.services.IjkMediaPlayerItemClient(),
                args
            ) { callbackArgs -> callbackArgs[2] }
            assertEquals(if (type == String::class.java) "0" else 0L, rewritten)
        }
    }

    @Test
    fun `follow host installs nothing`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            PlayerCodecForceFeatureInstaller(0, 0).install(environment(registrar))
        )
        assertTrue(registrar.hooks.isEmpty())
    }
}
