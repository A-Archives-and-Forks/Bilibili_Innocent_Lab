package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.os.SystemClock

/**
 * 观测快照查询的最后一条保底线路：把宿主主进程拉起来，再让调用方重试一次。
 *
 * 判据与边界见 [HostProcessWakePolicy]。这里只负责枚举、按序尝试和节流。
 *
 * **必须在后台线程调用**：`acquireUnstableContentProviderClient` 会一直阻塞到宿主
 * 进程创建完、provider 发布为止（真机实测 1–3 秒），放主线程就是 ANR。
 *
 * 只由用户在模块设置页发起的查询触发（`ReceiptQueryTransport` 的两个调用方都是
 * 前台流程），不做任何轮询、不注册常驻组件、不在宿主进程里运行。
 */
internal object HostProcessWaker {

    private val lock = Any()
    private var lastAttemptAt = 0L
    private var lastSuccessAt = 0L

    /**
     * @return true 表示"宿主主进程现在应该在了，值得重试查询"。
     *
     * 冷却窗口内刚成功过的直接返回 true——两个面并发查询时，第二个不该因为
     * "这次没真的去戳"就放弃重试（「管理推荐屏蔽」同时拉两个面，踩得到）。
     */
    fun wake(context: Context): Boolean {
        // 兜住"将来有人从主线程调用"：宁可这次保底不生效，也不能把主线程按在
        // acquireUnstableContentProviderClient 上等宿主冷启（那就是 ANR）。
        // 同样挡住"锁被后台唤起持有着、主线程来抢"这条更隐蔽的路径。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ReceiptQueryLog.failure("wake_main_thread", ReceiptQueryFailure.SEND_FAILED)
            return false
        }
        return wakeLocked(context)
    }

    private fun wakeLocked(context: Context): Boolean = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSuccessAt < HostProcessWakePolicy.COOLDOWN_MS && lastSuccessAt > 0L) return true
        if (now - lastAttemptAt < HostProcessWakePolicy.COOLDOWN_MS && lastAttemptAt > 0L) return false
        lastAttemptAt = now
        val started = attempt(context, now)
        if (started) lastSuccessAt = SystemClock.elapsedRealtime()
        else ReceiptQueryLog.failure("wake", ReceiptQueryFailure.SEND_FAILED)
        started
    }

    private fun attempt(context: Context, startedAt: Long): Boolean {
        val authorities = runCatching { candidates(context) }.getOrDefault(emptyList())
        for (authority in authorities) {
            if (SystemClock.elapsedRealtime() - startedAt > HostProcessWakePolicy.DEADLINE_MS) return false
            val acquired = runCatching {
                context.contentResolver.acquireUnstableContentProviderClient(authority)?.use { true }
            }.getOrNull()
            if (acquired == true) return true
        }
        return false
    }

    /** `<queries>` 里已声明宿主包，所以枚举得到；宿主没装或被系统隐藏时返回空。 */
    private fun candidates(context: Context): List<String> {
        val info = context.packageManager.getPackageInfo(
            MineComponentSnapshotQueryContract.TARGET_PACKAGE,
            PackageManager.GET_PROVIDERS
        )
        val providers = info.providers?.map { provider ->
            HostProviderCandidate(
                authority = provider.authority,
                exported = provider.exported,
                enabled = provider.enabled,
                readPermission = provider.readPermission,
                writePermission = provider.writePermission,
                processName = provider.processName
            )
        }.orEmpty()
        return HostProcessWakePolicy.selectAuthorities(
            MineComponentSnapshotQueryContract.TARGET_PACKAGE, providers
        )
    }
}
