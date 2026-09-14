package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保底唤起的选择判据。
 *
 * 这条线路只在"Binder 和广播都判定没人应答"时才走，作用是把宿主主进程拉起来，
 * 让它把自己磁盘上那份按面持久化的快照读回内存后再应答一次。
 */
class HostProcessWakePolicyTest {

    private val target = "tv.danmaku.bili"

    private fun provider(
        authority: String?,
        exported: Boolean = true,
        enabled: Boolean = true,
        readPermission: String? = null,
        writePermission: String? = null,
        processName: String? = "tv.danmaku.bili"
    ) = HostProviderCandidate(authority, exported, enabled, readPermission, writePermission, processName)

    /**
     * 真实清单片段（9.11.0(9110400) `aapt2 dump xmltree` + 8.90.2 真机 `dumpsys package providers`）。
     *
     * `VideoDownloadProvider` 是最重要的那个反例：它同样是 provider，但声明了
     * `android:process=":download"`——拉起来的是下载子进程，里面**没有快照桥**，纯白费。
     */
    @Test fun picksExportedMainProcessProvidersAndRejectsTheDownloadOne() {
        val selected = HostProcessWakePolicy.selectAuthorities(target, listOf(
            provider("tv.danmaku.bili.providers.VideoDownloadProvider",
                exported = false, processName = "tv.danmaku.bili:download"),
            provider("tv.danmaku.bili.providers.BiliDataProvider"),
            provider("com.bilibili.lib.accessbridge.BiliAccessContentProvider"),
            provider("tv.danmaku.bili.fileprovider", exported = false),
            provider("tv.danmaku.bili.appinit", exported = false)
        ))
        assertEquals(listOf(
            "com.bilibili.lib.accessbridge.BiliAccessContentProvider",
            "tv.danmaku.bili.providers.BiliDataProvider"
        ), selected)
    }

    @Test fun rejectsEverythingThatIsNotFreelyReachableInTheMainProcess() {
        listOf(
            provider("a.not.exported", exported = false),
            provider("a.disabled", enabled = false),
            provider("a.read.gated", readPermission = "tv.danmaku.bili.permission.X"),
            provider("a.write.gated", writePermission = "tv.danmaku.bili.permission.X"),
            provider("a.other.process", processName = "tv.danmaku.bili:web"),
            provider("a.null.process", processName = null),
            provider(null),
            provider("   ")
        ).forEach {
            assertEquals(it.authority.orEmpty(), emptyList<String>(),
                HostProcessWakePolicy.selectAuthorities(target, listOf(it)))
        }
    }

    /** `authority` 允许是分号分隔的多个；取第一个。顺序固定、条数有界，便于按日志复现。 */
    @Test fun takesTheFirstAuthorityAndStaysBounded() {
        assertEquals(listOf("first.one"),
            HostProcessWakePolicy.selectAuthorities(target, listOf(provider("first.one;second.one"))))
        val many = (1..10).map { provider("auth.%02d".format(it)) }
        val selected = HostProcessWakePolicy.selectAuthorities(target, many.reversed())
        assertEquals(HostProcessWakePolicy.MAX_AUTHORITIES, selected.size)
        assertEquals(listOf("auth.01", "auth.02", "auth.03"), selected)
        assertEquals(selected, HostProcessWakePolicy.selectAuthorities(target, many))
    }

    /** 唤起只对"压根没人应答"有意义；协议、摘要、身份类失败唤起多少次都一样。 */
    @Test fun onlyUnreachableFailuresAreWorthWakingTheHostFor() {
        listOf(ReceiptQueryFailure.UNHANDLED, ReceiptQueryFailure.TIMEOUT, ReceiptQueryFailure.SEND_FAILED)
            .forEach { assertTrue(it.name, HostProcessWakePolicy.shouldWake(it)) }
        listOf(ReceiptQueryFailure.NONE, ReceiptQueryFailure.MALFORMED_RESPONSE,
            ReceiptQueryFailure.NONCE_MISMATCH, ReceiptQueryFailure.UNSUPPORTED_PROTOCOL,
            ReceiptQueryFailure.DIGEST_MISMATCH, ReceiptQueryFailure.SOURCE_MISMATCH,
            ReceiptQueryFailure.SURFACE_MISMATCH, ReceiptQueryFailure.SESSION_MISMATCH,
            ReceiptQueryFailure.STORE_FAILED)
            .forEach { assertFalse(it.name, HostProcessWakePolicy.shouldWake(it)) }
    }
}
