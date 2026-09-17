package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import androidx.core.content.ContextCompat
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模块设置 App 对公平运行内存广播的唯一接入点。
 *
 * 工作只做三件：解析 TRIM/KILL、在主线程释放可重建的 Liquid 图形、在 3 秒内
 * oneway 回调系统 Binder。不读写 Remote Preferences、不发跨应用广播、不唤起
 * 宿主、不碰授权链、不在宿主进程或 system_server 里注册。
 */
internal object FairRunningMemoryCoordinator {
    private const val TAG = "BIL-FairMemory"
    internal const val DEBUG_PROBE_EXTRA = "bil_fair_probe"

    private val initialized = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var onTrim: (() -> Unit)? = null

    @Volatile
    private var onPersist: (() -> Unit)? = null

    fun initialize(
        context: Context,
        onTrim: () -> Unit,
        onPersist: () -> Unit
    ) {
        if (!initialized.compareAndSet(false, true)) return
        this.onTrim = onTrim
        this.onPersist = onPersist
        val app = context.applicationContext
        val thread = HandlerThread(TAG).apply {
            isDaemon = true
            start()
        }
        val handler = Handler(thread.looper)
        val filter = IntentFilter().apply {
            addAction(FairRunningMemoryContract.ACTION_TRIM)
            addAction(FairRunningMemoryContract.ACTION_KILL)
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(
                app,
                Receiver,
                filter,
                null,
                handler,
                ContextCompat.RECEIVER_EXPORTED
            )
        }.onFailure { throwable ->
            Log.w(TAG, "fair-memory receiver not registered", throwable)
            thread.quitSafely()
            initialized.set(false)
            this.onTrim = null
            this.onPersist = null
        }.isSuccess
        if (registered) {
            Log.i(TAG, "receiver registered TRIM+KILL")
        }
    }

    internal fun handle(notice: FairRunningMemoryNotice) {
        if (FairRunningMemoryPolicy.shouldPersist(notice.kind)) {
            runCatching { onPersist?.invoke() }
        }
        val trimCompleted = if (FairRunningMemoryPolicy.shouldReleaseGraphics(notice.kind)) {
            runOnMainWithBudget { onTrim?.invoke() }
        } else {
            true
        }
        val result = when (notice.kind) {
            FairRunningMemoryKind.TRIM -> FairRunningMemoryPolicy.resultCode(trimCompleted)
            // 查杀的契约是保存现场。设置已随用户操作落盘；图形释放超时仍回 HANDLED，
            // 避免因为主线程忙而让系统按「未处理」加时处置。
            FairRunningMemoryKind.KILL -> FairRunningMemoryContract.RESULT_HANDLED
        }
        Log.i(
            TAG,
            "handled kind=${notice.kind} type=${notice.notifyType} id=${notice.notifyId} " +
                "result=$result trim=$trimCompleted callback=${notice.callback != null}"
        )
        val callback = notice.callback ?: return
        reply(
            callback = callback,
            notifyType = notice.notifyType,
            notifyId = notice.notifyId,
            result = result
        )
    }

    private fun runOnMainWithBudget(block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).isSuccess
        }
        val done = CountDownLatch(1)
        val posted = mainHandler.post {
            try {
                runCatching(block)
            } finally {
                done.countDown()
            }
        }
        if (!posted) return false
        return done.await(FairRunningMemoryContract.WORK_BUDGET_MS, TimeUnit.MILLISECONDS)
    }

    internal fun reply(callback: IBinder, notifyType: Int, notifyId: Int, result: Int) {
        val data = Parcel.obtain()
        val replyParcel = Parcel.obtain()
        try {
            data.writeInt(notifyType)
            data.writeInt(notifyId)
            data.writeInt(result)
            data.writeBundle(Bundle())
            callback.transact(
                FairRunningMemoryContract.TRANSACTION_EXCEPTION_REPLY,
                data,
                replyParcel,
                IBinder.FLAG_ONEWAY
            )
        } catch (throwable: Exception) {
            Log.w(TAG, "fair-memory reply failed", throwable)
        } finally {
            replyParcel.recycle()
            data.recycle()
        }
    }

    private object Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BuildConfig.DEBUG && intent.getBooleanExtra(DEBUG_PROBE_EXTRA, false)) {
                Log.i(TAG, "debug probe ${intent.action}")
                handle(
                    FairRunningMemoryNotice(
                        kind = FairRunningMemoryPolicy.kindFor(intent.action, null)
                            ?: FairRunningMemoryKind.TRIM,
                        notifyType = FairRunningMemoryContract.NOTIFY_TYPE_PSS,
                        notifyId = 0,
                        reason = "debug-probe",
                        action = FairRunningMemoryContract.ACTION_VALUE_TRIM,
                        callback = null,
                        heapSizeKb = null,
                        heapCapacityKb = null,
                        pssKb = null,
                        pssLimitKb = null
                    )
                )
                return
            }
            val notice = FairRunningMemoryContract.parse(intent.action, intent.extras)
            if (notice == null) {
                Log.w(TAG, "ignored ${intent.action} extras=${intent.extras != null}")
                return
            }
            handle(notice)
        }
    }
}
