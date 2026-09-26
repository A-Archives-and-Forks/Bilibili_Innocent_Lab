package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * access_key 只允许以"状态码"离开宿主侧探测层：不缓存、不记日志、不上报原文。
 * 强力模式必须以授权为前提，两处读取强力模式的地方都走同一个合成函数。
 */
class BiliAccessKeyContainmentContractTest {
    private val probe = SourceContract.read("hook/feature/BiliAccessKeyProbe.kt")
    private val installer = SourceContract.read("hook/feature/AiDeclaredVideoFeatureInstaller.kt")
    private val entry = SourceContract.read("hook/HookEntry.kt")

    @Test fun probeNeverStoresOrLogsTheToken() {
        val body = probe.after("internal class BiliAccessKeyProbe").before("companion object")
        // 令牌只在一个表达式里判空，不落到字段或变量。
        assertTrue(body.contains("(getAccessKey.invoke(token) as? String)?.isNotBlank() == true"))
        listOf("log", "report", "Log.", "println", "putString", "var ").forEach { forbidden ->
            assertFalse("探测层不得出现 $forbidden", body.contains(forbidden))
        }
    }

    @Test fun installerReportsOnlyTheStateCode() {
        val precheck = installer.after("private fun installPrechecker(").before("private fun installRelatesFeed(")
        assertTrue(precheck.contains("environment.reportStatus(ACCESS_KEY_STATUS, state.code)"))
        assertFalse(precheck.contains("getAccessKey"))
    }

    @Test fun bothStrongModeReadersRequireAuthorization() {
        assertEquals(2, Regex("aiDeclaredStrongModeEffective\\(prefs\\)").findAll(entry).count())
        assertFalse(entry.contains("BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE,\n                            false"))
        assertTrue(entry.contains("accessKeyAuthorized = prefs.getBoolean(FeaturePreferences.BILI_ACCESS_KEY_AUTHORIZED, false)"))
    }
}
