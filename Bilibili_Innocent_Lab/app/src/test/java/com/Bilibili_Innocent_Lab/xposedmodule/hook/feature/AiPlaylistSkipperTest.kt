package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlaylistSkipperTest {

    /** 8.89.0 的属性表顺序：attr 第 0 位、id 第 8 位（`MultiTypeMedia_JsonDescriptor.createProperties`）。 */
    private val keys = listOf("attr", "bv_id", "cnt_info", "coin", "copy_right", "cover", "duration",
        "fav_state", "id", "index")

    @Test fun slotsAreFoundByJsonKeyNameNotByPosition() {
        assertArrayEquals(intArrayOf(0, 8), AiPlaylistSkipper.slotsOf(keys))
        assertArrayEquals(intArrayOf(2, 0), AiPlaylistSkipper.slotsOf(listOf("id", null, "attr")))
        assertArrayEquals(intArrayOf(-1, -1), AiPlaylistSkipper.slotsOf(listOf("title")))
    }

    @Test fun knownAiItemIsMarkedInvalidInPlace() {
        val values = arrayOfNulls<Any>(keys.size).also { it[0] = 0; it[8] = 117184995006501L }
        val result = AiPlaylistSkipper.markKnownAi(values, 0, 8) { it == 117184995006501L }
        assertEquals(AiPlaylistSkipper.MarkResult(117184995006501L, true), result)
        assertEquals(1, values[0])
    }

    @Test fun otherAttrBitsAreKeptAndMissingAttrCountsAsZero() {
        val values = arrayOfNulls<Any>(keys.size).also { it[0] = 4; it[8] = 7L }
        assertTrue(AiPlaylistSkipper.markKnownAi(values, 0, 8) { true }.marked)
        assertEquals(5, values[0])
        val missing = arrayOfNulls<Any>(keys.size).also { it[8] = 7L }
        assertTrue(AiPlaylistSkipper.markKnownAi(missing, 0, 8) { true }.marked)
        assertEquals(1, missing[0])
    }

    @Test fun unknownOrAlreadyInvalidItemsAreOnlyRecorded() {
        val unknown = arrayOfNulls<Any>(keys.size).also { it[0] = 0; it[8] = 7L }
        assertEquals(AiPlaylistSkipper.MarkResult(7L, false), AiPlaylistSkipper.markKnownAi(unknown, 0, 8) { false })
        assertEquals(0, unknown[0])
        val invalid = arrayOfNulls<Any>(keys.size).also { it[0] = 1; it[8] = 7L }
        assertFalse(AiPlaylistSkipper.markKnownAi(invalid, 0, 8) { true }.marked)
        assertEquals(1, invalid[0])
    }

    @Test fun unreadableShapesFailOpen() {
        val values = arrayOfNulls<Any>(3)
        assertNull(AiPlaylistSkipper.markKnownAi(values, 0, 8) { true }.aid)
        assertNull(AiPlaylistSkipper.markKnownAi(arrayOf(0, "x"), 0, 1) { true }.aid)
        assertNull(AiPlaylistSkipper.markKnownAi(arrayOf(0, 0L), 0, 1) { true }.aid)
        // 读得到 aid 但缺 attr 格：只记归属，不标。
        assertEquals(AiPlaylistSkipper.MarkResult(9L, false), AiPlaylistSkipper.markKnownAi(arrayOf<Any?>(9L), -1, 0) { true })
    }
}
