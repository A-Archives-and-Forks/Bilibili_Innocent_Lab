package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import android.os.IBinder

/**
 * 金标联盟 / 小米「公平运行内存」广播的字段与处理策略。
 *
 * 只描述**模块设置 App 进程**如何识别 TRIM/KILL。宿主 `tv.danmaku.bili` 与
 * `system_server` 不得注册同名接收器：那会截走哔哩哔哩自己的回调 Binder，
 * 既破坏已验证的跨进程通道，也可能让宿主因未应答或错误应答被查杀。
 *
 * 协议来源：https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2304
 * （表格为官网截图；示例代码把堆大小键写成 `heapAlloc`，表格 value 为 `heapSize`，
 * 解析时两个都认。）
 */
internal enum class FairRunningMemoryKind {
    TRIM,
    KILL
}

internal data class FairRunningMemoryNotice(
    val kind: FairRunningMemoryKind,
    val notifyType: Int,
    val notifyId: Int,
    val reason: String?,
    val action: String?,
    val callback: IBinder?,
    val heapSizeKb: Int?,
    val heapCapacityKb: Int?,
    val pssKb: Int?,
    val pssLimitKb: Int?
)

internal data class FairRunningMemoryFields(
    val notifyType: Int = 0,
    val notifyId: Int = 0,
    val reason: String? = null,
    val action: String? = null,
    val heapSizeKb: Int? = null,
    val heapAllocKb: Int? = null,
    val heapCapacityKb: Int? = null,
    val pssKb: Int? = null,
    val pssLimitKb: Int? = null
)

internal object FairRunningMemoryContract {
    const val ACTION_TRIM = "itgsa.intent.action.TRIM"
    const val ACTION_KILL = "itgsa.intent.action.KILL"

    const val KEY_COMMON = "common"
    const val KEY_EXTRA = "extra"
    const val KEY_NOTIFY_TYPE = "notifyType"
    const val KEY_NOTIFY_ID = "notifyId"
    const val KEY_REASON = "reason"
    const val KEY_ACTION = "action"
    const val KEY_CALLBACK = "callback"
    const val KEY_HEAP_SIZE = "heapSize"
    const val KEY_HEAP_ALLOC = "heapAlloc"
    const val KEY_HEAP_CAPACITY = "heapCapacity"
    const val KEY_PSS = "pss"
    const val KEY_PSS_LIMIT = "pssLimit"

    const val NOTIFY_TYPE_PSS = 1000
    const val NOTIFY_TYPE_HEAP = 2000

    const val ACTION_VALUE_TRIM = "trim"
    const val ACTION_VALUE_KILL = "kill"

    /** 已释放或已保存。 */
    const val RESULT_HANDLED = 0
    /** 未正确处理。 */
    const val RESULT_UNHANDLED = 1

    const val TRANSACTION_EXCEPTION_REPLY = IBinder.FIRST_CALL_TRANSACTION

    /** 系统端超时；本进程必须在此之前 oneway 回调。 */
    const val SYSTEM_DEADLINE_MS = 3_000L
    /**
     * 主线程释放/保存的等待上限。留出注册、解析和 Binder 回调的余量，
     * 避免等满 3 秒才回复。超时后仍让已 post 的工作继续，不撤销。
     */
    const val WORK_BUDGET_MS = 1_500L

    fun parse(intentAction: String?, extras: Bundle?): FairRunningMemoryNotice? {
        val common = extras?.getBundle(KEY_COMMON) ?: return null
        val extra = extras.getBundle(KEY_EXTRA)
        return fromFields(
            intentAction = intentAction,
            fields = FairRunningMemoryFields(
                notifyType = common.getInt(KEY_NOTIFY_TYPE, 0),
                notifyId = common.getInt(KEY_NOTIFY_ID, 0),
                reason = common.getString(KEY_REASON),
                action = common.getString(KEY_ACTION),
                heapSizeKb = extra.intOrNull(KEY_HEAP_SIZE),
                heapAllocKb = extra.intOrNull(KEY_HEAP_ALLOC),
                heapCapacityKb = extra.intOrNull(KEY_HEAP_CAPACITY),
                pssKb = extra.intOrNull(KEY_PSS),
                pssLimitKb = extra.intOrNull(KEY_PSS_LIMIT)
            ),
            callback = runCatching { common.getBinder(KEY_CALLBACK) }.getOrNull()
        )
    }

    fun fromFields(
        intentAction: String?,
        fields: FairRunningMemoryFields,
        callback: IBinder? = null
    ): FairRunningMemoryNotice? {
        val kind = FairRunningMemoryPolicy.kindFor(
            intentAction = intentAction,
            bundleAction = fields.action
        ) ?: return null
        return FairRunningMemoryNotice(
            kind = kind,
            notifyType = fields.notifyType,
            notifyId = fields.notifyId,
            reason = fields.reason,
            action = fields.action,
            callback = callback,
            heapSizeKb = fields.heapSizeKb ?: fields.heapAllocKb,
            heapCapacityKb = fields.heapCapacityKb,
            pssKb = fields.pssKb,
            pssLimitKb = fields.pssLimitKb
        )
    }
}

private fun Bundle?.intOrNull(key: String): Int? =
    if (this != null && containsKey(key)) getInt(key) else null

/**
 * 与 [CrossAppBroadcastPolicy] 刻意分开：公平内存是系统发来的隐式广播，
 * 必须 EXPORTED；NPatch 一次性回调是本应用身份的反向通道，必须 NOT_EXPORTED。
 * 两套 flags 不能复用同一注册入口。
 */
internal object FairRunningMemoryPolicy {
    fun kindFor(intentAction: String?, bundleAction: String?): FairRunningMemoryKind? {
        when (intentAction) {
            FairRunningMemoryContract.ACTION_TRIM -> return FairRunningMemoryKind.TRIM
            FairRunningMemoryContract.ACTION_KILL -> return FairRunningMemoryKind.KILL
        }
        return when (bundleAction) {
            FairRunningMemoryContract.ACTION_VALUE_TRIM -> FairRunningMemoryKind.TRIM
            FairRunningMemoryContract.ACTION_VALUE_KILL -> FairRunningMemoryKind.KILL
            else -> null
        }
    }

    fun shouldReleaseGraphics(kind: FairRunningMemoryKind): Boolean = true

    fun shouldPersist(kind: FairRunningMemoryKind): Boolean =
        kind == FairRunningMemoryKind.KILL

    fun resultCode(completed: Boolean): Int =
        if (completed) {
            FairRunningMemoryContract.RESULT_HANDLED
        } else {
            FairRunningMemoryContract.RESULT_UNHANDLED
        }

    fun receiverMustBeExported(): Boolean = true
}
