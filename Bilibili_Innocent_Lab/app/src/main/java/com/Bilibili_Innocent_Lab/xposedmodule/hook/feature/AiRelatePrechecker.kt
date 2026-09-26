package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 强力模式 · 获取 access_key：详情页首屏推荐的后台限速预检。
 *
 * 推荐卡本身不带创作声明（抓包 5 份 View 响应 + `RelateCard` 全字段树核对），只能逐个查详情。
 * 每打开一个详情页，取首屏推荐里**尚未确认过**的前 [PER_PAGE] 个视频，单线程每 [SPACING_MS] 查一个；
 * 新详情页会作废旧队列（只为当前页服务）；滚动窗口内总量不超过 [BUDGET] 次，压住风控面。
 * 查到声明就记进 [AiDeclaredVideoRegistry]：已显示的卡不会立刻消失，但之后的推荐、「加载更多」、
 * 其他详情页与首页都会提前删掉，点进去也会被拦截补位。
 *
 * 请求走宿主自己的 `ViewMoss`（宿主网络层附带账号凭证），`spmid` 用宿主历史页后台刷新的
 * `main.my-history.recommend.0`：服务端看来是宿主本来就有的后台请求，不会被当成"打开了这个视频"的兴趣信号；
 * 模块自己的 View 拦截器也按被动请求处理（只记录、不改写）。查询线程另有线程标记兜底。
 */
internal class AiRelatePrechecker(
    private val query: (Long) -> Boolean?,
    private val ready: () -> Boolean,
    private val onDeclared: (Long) -> Unit,
    private val budget: PrecheckBudget = PrecheckBudget(),
    private val clock: () -> Long = System::currentTimeMillis,
    executorFactory: () -> ScheduledExecutorService = ::defaultExecutor
) {
    private val executor by lazy(executorFactory)
    private val generation = AtomicLong()
    private val checked = BoundedAidSet(CHECKED_CAPACITY)

    /** 新详情页：作废旧队列，排入本页待查的 aid。 */
    fun submit(candidates: List<Long>) {
        val current = generation.incrementAndGet()
        val plan = select(candidates, AiDeclaredVideoRegistry::contains, checked::contains)
        if (plan.isEmpty()) return
        runCatching { schedule(current, plan, 0, INITIAL_DELAY_MS) }
    }

    private fun schedule(session: Long, plan: List<Long>, index: Int, delayMs: Long) {
        executor.schedule(HostThreadGuard.runnable("ai_declared.precheck") {
            if (generation.get() != session || index >= plan.size) return@runnable
            val aid = plan[index]
            val next = { schedule(session, plan, index + 1, SPACING_MS) }
            if (AiDeclaredVideoRegistry.contains(aid) || checked.contains(aid)) {
                next(); return@runnable
            }
            if (!ready() || !budget.tryAcquire(clock())) return@runnable
            when (queryOnThisThread(aid)) {
                true -> onDeclared(aid)
                false -> checked.add(aid)
                null -> Unit // 失败不记负缓存，下次再查
            }
            next()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun queryOnThisThread(aid: Long): Boolean? {
        QUERY_THREAD.set(true)
        return try {
            query(aid)
        } finally {
            QUERY_THREAD.remove()
        }
    }

    companion object {
        const val PER_PAGE = 10
        const val SPACING_MS = 1_000L
        const val INITIAL_DELAY_MS = 1_500L
        const val BUDGET = 60
        const val BUDGET_WINDOW_MS = 10 * 60_000L
        const val CHECKED_CAPACITY = 1024
        const val PRECHECK_SPMID = "main.my-history.recommend.0"

        private val QUERY_THREAD = ThreadLocal<Boolean>()

        /** 当前线程是不是预检查询线程；View 拦截器据此按被动请求处理。 */
        fun isQueryThread(): Boolean = QUERY_THREAD.get() == true

        /** 去重、去掉已知 AI 与已查过的，保持推荐顺序取前 [limit] 个。 */
        fun select(
            candidates: List<Long>,
            known: (Long) -> Boolean,
            checked: (Long) -> Boolean,
            limit: Int = PER_PAGE
        ): List<Long> = candidates.asSequence()
            .filter { it > 0 }
            .distinct()
            .filterNot(known)
            .filterNot(checked)
            .take(limit)
            .toList()

        private fun defaultExecutor(): ScheduledExecutorService =
            ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "BIL-AiPrecheck").apply { isDaemon = true }
            }.apply {
                setRemoveOnCancelPolicy(true)
                executeExistingDelayedTasksAfterShutdownPolicy = false
            }
    }
}

/** 滚动窗口额度：窗口内最多 [max] 次。 */
internal class PrecheckBudget(
    private val max: Int = AiRelatePrechecker.BUDGET,
    private val windowMs: Long = AiRelatePrechecker.BUDGET_WINDOW_MS
) {
    private val stamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) stamps.removeFirst()
        if (stamps.size >= max) return false
        stamps.addLast(now)
        return true
    }
}

/** 有界 LRU 集合（查过且无声明的 aid）。 */
internal class BoundedAidSet(private val capacity: Int) {
    private val map = object : LinkedHashMap<Long, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Unit>?) = size > capacity
    }

    /** 用 get 而不是 containsKey：命中要刷新访问顺序，才是真正的 LRU。 */
    @Synchronized fun contains(aid: Long): Boolean = map[aid] != null

    @Synchronized fun add(aid: Long) {
        map[aid] = Unit
    }
}

/**
 * 借宿主 `viewunite.v1.ViewMoss#executeView` 查单个视频是否带声明（27 版：`ViewMoss()` 无参构造、
 * `ViewReq.newBuilder()`、Builder `setAid(long)`/`setSpmid(String)` 全在；`build()` 继承自 protobuf 基类）。
 * 同步调用，只能在预检线程上跑。
 */
internal class AiViewQuery private constructor(
    private val mossConstructor: Constructor<*>,
    private val execute: Method,
    private val newBuilder: Method,
    private val setAid: Method,
    private val setSpmid: Method,
    private val build: Method,
    private val declared: (Any) -> Boolean
) {
    /** @return 是否带声明；请求或解析失败返回 null。 */
    fun query(aid: Long): Boolean? = runCatching {
        val builder = checkNotNull(newBuilder.invoke(null))
        setAid.invoke(builder, aid)
        setSpmid.invoke(builder, AiRelatePrechecker.PRECHECK_SPMID)
        val request = checkNotNull(build.invoke(builder))
        val reply = execute.invoke(mossConstructor.newInstance(), request) ?: return@runCatching null
        declared(reply)
    }.getOrNull()

    companion object {
        fun resolve(loader: ClassLoader, declared: (Any) -> Boolean): AiViewQuery? = runCatching {
            val moss = KavaMemberLookup.classOrNull(loader, DetailUnitedModulePurifyPolicy.MOSS_CLASS) ?: return null
            val request = KavaMemberLookup.classOrNull(loader, DetailUnitedModulePurifyPolicy.REQUEST_CLASS) ?: return null
            val ctor = KavaMemberLookup.constructorOrNull(moss) ?: return null
            val execute = KavaMemberLookup.methodOrNull(moss, DetailUnitedModulePurifyPolicy.SYNC_METHOD, request)
                ?.takeIf { !it.isStatic } ?: return null
            val newBuilder = KavaMemberLookup.methodOrNull(request, "newBuilder")
                ?.takeIf { it.isStatic && it.parameterCount == 0 } ?: return null
            val builderClass = newBuilder.returnType
            AiViewQuery(
                mossConstructor = ctor,
                execute = execute,
                newBuilder = newBuilder,
                setAid = KavaMemberLookup.methodOrNull(builderClass, "setAid", classOf<Long>()) ?: return null,
                setSpmid = KavaMemberLookup.methodOrNull(builderClass, "setSpmid", classOf<String>()) ?: return null,
                build = KavaMemberLookup.inheritedMethodOrNull(builderClass, "build")
                    ?.takeIf { !it.isStatic && it.parameterCount == 0 } ?: return null,
                declared = declared
            )
        }.getOrNull()
    }
}
