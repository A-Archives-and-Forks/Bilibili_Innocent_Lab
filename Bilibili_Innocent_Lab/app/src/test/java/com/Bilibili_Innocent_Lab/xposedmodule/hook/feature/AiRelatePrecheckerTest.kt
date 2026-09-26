package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.AbstractExecutorService

class AiRelatePrecheckerTest {

    /** 手动推进的调度器：记下延迟，由测试逐个执行。 */
    private class ManualScheduler : AbstractExecutorService(), ScheduledExecutorService {
        val pending = ArrayDeque<Pair<Long, Runnable>>()
        fun runNext(): Long? = pending.removeFirstOrNull()?.let { (delay, task) -> task.run(); delay }
        fun drain(): List<Long> = generateSequence { runNext() }.toList()

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            pending.addLast(unit.toMillis(delay) to command)
            return NoopFuture
        }
        override fun <V : Any?> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
            throw UnsupportedOperationException()
        override fun scheduleAtFixedRate(c: Runnable, i: Long, p: Long, u: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()
        override fun scheduleWithFixedDelay(c: Runnable, i: Long, d: Long, u: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true

        private object NoopFuture : ScheduledFuture<Any?> {
            override fun compareTo(other: Delayed?) = 0
            override fun getDelay(unit: TimeUnit) = 0L
            override fun cancel(mayInterruptIfRunning: Boolean) = false
            override fun isCancelled() = false
            override fun isDone() = true
            override fun get(): Any? = null
            override fun get(timeout: Long, unit: TimeUnit): Any? = null
        }
    }

    private val threadFlags = mutableListOf<Boolean>()

    private fun prechecker(
        scheduler: ManualScheduler,
        answers: Map<Long, Boolean?>,
        queried: MutableList<Long>,
        declared: MutableList<Long>,
        ready: () -> Boolean = { true },
        budget: PrecheckBudget = PrecheckBudget()
    ) = AiRelatePrechecker(
        query = { aid ->
            // 查询期间线程标记必须为真，View 拦截器据此按被动请求处理。
            // （断言不能写在回调里：会被 HostThreadGuard 吞掉，先记下来）
            threadFlags += AiRelatePrechecker.isQueryThread()
            queried += aid
            answers[aid]
        },
        ready = ready,
        onDeclared = { declared += it },
        budget = budget,
        clock = { 0L },
        executorFactory = { scheduler }
    )

    @Test
    fun `selection keeps order, drops duplicates, known and checked aids and caps the page`() {
        val picked = AiRelatePrechecker.select(
            listOf(5, 1, 0, -3, 2, 1, 3, 4) .map(Int::toLong),
            known = { it == 2L },
            checked = { it == 3L },
            limit = 3
        )
        assertEquals(listOf(5L, 1L, 4L), picked)
    }

    @Test
    fun `queries run one by one with spacing and record declared videos`() {
        val scheduler = ManualScheduler()
        val queried = mutableListOf<Long>(); val declared = mutableListOf<Long>()
        prechecker(scheduler, mapOf(900001L to true, 900002L to false), queried, declared)
            .submit(listOf(900001L, 900002L))
        val delays = scheduler.drain()
        assertEquals(listOf(900001L, 900002L), queried)
        assertEquals(listOf(900001L), declared)
        assertEquals(AiRelatePrechecker.INITIAL_DELAY_MS, delays.first())
        assertTrue(delays.drop(1).all { it == AiRelatePrechecker.SPACING_MS })
        assertEquals(listOf(true, true), threadFlags)
        assertFalse(AiRelatePrechecker.isQueryThread())
    }

    @Test
    fun `a newer page supersedes the old queue`() {
        val scheduler = ManualScheduler()
        val queried = mutableListOf<Long>()
        val checker = prechecker(scheduler, emptyMap(), queried, mutableListOf())
        checker.submit(listOf(910001L, 910002L))
        checker.submit(listOf(920001L))
        scheduler.drain()
        assertEquals(listOf(920001L), queried)
    }

    @Test
    fun `nothing is queried without a ready account`() {
        val scheduler = ManualScheduler()
        val queried = mutableListOf<Long>()
        prechecker(scheduler, emptyMap(), queried, mutableListOf(), ready = { false }).submit(listOf(930001L))
        scheduler.drain()
        assertTrue(queried.isEmpty())
    }

    @Test
    fun `videos confirmed clean are not queried again while failures are retried`() {
        val scheduler = ManualScheduler()
        val queried = mutableListOf<Long>()
        val checker = prechecker(scheduler, mapOf(940001L to false, 940002L to null), queried, mutableListOf())
        checker.submit(listOf(940001L, 940002L)); scheduler.drain()
        checker.submit(listOf(940001L, 940002L)); scheduler.drain()
        assertEquals(listOf(940001L, 940002L, 940002L), queried)
    }

    @Test
    fun `budget caps queries inside the rolling window`() {
        val budget = PrecheckBudget(max = 2, windowMs = 1_000L)
        assertTrue(budget.tryAcquire(0)); assertTrue(budget.tryAcquire(10))
        assertFalse(budget.tryAcquire(500))
        assertTrue(budget.tryAcquire(1_001))
    }

    @Test
    fun `bounded aid set evicts the least recently used entry`() {
        val set = BoundedAidSet(2)
        set.add(1); set.add(2); set.contains(1); set.add(3)
        assertTrue(set.contains(1)); assertFalse(set.contains(2)); assertTrue(set.contains(3))
    }
}
