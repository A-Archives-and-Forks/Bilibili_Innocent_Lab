package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.os.Bundle
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AdmissionReplayBookTest {
    @Test fun sameKeyComputesOnceWhenFourTransportsRace() {
        var now = 0L
        val book = AdmissionReplayBook(clock = { now })
        val computes = AtomicInteger()
        val entered = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        val task = Callable {
            book.getOrPut("nonce", "prepare_admission") {
                computes.incrementAndGet()
                entered.countDown()
                Thread.sleep(40)
                Bundle()
            }
        }
        val futures = List(4) { pool.submit(task) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertTrue(futures.all { it.get(2, TimeUnit.SECONDS) != null })
        pool.shutdownNow()
        assertEquals(1, computes.get())
        now = AdmissionReplayBook.TTL_MS + 1
        val secondWave = AtomicInteger()
        assertNotNull(
            book.getOrPut("nonce", "prepare_admission") {
                secondWave.incrementAndGet()
                Bundle()
            }
        )
        assertEquals(1, secondWave.get())
    }

    @Test fun differentKeysDoNotHoldEachOtherOutOfCompute() {
        val book = AdmissionReplayBook(clock = { 0L })
        val aStarted = CountDownLatch(1)
        val bStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val a = pool.submit(Callable {
            book.getOrPut("a", "prepare_admission") {
                aStarted.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                Bundle()
            }
        })
        val b = pool.submit(Callable {
            book.getOrPut("b", "prepare_admission") {
                bStarted.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                Bundle()
            }
        })
        assertTrue(aStarted.await(2, TimeUnit.SECONDS))
        assertTrue(bStarted.await(2, TimeUnit.SECONDS))
        release.countDown()
        assertNotNull(a.get(2, TimeUnit.SECONDS))
        assertNotNull(b.get(2, TimeUnit.SECONDS))
        pool.shutdownNow()
    }

    @Test fun nullComputeIsNotCached() {
        val book = AdmissionReplayBook(clock = { 0L })
        val computes = AtomicInteger()
        assertNull(book.getOrPut("n", "prepare_admission") {
            computes.incrementAndGet()
            null
        })
        assertNotNull(book.getOrPut("n", "prepare_admission") {
            computes.incrementAndGet()
            Bundle()
        })
        assertEquals(2, computes.get())
    }
}
