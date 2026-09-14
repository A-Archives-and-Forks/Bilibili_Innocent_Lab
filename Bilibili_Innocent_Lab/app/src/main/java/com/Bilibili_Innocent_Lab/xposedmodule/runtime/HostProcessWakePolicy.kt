package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/** 宿主清单里一条 provider 的判据输入；只带做选择需要的字段，不持有 `ProviderInfo`。 */
internal data class HostProviderCandidate(
    val authority: String?,
    val exported: Boolean,
    val enabled: Boolean,
    val readPermission: String?,
    val writePermission: String?,
    val processName: String?
)

/**
 * "宿主进程根本没起来"时的保底唤起判据。
 *
 * ## 为什么需要
 *
 * 观测快照（我的页 / 底栏 / 首页 Tab / 首页组件 / 两个点选面）是宿主进程产出的，
 * 模块要么走 Binder 会话、要么走有序广播去取。宿主进程不在时两条都拿不到东西，
 * 面板只能报"未收到哔哩哔哩响应"，而宿主**磁盘上其实存着上一次的完整快照**
 * （`MineComponentSnapshotHostBridge` 按面持久化，进程启动时读回 `latest`）。
 * 差的只是"让那个进程跑起来"。
 *
 * ## 判据：结构，不是名字
 *
 * 访问一个应用的 ContentProvider 会由系统把它的进程拉起来——这是平台的正常行为，
 * 不绕过任何权限：候选必须**自己就是公开可访问的**。所以判据全是清单事实：
 *
 * - `exported && enabled`：宿主自己声明对外开放的组件；
 * - 读写权限都为 null：没有权限门，我们不需要、也不会去申请宿主的自定义权限；
 * - `processName == 宿主包名`：**必须是主进程**。`:download` / `:web` 这些子进程里
 *   没有快照桥，拉起来纯属白费（`VideoDownloadProvider` 就是个现成的反例）。
 *
 * **一个宿主类名都不写死**（AGENTS.md 红线）：候选来自 `GET_PROVIDERS` 的枚举，
 * 宿主哪版换了 provider 都不影响；一个都不满足就整条降级，不做任何猜测。
 * 2026-09-15 在 9.11.0 清单与 8.90.2 真机上各自都能选出 ≥2 个候选。
 *
 * ## 有界
 *
 * 最多试 [MAX_AUTHORITIES] 个、且总时长不超过 [DEADLINE_MS]；排序后再取，
 * 保证同一宿主每次选的是同一批（便于按日志复现）。
 */
internal object HostProcessWakePolicy {
    /** 一次保底最多戳几个 authority；第一个成功就停。 */
    const val MAX_AUTHORITIES = 3

    /** 整个唤起动作的预算；`acquireUnstableContentProviderClient` 自身会阻塞，只能在两次之间检查。 */
    const val DEADLINE_MS = 6_000L

    /**
     * 唤起成功后等多久再重试查询。
     *
     * Provider 发布时模块的 Hook 已在 `Application.attach` 内装好、广播接收器也已注册，
     * 但 `MineComponentSnapshotHostBridge` 把**磁盘快照读回 `latest`** 是排在
     * 后台单线程上的。不等这一下，重试会撞上"宿主在线但这个面还没有内容"。
     */
    const val SETTLE_MS = 1_000L

    /** 同一结果的冷却窗口：刚唤起过就别再戳，刚失败过也别反复戳。 */
    const val COOLDOWN_MS = 15_000L

    /** 只有"压根没人应答"这几种才值得去唤起；协议、摘要、身份类失败唤起也没用。 */
    fun shouldWake(reason: ReceiptQueryFailure): Boolean = ReceiptQueryPolicy.isUnavailable(reason)

    /**
     * 从宿主清单里挑出可以用来拉起**主进程**的 authority。
     *
     * `ProviderInfo.authority` 允许是分号分隔的多个，取第一个即可。
     */
    fun selectAuthorities(
        targetPackage: String,
        candidates: List<HostProviderCandidate>
    ): List<String> = candidates.asSequence()
        .filter { it.exported && it.enabled }
        .filter { it.readPermission == null && it.writePermission == null }
        .filter { it.processName == targetPackage }
        .mapNotNull { it.authority?.substringBefore(';')?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .sorted()
        .take(MAX_AUTHORITIES)
        .toList()
}
