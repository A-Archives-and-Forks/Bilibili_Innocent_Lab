package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import aistub.accounts.AccessToken
import aistub.accounts.BiliAccounts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliAccessKeyProbeTest {
    private val probe = checkNotNull(
        BiliAccessKeyProbe.resolve(javaClass.classLoader!!, "aistub.accounts.BiliAccounts", "aistub.accounts.AccessToken")
    )

    @After
    fun tearDown() {
        BiliAccounts.current = null
    }

    @Test
    fun `classification follows the host token checks`() {
        assertEquals(BiliAccessKeyState.READY, BiliAccessKeyProbe.classify(hasKey = true, valid = true, expired = false))
        assertEquals(BiliAccessKeyState.EXPIRED, BiliAccessKeyProbe.classify(hasKey = true, valid = true, expired = true))
        assertEquals(BiliAccessKeyState.EXPIRED, BiliAccessKeyProbe.classify(hasKey = true, valid = false, expired = false))
        assertEquals(BiliAccessKeyState.NOT_LOGGED_IN, BiliAccessKeyProbe.classify(hasKey = false, valid = true, expired = false))
    }

    @Test
    fun `state is read through the host account objects`() {
        assertEquals(BiliAccessKeyState.NOT_LOGGED_IN, probe.state(null))
        BiliAccounts.current = AccessToken("token", true, false)
        assertEquals(BiliAccessKeyState.READY, probe.state(null))
        BiliAccounts.current = AccessToken("token", true, true)
        assertEquals(BiliAccessKeyState.EXPIRED, probe.state(null))
        BiliAccounts.current = AccessToken("  ", true, false)
        assertEquals(BiliAccessKeyState.NOT_LOGGED_IN, probe.state(null))
    }

    /** 只有状态码能离开探测层：状态码里不含令牌原文。 */
    @Test
    fun `state codes never carry the token`() {
        BiliAccounts.current = AccessToken("secret-token-value", true, false)
        val code = probe.state(null).code
        assertFalse(code.contains("secret"))
        assertTrue(BiliAccessKeyState.entries.all { it.code.matches(Regex("[a-z_]+")) })
    }

    @Test
    fun `missing host classes disable the probe`() {
        assertNull(BiliAccessKeyProbe.resolve(javaClass.classLoader!!, "aistub.accounts.Missing", "aistub.accounts.AccessToken"))
    }

    @Test
    fun `strong mode requires the access key authorization`() {
        assertTrue(AiDeclaredVideoPolicy.effectiveStrongMode(strongMode = true, accessKeyAuthorized = true))
        assertFalse(AiDeclaredVideoPolicy.effectiveStrongMode(strongMode = true, accessKeyAuthorized = false))
        assertFalse(AiDeclaredVideoPolicy.effectiveStrongMode(strongMode = false, accessKeyAuthorized = true))
    }
}
