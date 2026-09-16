package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import java.util.concurrent.CompletableFuture

/**
 * 同一 nonce+method 的启动授权广播可能被四条运输同时投递。
 * 按 key 单飞：命中缓存直接复制；同 key 并发共用一次 compute，避免两次
 * PREPARE 产出两个 challenge。不同 key 不互相阻塞，计算不拿缓存锁。
 */
internal class AdmissionReplayBook(
    private val clock: () -> Long,
    private val ttlMs: Long = TTL_MS,
    private val limit: Int = LIMIT,
) {
    private val cache = LinkedHashMap<String, Replay>(limit, 0.75f, true)
    private val inflight = HashMap<String, CompletableFuture<Bundle?>>()

    fun getOrPut(nonce: String, method: String, compute: () -> Bundle?): Bundle? {
        val key = "$nonce|$method"
        val future: CompletableFuture<Bundle?>
        val owner: Boolean
        synchronized(cache) {
            prune(clock())
        cache[key]?.response?.let { return snapshot(it) }
            val pending = inflight[key]
            if (pending != null) {
                future = pending
                owner = false
            } else {
                future = CompletableFuture()
                inflight[key] = future
                owner = true
            }
        }
        if (!owner) {
            return runCatching { future.get() }.getOrNull()?.let(::snapshot)
        }
        val computed = try {
            compute()
        } catch (error: Throwable) {
            synchronized(cache) { inflight.remove(key) }
            future.complete(null)
            throw error
        }
        synchronized(cache) {
            inflight.remove(key)
            if (computed != null) {
                prune(clock())
                while (cache.size >= limit) {
                    cache.remove(cache.entries.firstOrNull()?.key ?: break)
                }
                cache[key] = Replay(clock(), snapshot(computed))
            }
        }
        future.complete(computed)
        return computed
    }

    private fun prune(now: Long) {
        cache.entries.removeIf { now - it.value.createdAt > ttlMs }
    }

    /** 真机复制一份，避免 ordered extras 改到缓存；JVM 桩没有 copy 构造器时共用原件。 */
    private fun snapshot(source: Bundle): Bundle = try {
        Bundle(source)
    } catch (_: RuntimeException) {
        source
    }

    private data class Replay(val createdAt: Long, val response: Bundle)

    companion object {
        const val TTL_MS = 5_000L
        const val LIMIT = 32
    }
}
