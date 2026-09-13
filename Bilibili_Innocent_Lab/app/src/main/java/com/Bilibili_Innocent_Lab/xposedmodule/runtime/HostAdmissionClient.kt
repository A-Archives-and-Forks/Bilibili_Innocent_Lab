package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.content.Context
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.app.PendingIntent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.compat.CompatibilityReceiptService
import com.Bilibili_Innocent_Lab.xposedmodule.receiver.RoamingOpenReceiver
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Bundle
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigDecodeResult
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigSnapshot
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 启动窗口内完成新鲜许可；工作线程只读取，不在迟到回调中安装 Hook。 */
internal object HostAdmissionClient {
    data class Grant(val snapshot: RemoteHookConfigSnapshot, val source: String, val identity: PublicationIdentity? = null)
    data class Result(val grant: Grant? = null, val reason: String = "admission_unavailable")
    private val boundWorker = HostReceiptWire.executor("bil-bound-admission")
    private val worker = HostReceiptWire.executor("bil-host-admission")
    private val broadcastWorker = HostReceiptWire.executor("bil-broadcast-admission")
    private val localWorker = HostReceiptWire.executor("bil-local-admission")

    private const val TAG = "BilibiliInnocentLab"

    fun admit(context: Context, normal: RemoteHookConfigSnapshot?, normalFailure: String?): Result {
        val app = context.applicationContext ?: context
        val deadline = SystemClock.elapsedRealtime() + HostAdmissionContract.BOOTSTRAP_TIMEOUT_MS
        val responses = LinkedBlockingQueue<Result>(4)
        fun launch(bound: Boolean) = runCatching {
            (if (bound) boundWorker else worker).submit {
                val result = try { exchange(app, normal, normalFailure, deadline, bound = bound) }
                catch (error: SecurityException) {
                    Log.w(TAG, "[BIL] 启动授权通道异常(route=${if (bound) "binder" else "provider"}, type=security)")
                    Result(reason = "admission_denied")
                }
                catch (error: TimeoutException) {
                    Log.w(TAG, "[BIL] 启动授权通道异常(route=${if (bound) "binder" else "provider"}, type=timeout)")
                    Result(reason = "admission_timeout")
                }
                catch (error: Exception) {
                    Log.w(TAG, "[BIL] 启动授权通道异常(route=${if (bound) "binder" else "provider"}, type=${error.javaClass.simpleName})")
                    Result()
                }
                Log.i(TAG, "[BIL] 启动授权通道结果(route=${if (bound) "binder" else "provider"}, reason=${result.reason})")
                responses.offer(result)
            }
        }.getOrNull()
        fun launchBroadcast() = runCatching {
            broadcastWorker.submit {
                val result = try { exchange(app, normal, normalFailure, deadline, broadcast = true) }
                catch (error: SecurityException) {
                    Log.w(TAG, "[BIL] 启动授权通道异常(route=broadcast, type=security)")
                    Result(reason = "admission_denied")
                }
                catch (error: TimeoutException) {
                    Log.w(TAG, "[BIL] 启动授权通道异常(route=broadcast, type=timeout)")
                    Result(reason = "admission_timeout")
                }
                catch (error: Exception) {
                    Log.w(TAG, "[BIL] 启动授权通道异常(route=broadcast, type=${error.javaClass.simpleName})")
                    Result()
                }
                Log.i(TAG, "[BIL] 启动授权通道结果(route=broadcast, reason=${result.reason})")
                responses.offer(result)
            }
        }.getOrNull()
        fun launchLocal() = runCatching {
            localWorker.submit {
                val result = try { exchange(app, normal, normalFailure, deadline, local = true) }
                catch (error: SecurityException) {
                    Log.w(TAG, "[BIL] 启动授权通道异常(route=local, type=security)")
                    Result(reason = "admission_denied")
                }
                catch (error: TimeoutException) {
                    Log.w(TAG, "[BIL] 启动授权通道异常(route=local, type=timeout)")
                    Result(reason = "admission_timeout")
                }
                catch (error: Exception) {
                    Log.w(TAG, "[BIL] 启动授权通道异常(route=local, type=${error.javaClass.simpleName})")
                    Result()
                }
                Log.i(TAG, "[BIL] 启动授权通道结果(route=local, reason=${result.reason})")
                responses.offer(result)
            }
        }.getOrNull()
        val calls = listOfNotNull(
            launch(false),
            if (Build.VERSION.SDK_INT >= 29) launch(true) else null,
            launchBroadcast(),
            launchLocal()
        )
        if (calls.isEmpty()) return Result()
        var failure = Result()
        var sawDenied = false
        try {
            repeat(calls.size) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    return remoteConfigFallback(normal, normalFailure, sawDenied, failure)
                }
                val response = responses.poll(remaining, TimeUnit.MILLISECONDS)
                    ?: return remoteConfigFallback(normal, normalFailure, sawDenied, Result(reason = "admission_timeout"))
                if (response.grant != null && SystemClock.elapsedRealtime() < deadline) return response
                if (response.reason == "admission_denied") sawDenied = true
                failure = response
            }
            return remoteConfigFallback(normal, normalFailure, sawDenied, failure)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Result()
        } finally {
            // 仅取消排队及结果所有权；Binder 事务可能仍在运行，但绝不安装迟到 Hook。
            calls.forEach { it.cancel(false) }
        }
    }

    private fun exchange(
        context: Context,
        normal: RemoteHookConfigSnapshot?,
        normalFailure: String?,
        deadline: Long,
        bound: Boolean = false,
        broadcast: Boolean = false,
        local: Boolean = false
    ): Result {
        val authority = "${BuildConfig.APPLICATION_ID}.admission"
        // 目标宿主可能因 Android 包可见性策略无法从自身 PackageManager 查询模块包。
        // Provider/Service 端会在 Binder 事务内校验真实调用 UID、nonce、版本和授权状态，
        // 因此这里不能把 getApplicationInfo/resolveContentProvider 的可见性结果当作拒绝依据。
        // 有损坏/身份拒绝证据时不切换到另一个更宽松的源。缺失和旧协议可以询问当前权威源。
        val directFallback = normalFailure == null || normalFailure in setOf(
            "remote_group_unavailable", "remote_read_exception", "remote_group_missing", "remote_stale_protocol")
        val nonce = UUID.randomUUID().toString()
        val request = Bundle().apply {
            putLong("deadline", deadline)
            putInt("version", HostAdmissionContract.VERSION)
            putString("nonce", nonce)
            putBoolean("allowDirect", directFallback)
            putLong("normalNoRootRevision", normal?.noRootRevision ?: 0L)
            normal?.takeIf { it.authorized }?.let { putString("normalFingerprint", RemoteHookConfigContract.contentFingerprint(it)) }
        }
        fun exchangeUsing(call: (String, Bundle) -> Bundle?): Result {
            if (SystemClock.elapsedRealtime() >= deadline) return Result(reason = "admission_timeout")
            val prepared = call(HostAdmissionContract.METHOD_PREPARE, request) ?: return Result()
            if (!valid(prepared, nonce) || prepared.getString("status") != "prepared") return Result(reason = "admission_denied")
            val identity = HostAdmissionContract.identity(prepared) ?: return Result(reason = "admission_denied")
            val source = prepared.getString("source")
            val snapshot = when (source) {
                HostAdmissionContract.NORMAL -> normal ?: return Result(reason = "admission_denied")
                HostAdmissionContract.DIRECT -> {
                    if (!directFallback) return Result(reason = "admission_denied")
                    val document = HostAdmissionContract.decode(prepared.getString("document").orEmpty()) ?: return Result(reason = "admission_denied")
                    (RemoteHookConfigContract.decode(document) as? RemoteHookConfigDecodeResult.Ready)?.snapshot
                        ?: return Result(reason = "admission_denied")
                }
                else -> return Result(reason = "admission_denied")
            }
            if (!snapshot.authorized || RemoteHookConfigContract.contentFingerprint(snapshot) != identity.fingerprint) return Result(reason = "admission_denied")
            if (SystemClock.elapsedRealtime() >= deadline) return Result(reason = "admission_timeout")
            val confirmation = Bundle().apply {
                putInt("version", HostAdmissionContract.VERSION)
                putString("nonce", nonce)
                putString("challenge", prepared.getString("challenge"))
                HostAdmissionContract.putIdentity(this, identity)
            }
            val granted = call(HostAdmissionContract.METHOD_CONFIRM, confirmation) ?: return Result()
            if (SystemClock.elapsedRealtime() >= deadline) return Result(reason = "admission_timeout")
            if (!valid(granted, nonce) || granted.getString("status") != "granted" ||
                granted.getString("source") != source || HostAdmissionContract.identity(granted) != identity) return Result(reason = "admission_denied")
            return Result(Grant(snapshot, source, identity), reason = "")
        }
        if (SystemClock.elapsedRealtime() >= deadline) return Result(reason = "admission_timeout")
        return when {
            broadcast -> broadcastExchange(context, deadline, ::exchangeUsing)
            local -> localExchange(context, deadline, ::exchangeUsing)
            bound && Build.VERSION.SDK_INT >= 29 -> boundExchange(context, deadline, ::exchangeUsing)
            else -> context.contentResolver.acquireUnstableContentProviderClient(authority)?.use { client ->
                exchangeUsing { method, extras -> client.call(method, null, extras) }
            } ?: Result()
        }
    }

    /**
     * 所有跨进程传输均被系统丢弃时的最后保底。Remote Preferences 已由
     * RemoteHookConfigContract 完整校验，且只在没有收到任何明确拒绝时采用；
     * 这不是新的授权来源，也不会覆盖 Provider/Binder/广播的结果。
     */
    private fun remoteConfigFallback(
        normal: RemoteHookConfigSnapshot?,
        normalFailure: String?,
        sawDenied: Boolean,
        failure: Result
    ): Result {
        if (!sawDenied && normalFailure == null && normal?.authorized == true &&
            normal.moduleVersionCode == BuildConfig.VERSION_CODE.toLong() &&
            normal.generation > 0L
        ) {
            Log.w(TAG, "[BIL] 启动授权传输不可达，采用已校验 Remote Preferences 回退")
            return Result(
                Grant(snapshot = normal, source = "remote_config_transport_fallback"),
                reason = ""
            )
        }
        return failure
    }

    /**
     * LSPosed 已将模块代码加载到宿主进程时的同进程回退。它仍使用模块 Context、
     * 同一端点和同一 UID/nonce/租约校验；若系统拒绝创建模块 Context，则结果为不可用，
     * 不会把本地 Remote Preferences 直接当成授权。
     */
    private fun localExchange(
        context: Context,
        deadline: Long,
        exchange: ((String, Bundle) -> Bundle?) -> Result
    ): Result {
        if (SystemClock.elapsedRealtime() >= deadline) return Result(reason = "admission_timeout")
        val moduleContext = runCatching {
            context.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY
            )
        }.getOrNull() ?: return Result()
        if (moduleContext.packageName != BuildConfig.APPLICATION_ID) return Result(reason = "admission_denied")
        val hostUid = runCatching { context.applicationInfo.uid }.getOrNull() ?: return Result(reason = "admission_denied")
        val moduleUid = runCatching { moduleContext.applicationInfo.uid }.getOrNull() ?: return Result(reason = "admission_denied")
        if (hostUid == moduleUid) return Result(reason = "admission_denied")
        return exchange { method, extras ->
            if (SystemClock.elapsedRealtime() >= deadline) null
            else HostAdmissionEndpoint.handleBroadcast(moduleContext, method, extras, hostUid)
        }
    }

    /** 包可见性隔离时，经显式有序广播调用模块端的同一两阶段握手。 */
    private fun broadcastExchange(
        context: Context,
        deadline: Long,
        exchange: ((String, Bundle) -> Bundle?) -> Result
    ): Result {
        val nonce = UUID.randomUUID().toString()
        val responseAction = "${context.packageName}.HOST_ADMISSION_RESPONSE.$nonce"
        val mainHandler = Handler(Looper.getMainLooper())
        val response = java.util.concurrent.CompletableFuture<Bundle?>()
        val callbackReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != responseAction) return
                response.complete(intent.getBundleExtra(RoamingOpenReceiver.EXTRA_ADMISSION_RESPONSE))
            }
        }
        CrossAppBroadcastCompat.registerPrivateCallbackReceiver(
            context, callbackReceiver, IntentFilter(responseAction), mainHandler
        )
        val proof = runCatching {
            PendingIntent.getBroadcast(
                context,
                nonce.hashCode(),
                Intent(responseAction).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }.getOrNull()
        if (proof == null) {
            runCatching {
                context.unregisterReceiver(callbackReceiver)
            }
            return Result()
        }
        return try {
            exchange { method, extras ->
                if (SystemClock.elapsedRealtime() >= deadline) return@exchange null
                val intent = Intent(RoamingOpenReceiver.ACTION_QUERY_HOOK_ADMISSION)
                    .setComponent(ComponentName(BuildConfig.APPLICATION_ID, RoamingOpenReceiver::class.java.name))
                    .addFlags(
                        Intent.FLAG_INCLUDE_STOPPED_PACKAGES or
                            Intent.FLAG_RECEIVER_FOREGROUND or
                            FLAG_RECEIVER_INCLUDE_BACKGROUND
                    )
                    .putExtra(RoamingOpenReceiver.EXTRA_ADMISSION_METHOD, method)
                    .putExtra(RoamingOpenReceiver.EXTRA_ADMISSION_REQUEST, Bundle(extras))
                    .putExtra(RoamingOpenReceiver.EXTRA_ADMISSION_NONCE, extras.getString("nonce"))
                    .putExtra(RoamingOpenReceiver.EXTRA_ADMISSION_CALLER_PROOF, proof)
                    .putExtra(RoamingOpenReceiver.EXTRA_ADMISSION_RESPONSE_ACTION, responseAction)
                val resultReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val result = getResultExtras(false)
                        response.complete(
                            result?.takeIf { it.getBoolean(RoamingOpenReceiver.EXTRA_ADMISSION_HANDLED, false) }
                                ?.getBundle(RoamingOpenReceiver.EXTRA_ADMISSION_RESPONSE)
                        )
                    }
                }
                CrossAppBroadcastCompat.sendOrderedBroadcast(
                    context,
                    intent,
                    resultReceiver,
                    mainHandler
                )
                Log.i(TAG, "[BIL] 启动授权广播已发送(method=$method)")
                runCatching {
                    // MIUI 某些版本会丢弃后台有序广播，但允许同一显式组件的普通广播；
                    // 两路共用 nonce，由接收端短时去重并回送同一结果，保留有序回执作为首选。
                    CrossAppBroadcastCompat.sendBroadcast(context, Intent(intent))
                    Log.i(TAG, "[BIL] 启动授权广播回退已发送(method=$method)")
                }.onFailure {
                    Log.w(TAG, "[BIL] 启动授权广播回退发送失败(method=$method, type=${it.javaClass.simpleName})")
                }
                runCatching {
                    // 部分 MIUI 构建只在 sendBroadcastAsUser 路径保留显式跨包投递；
                    // 该路仍受模块端 PendingIntent、UID、nonce 和租约校验保护。
                    context.sendBroadcastAsUser(Intent(intent), android.os.Process.myUserHandle())
                    Log.i(TAG, "[BIL] 启动授权广播用户回退已发送(method=$method)")
                }.onFailure {
                    Log.w(TAG, "[BIL] 启动授权广播用户回退发送失败(method=$method, type=${it.javaClass.simpleName})")
                }
                runCatching {
                    // PendingIntent 由宿主 UID 创建后交给系统调度，可绕过 MIUI 对普通
                    // Context 广播的包可见性拦截；请求内容仍包含同一回调证明和 nonce。
                    val requestToken = PendingIntent.getBroadcast(
                        context,
                        nonce.hashCode() xor method.hashCode(),
                        Intent(intent),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    try {
                        requestToken.send(context, 0, null)
                    } finally {
                        requestToken.cancel()
                    }
                    Log.i(TAG, "[BIL] 启动授权 PendingIntent 回退已发送(method=$method)")
                }.onFailure {
                    Log.w(TAG, "[BIL] 启动授权 PendingIntent 回退发送失败(method=$method, type=${it.javaClass.simpleName})")
                }
                runCatching {
                    response.get(
                        (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L),
                        TimeUnit.MILLISECONDS
                    )
                }.getOrNull().also {
                    if (it != null) Log.i(TAG, "[BIL] 启动授权广播收到回执(method=$method)")
                }
            }
        } finally {
            runCatching { proof.cancel() }
            runCatching { context.unregisterReceiver(callbackReceiver) }
        }
    }
    // API 29 起可指定工作回调执行器，避免 Application.attach 主线程等待自己的绑定回调。
    @androidx.annotation.RequiresApi(29)
    private fun boundExchange(context: Context, deadline: Long, exchange: ((String, Bundle) -> Bundle?) -> Result): Result {
        val component = ComponentName(BuildConfig.APPLICATION_ID, CompatibilityReceiptService::class.java.name)
        val arrival = CompletableFuture<IBinder?>()
        val closed = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (!closed.get()) arrival.complete(service.takeIf { name == component })
            }
            override fun onServiceDisconnected(name: ComponentName) { arrival.complete(null) }
            override fun onBindingDied(name: ComponentName) { arrival.complete(null) }
            override fun onNullBinding(name: ComponentName) { arrival.complete(null) }
        }
        try {
            if (!context.bindService(Intent().setComponent(component), Context.BIND_AUTO_CREATE,
                    java.util.concurrent.Executor { it.run() }, connection)) return Result()
            val binder = arrival.get((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L), TimeUnit.MILLISECONDS) ?: return Result()
            if (!binder.isBinderAlive || binder.interfaceDescriptor != CompatibilityReceiptService.DESCRIPTOR) return Result(reason = "admission_denied")
            return exchange { method, extras ->
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(CompatibilityReceiptService.DESCRIPTOR)
                    data.writeString(method); data.writeBundle(extras)
                    if (!binder.transact(CompatibilityReceiptService.ADMISSION, data, reply, 0)) null
                    else { reply.readException(); reply.readBundle(Bundle::class.java.classLoader) }
                } finally { data.recycle(); reply.recycle() }
            }
        } finally {
            closed.set(true)
            runCatching { context.unbindService(connection) }
        }
    }

    private fun valid(value: Bundle, nonce: String): Boolean = value.getInt("version") == HostAdmissionContract.VERSION &&
        value.getLong("moduleVersion") == BuildConfig.VERSION_CODE.toLong() && value.getString("nonce") == nonce

    // Hidden framework flag (Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND), stable since API 26.
    private const val FLAG_RECEIVER_INCLUDE_BACKGROUND = 0x01000000
}
