package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/**
 * 启动授权的路线次序。保底和兼容优先：任一通道挂起都不能挡住别的路。
 *
 * t=0 并行 provider、API 29+ binder、local。广播会唤醒冻结模块、和首帧抢 CPU，
 * 只在 local 明确不可用（或根本没提交出去）时才发。
 *
 * 调用方只在「当前结果既不是 grant 也不是权威拒绝」时询问下一步。
 */
internal object HostAdmissionRoutePlan {
    const val PROVIDER = "provider"
    const val BINDER = "binder"
    const val LOCAL = "local"
    const val BROADCAST = "broadcast"

    enum class FollowUp { NONE, START_BROADCAST }

    fun binderRequired(sdkInt: Int): Boolean = sdkInt >= 29

    fun followUp(
        completedRoute: String,
        broadcastStarted: Boolean,
    ): FollowUp = when {
        completedRoute == LOCAL && !broadcastStarted -> FollowUp.START_BROADCAST
        else -> FollowUp.NONE
    }
}
