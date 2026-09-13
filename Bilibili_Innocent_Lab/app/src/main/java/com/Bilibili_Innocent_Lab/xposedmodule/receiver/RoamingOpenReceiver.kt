package com.Bilibili_Innocent_Lab.xposedmodule.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.IntentCompat
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.provider.RoamingCompatProvider
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostAdmissionEndpoint
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportStore
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootUpgradeRecoveryCoordinator
import com.Bilibili_Innocent_Lab.xposedmodule.settings.terms.UserTermsConsentStore
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.highcapable.kavaref.extension.classOf
import java.util.concurrent.atomic.AtomicLong
import java.util.LinkedHashMap

/**
 * 代开哔哩漫游设置的接收器（B 站进程 → 本模块 App 的跨进程通道）。
 *
 * 背景：部分设备（如本机 MIUI + LSPosed DirectAccessService 分支）上，B 站
 * 进程对任何其他包都不可见（系统级包可见性隔离），既不能直接 startActivity
 * 打开 me.iacn.biliroaming，也不能经 ContentProvider 调用本模块（Unknown
 * authority）。广播投递不受包可见性过滤，是 B 站 → 模块 App 仅剩的可靠
 * 通道：B 站点击「我的」页注入的「哔哩漫游设置」入口后发送显式广播，
 * 本接收器以模块 App 身份启动漫游的 MainActivityAlias（已导出、带 LAUNCHER
 * 类别；其 MainActivity 本体未导出，显式启动会 ActivityNotFoundException）。
 * 同一显式组件还承载一次性 ordered broadcast 的只读授权回执，不接受状态写入。
 */
class RoamingOpenReceiver : BroadcastReceiver() {

    companion object {
        /** Provider 被宿主包可见性隔离时的一次性、只读 Hook 授权查询。 */
        const val ACTION_QUERY_HOOK_AUTHORIZATION =
            "com.Bilibili_Innocent_Lab.xposedmodule.QUERY_HOOK_AUTHORIZATION"
        const val EXTRA_HOOK_AUTHORIZATION_HANDLED = "hook_authorization_handled"
        const val EXTRA_HOOK_AUTHORIZED = "hook_authorized"
        const val ACTION_QUERY_HOOK_ADMISSION =
            "com.Bilibili_Innocent_Lab.xposedmodule.QUERY_HOOK_ADMISSION"
        const val EXTRA_ADMISSION_METHOD = "hook_admission_method"
        const val EXTRA_ADMISSION_REQUEST = "hook_admission_request"
        const val EXTRA_ADMISSION_NONCE = "hook_admission_nonce"
        const val EXTRA_ADMISSION_CALLER_PROOF = "hook_admission_caller_proof"
        const val EXTRA_ADMISSION_RESPONSE_ACTION = "hook_admission_response_action"
        const val EXTRA_ADMISSION_HANDLED = "hook_admission_handled"
        const val EXTRA_ADMISSION_RESPONSE = "hook_admission_response"
        const val EXTRA_BOOTSTRAP_CALLBACK = "no_root_bootstrap_callback"
        const val EXTRA_BOOTSTRAP_NONCE = "no_root_bootstrap_nonce"

        /** NPatch 宿主的启动/关闭回执；调用方身份由其自建 PendingIntent 证明。 */
        const val ACTION_REPORT_NO_ROOT_HEARTBEAT =
            "com.Bilibili_Innocent_Lab.xposedmodule.REPORT_NO_ROOT_HEARTBEAT"
        const val EXTRA_CALLER_PROOF = "no_root_caller_proof"
        const val EXTRA_NO_ROOT_ACTIVE = "no_root_active"

        /** B 站进程发送的广播 action（RoamingCompatHook 中点击入口时发送） */
        const val ACTION_OPEN_ROAMING_SETTINGS = "com.Bilibili_Innocent_Lab.xposedmodule.OPEN_ROAMING_SETTINGS"
        const val EXTRA_REQUEST_ELAPSED_REALTIME = "request_elapsed_realtime"
        private const val MAX_REQUEST_AGE_MS = 5_000L
        private const val MIN_REQUEST_INTERVAL_MS = 1_000L
        private val lastAcceptedRequestMs = AtomicLong(0L)
        private const val ADMISSION_REPLAY_TTL_MS = 5_000L
        private const val ADMISSION_REPLAY_LIMIT = 32
        private val admissionReplayCache = LinkedHashMap<String, AdmissionReplay>(ADMISSION_REPLAY_LIMIT, 0.75f, true)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_QUERY_HOOK_ADMISSION) {
            handleAdmissionQuery(context, intent)
            return
        }
        if (intent.action == ACTION_QUERY_HOOK_AUTHORIZATION) {
            // 必须是显式 ordered broadcast；宿主通过系统最终回调取回结果，
            // 模块不接受任何状态写入。Android 14+ 还校验框架报告的真实发送包。
            if (!isOrderedBroadcast ||
                (AndroidVersion.isAtLeast(AndroidVersion.U) &&
                    sentFromPackage != HookEntry.TARGET_PACKAGE)
            ) return
            val authorized = runCatching {
                UserTermsConsentStore.readOrInitialize(context).isAuthorized
            }.getOrDefault(false)
            getResultExtras(true).apply {
                putBoolean(EXTRA_HOOK_AUTHORIZATION_HANDLED, true)
                putBoolean(EXTRA_HOOK_AUTHORIZED, authorized)
            }
            sendSecureBootstrapReply(context, intent, authorized)
            if (authorized) NoRootUpgradeRecoveryCoordinator.pokeIfRetryable()
            return
        }
        if (intent.action == ACTION_REPORT_NO_ROOT_HEARTBEAT) {
            receiveNoRootHeartbeat(context, intent)
            return
        }
        if (intent.action != ACTION_OPEN_ROAMING_SETTINGS) return
        val authorized = runCatching {
            UserTermsConsentStore.readOrInitialize(context).isAuthorized
        }.getOrDefault(false)
        if (!authorized) return
        val now = SystemClock.elapsedRealtime()
        val requestedAt = intent.getLongExtra(EXTRA_REQUEST_ELAPSED_REALTIME, -1L)
        if (requestedAt <= 0L || now - requestedAt !in 0L..MAX_REQUEST_AGE_MS) return
        // Android 14+ 可取得真实发送方身份；只接受由注入代码所在的 B 站进程
        // 发起的请求。Android 13 及以下没有对应公开 API，因此 Manifest 不再声明
        // Intent Filter，发送方必须知道并显式指定组件，同时还需通过短时效与节流检查。
        // 接收器只执行无参数的设置页跳转，不处理状态写入或外部数据。
        if (AndroidVersion.isAtLeast(AndroidVersion.U) &&
            sentFromPackage != HookEntry.TARGET_PACKAGE
        ) return
        val previous = lastAcceptedRequestMs.get()
        if (previous > 0L && now - previous < MIN_REQUEST_INTERVAL_MS) return
        lastAcceptedRequestMs.set(now)
        // 这里保持接收器直启（Android 13 真机已验证），不走 B 站自建 PendingIntent
        // 的回放（2026-09-04 撤回）：一是 `send()` 仍按创建者身份解析目标，而本通道
        // 被使用的前提正是 B 站侧解析失败；二是 `PendingIntent.send()` 只在结果码落在
        // 致命段 [-100, -1] 时抛 CanceledException，BAL 中止属于非致命段
        // （START_ABORTED=102），成功返回并不证明 Activity 真的启动，用它当回退开关
        // 会在正要防的场景里静默吞掉这次点击。Android 17 的 BAL 复验见 verification.md。
        runCatching {
            val launch = Intent().apply {
                setClassName("me.iacn.biliroaming", "me.iacn.biliroaming.MainActivityAlias")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launch)
        }
    }

    /** Provider/Service 被宿主包可见性隔离时的完整授权握手。 */
    private fun handleAdmissionQuery(context: Context, intent: Intent) {
        Log.i("BilibiliInnocentLab", "[BIL] 收到启动授权广播")
        val ordered = isOrderedBroadcast
        if (AndroidVersion.isAtLeast(AndroidVersion.U) &&
            sentFromPackage != HookEntry.TARGET_PACKAGE
        ) return
        val proof = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_ADMISSION_CALLER_PROOF,
            classOf<PendingIntent>()
        ) ?: return
        val hostUid = runCatching {
            context.packageManager.getApplicationInfo(HookEntry.TARGET_PACKAGE, 0).uid
        }.getOrNull() ?: return
        val creatorPackage = proof.creatorPackage ?: return
        if (creatorPackage != HookEntry.TARGET_PACKAGE || proof.creatorUid != hostUid) return
        val nonce = intent.getStringExtra(EXTRA_ADMISSION_NONCE).orEmpty()
        val request = intent.getBundleExtra(EXTRA_ADMISSION_REQUEST) ?: return
        if (!HostAdmissionQueryContract.isValidNoncePair(nonce, request)) return
        val method = intent.getStringExtra(EXTRA_ADMISSION_METHOD).orEmpty()
        if (method != "prepare_admission" && method != "confirm_admission") return
        val response = admissionReplay(nonce, method) ?: runCatching {
            HostAdmissionEndpoint.handleBroadcast(context, method, request, proof.creatorUid)
        }.getOrNull()?.also { rememberAdmissionReplay(nonce, method, it) } ?: return
        val responseAction = intent.getStringExtra(EXTRA_ADMISSION_RESPONSE_ACTION).orEmpty()
        if (responseAction.isEmpty() ||
            !HostAdmissionQueryContract.isValidResponseAction(creatorPackage, responseAction)
        ) return
        val reply = Intent(responseAction).setPackage(HookEntry.TARGET_PACKAGE).apply {
            putExtra(EXTRA_ADMISSION_RESPONSE, response)
        }
        runCatching { proof.send(context, 0, reply) }
        if (ordered) {
            getResultExtras(true).apply {
                putBoolean(EXTRA_ADMISSION_HANDLED, true)
                putBundle(EXTRA_ADMISSION_RESPONSE, response)
            }
            setResultCode(HostAdmissionQueryContract.RESULT_CODE_HANDLED)
        }
        Log.i("BilibiliInnocentLab", "[BIL] 启动授权广播已处理(status=${response.getString("status")}, ordered=$ordered)")
    }

    private fun admissionReplay(nonce: String, method: String): android.os.Bundle? = synchronized(admissionReplayCache) {
        val now = SystemClock.elapsedRealtime()
        admissionReplayCache.entries.removeIf { now - it.value.createdAt > ADMISSION_REPLAY_TTL_MS }
        admissionReplayCache["$nonce|$method"]?.response?.let { android.os.Bundle(it) }
    }

    private fun rememberAdmissionReplay(nonce: String, method: String, response: android.os.Bundle) {
        synchronized(admissionReplayCache) {
            val now = SystemClock.elapsedRealtime()
            admissionReplayCache.entries.removeIf { now - it.value.createdAt > ADMISSION_REPLAY_TTL_MS }
            while (admissionReplayCache.size >= ADMISSION_REPLAY_LIMIT) {
                admissionReplayCache.remove(admissionReplayCache.entries.firstOrNull()?.key ?: break)
            }
            admissionReplayCache["$nonce|$method"] = AdmissionReplay(now, android.os.Bundle(response))
        }
    }

    private data class AdmissionReplay(val createdAt: Long, val response: android.os.Bundle)

    /** 完整配置只回送到由 B 站 uid 创建的一次性 PendingIntent，不进入 ordered extras。 */
    private fun sendSecureBootstrapReply(
        context: Context,
        request: Intent,
        authorized: Boolean
    ) {
        val callback = IntentCompat.getParcelableExtra(
            request,
            EXTRA_BOOTSTRAP_CALLBACK,
            classOf<PendingIntent>()
        ) ?: return
        if (callback.creatorPackage != HookEntry.TARGET_PACKAGE) return
        val nonce = request.getStringExtra(EXTRA_BOOTSTRAP_NONCE).orEmpty()
        if (nonce.isBlank()) return
        val exported = NoRootSupportStore.exportState(context, authorized)
        val reply = Intent().apply {
            putExtra(EXTRA_BOOTSTRAP_NONCE, nonce)
            putExtra(EXTRA_HOOK_AUTHORIZED, authorized)
            putExtra(RoamingCompatProvider.COLUMN_NO_ROOT_VALID, exported.valid)
            putExtra(RoamingCompatProvider.COLUMN_NO_ROOT_ENABLED, exported.enabled)
            putExtra(RoamingCompatProvider.COLUMN_NO_ROOT_REVISION, exported.revision)
            putExtra(RoamingCompatProvider.COLUMN_NO_ROOT_PAYLOAD, exported.payload)
        }
        runCatching { callback.send(context, 0, reply) }
    }

    private fun receiveNoRootHeartbeat(context: Context, intent: Intent) {
        if (AndroidVersion.isAtLeast(AndroidVersion.U) &&
            sentFromPackage != HookEntry.TARGET_PACKAGE
        ) return
        val callerProof = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_CALLER_PROOF,
            classOf<PendingIntent>()
        ) ?: return
        if (callerProof.creatorPackage != HookEntry.TARGET_PACKAGE) return
        val targetPackage = intent.getStringExtra(
            RoamingCompatProvider.EXTRA_NO_ROOT_TARGET_PACKAGE
        ).orEmpty()
        val processName = intent.getStringExtra(
            RoamingCompatProvider.EXTRA_NO_ROOT_PROCESS
        ).orEmpty()
        if (targetPackage != HookEntry.TARGET_PACKAGE ||
            (processName != targetPackage && !processName.startsWith("$targetPackage:"))
        ) return
        val revision = intent.getLongExtra(
            RoamingCompatProvider.EXTRA_NO_ROOT_REVISION,
            0L
        )
        val moduleVersion = intent.getLongExtra(
            RoamingCompatProvider.EXTRA_NO_ROOT_MODULE_VERSION,
            0L
        )
        val targetVersion = intent.getLongExtra(
            RoamingCompatProvider.EXTRA_NO_ROOT_TARGET_VERSION,
            0L
        )
        val targetUpdateTime = intent.getLongExtra(
            RoamingCompatProvider.EXTRA_NO_ROOT_TARGET_UPDATE_TIME,
            0L
        )
        if (intent.getBooleanExtra(EXTRA_NO_ROOT_ACTIVE, true)) {
            val authorized = runCatching {
                UserTermsConsentStore.readOrInitialize(context).isAuthorized
            }.getOrDefault(false)
            if (!authorized) return
            NoRootSupportStore.recordHeartbeat(
                context = context,
                revision = revision,
                moduleVersionCode = moduleVersion,
                targetVersionCode = targetVersion,
                targetUpdateTime = targetUpdateTime,
                targetPackage = targetPackage
            )
        } else {
            NoRootSupportStore.recordDisabledAck(
                context = context,
                revision = revision,
                moduleVersionCode = moduleVersion,
                targetVersionCode = targetVersion,
                targetUpdateTime = targetUpdateTime,
                targetPackage = targetPackage
            )
        }
    }

}

private object HostAdmissionQueryContract {
    const val RESULT_CODE_HANDLED = 0x4841

    fun isValidNoncePair(nonce: String, request: android.os.Bundle): Boolean =
        com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostRuntimeDiagnosticsQueryContract.isValidNonce(nonce) &&
            request.getString("nonce") == nonce

    fun isValidResponseAction(packageName: String, action: String): Boolean =
        action.startsWith("$packageName.HOST_ADMISSION_RESPONSE.") && action.length <= 256
}
