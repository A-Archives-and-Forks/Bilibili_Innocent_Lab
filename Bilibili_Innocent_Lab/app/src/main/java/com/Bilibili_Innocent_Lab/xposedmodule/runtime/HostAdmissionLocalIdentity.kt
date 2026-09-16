package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/**
 * 同进程 local 路的身份核对。
 *
 * 模块 Context 的 PackageManager 在宿主进程里受宿主包可见性约束，
 * `getApplicationInfo(tv.danmaku.bili)` 会失败并被端点写成 `identity_rejected`。
 * 宿主看自己的包永远可见，UID 在这里核对。
 *
 * 对不上、同 UID、包名不一致、缺 UID，一律视为**这一路不可用**，不是条款拒绝。
 * 嵌入式管理器、分身、厂商包可见性经常走这些形态；闩成 denied 会把已经校验过的
 * Remote Preferences 保底也禁掉。验过的 UID 仍交给端点做租约绑定。
 */
internal object HostAdmissionLocalIdentity {
    sealed class Trust {
        data class Verified(val hostUid: Int) : Trust()
        data object Unavailable : Trust()
    }

    fun verify(
        modulePackageName: String,
        hostUid: Int?,
        moduleUid: Int?,
        hostPackageUid: Int?,
        expectedModulePackage: String,
    ): Trust {
        if (modulePackageName != expectedModulePackage) return Trust.Unavailable
        if (hostUid == null || moduleUid == null || hostPackageUid == null) return Trust.Unavailable
        if (hostUid == moduleUid) return Trust.Unavailable
        if (hostPackageUid != hostUid) return Trust.Unavailable
        return Trust.Verified(hostUid)
    }
}
