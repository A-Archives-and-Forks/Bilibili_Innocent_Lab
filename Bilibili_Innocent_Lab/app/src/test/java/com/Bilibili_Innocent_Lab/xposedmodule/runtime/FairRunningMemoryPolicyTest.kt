package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FairRunningMemoryPolicyTest {

    @Test
    fun `intent action beats a conflicting bundle action`() {
        assertEquals(
            FairRunningMemoryKind.TRIM,
            FairRunningMemoryPolicy.kindFor(
                FairRunningMemoryContract.ACTION_TRIM,
                FairRunningMemoryContract.ACTION_VALUE_KILL
            )
        )
        assertEquals(
            FairRunningMemoryKind.KILL,
            FairRunningMemoryPolicy.kindFor(
                FairRunningMemoryContract.ACTION_KILL,
                FairRunningMemoryContract.ACTION_VALUE_TRIM
            )
        )
    }

    @Test
    fun `bundle action is only a fallback when the intent is unknown`() {
        assertEquals(
            FairRunningMemoryKind.TRIM,
            FairRunningMemoryPolicy.kindFor(null, FairRunningMemoryContract.ACTION_VALUE_TRIM)
        )
        assertEquals(
            FairRunningMemoryKind.KILL,
            FairRunningMemoryPolicy.kindFor("other.action", FairRunningMemoryContract.ACTION_VALUE_KILL)
        )
        assertNull(FairRunningMemoryPolicy.kindFor(null, null))
        assertNull(FairRunningMemoryPolicy.kindFor("android.intent.action.BOOT_COMPLETED", "save"))
    }

    @Test
    fun `trim and kill both release graphics but only kill asks to persist`() {
        assertTrue(FairRunningMemoryPolicy.shouldReleaseGraphics(FairRunningMemoryKind.TRIM))
        assertTrue(FairRunningMemoryPolicy.shouldReleaseGraphics(FairRunningMemoryKind.KILL))
        assertFalse(FairRunningMemoryPolicy.shouldPersist(FairRunningMemoryKind.TRIM))
        assertTrue(FairRunningMemoryPolicy.shouldPersist(FairRunningMemoryKind.KILL))
    }

    @Test
    fun `result codes match the published table`() {
        assertEquals(0, FairRunningMemoryPolicy.resultCode(true))
        assertEquals(1, FairRunningMemoryPolicy.resultCode(false))
        assertEquals(android.os.IBinder.FIRST_CALL_TRANSACTION, FairRunningMemoryContract.TRANSACTION_EXCEPTION_REPLY)
        assertEquals(3_000L, FairRunningMemoryContract.SYSTEM_DEADLINE_MS)
        assertTrue(FairRunningMemoryContract.WORK_BUDGET_MS < FairRunningMemoryContract.SYSTEM_DEADLINE_MS)
        assertTrue(FairRunningMemoryPolicy.receiverMustBeExported())
    }

    @Test
    fun `trim extras parse heap aliases and pss fields`() {
        val notice = FairRunningMemoryContract.fromFields(
            FairRunningMemoryContract.ACTION_TRIM,
            FairRunningMemoryFields(
                notifyType = FairRunningMemoryContract.NOTIFY_TYPE_HEAP,
                notifyId = 42,
                reason = "Excessive Java Heap Usage",
                action = FairRunningMemoryContract.ACTION_VALUE_TRIM,
                heapAllocKb = 12_000,
                heapCapacityKb = 24_000
            )
        )
        assertNotNull(notice)
        assertEquals(FairRunningMemoryKind.TRIM, notice!!.kind)
        assertEquals(FairRunningMemoryContract.NOTIFY_TYPE_HEAP, notice.notifyType)
        assertEquals(42, notice.notifyId)
        assertEquals(12_000, notice.heapSizeKb)
        assertEquals(24_000, notice.heapCapacityKb)
        assertNull(notice.callback)
    }

    @Test
    fun `heapSize wins over the sample-code heapAlloc alias`() {
        val notice = FairRunningMemoryContract.fromFields(
            FairRunningMemoryContract.ACTION_KILL,
            FairRunningMemoryFields(
                notifyType = FairRunningMemoryContract.NOTIFY_TYPE_PSS,
                notifyId = 7,
                action = FairRunningMemoryContract.ACTION_VALUE_KILL,
                heapSizeKb = 8,
                heapAllocKb = 99
            )
        )
        assertEquals(FairRunningMemoryKind.KILL, notice!!.kind)
        assertEquals(8, notice.heapSizeKb)
    }

    @Test
    fun `missing extra bundle is still a valid notice`() {
        val notice = FairRunningMemoryContract.fromFields(
            FairRunningMemoryContract.ACTION_TRIM,
            FairRunningMemoryFields(notifyId = 1)
        )
        assertNotNull(notice)
        assertNull(notice!!.pssKb)
        assertNull(notice.heapSizeKb)
    }

    @Test
    fun `root extras without common are ignored`() {
        assertNull(FairRunningMemoryContract.parse(FairRunningMemoryContract.ACTION_TRIM, extras = null))
        assertNull(
            FairRunningMemoryContract.fromFields(
                intentAction = "android.intent.action.BOOT_COMPLETED",
                fields = FairRunningMemoryFields(notifyId = 1)
            )
        )
    }
}
