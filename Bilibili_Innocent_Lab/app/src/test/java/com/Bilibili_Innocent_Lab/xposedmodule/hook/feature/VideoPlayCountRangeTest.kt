package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayCountRangeTest {

    @Test
    fun `empty range is disabled and unknown play count is always preserved`() {
        val range = VideoPlayCountRange(minimum = 0, maximum = 0)

        assertFalse(range.isEnabled)
        assertFalse(range.shouldRemove(null))
        assertFalse(range.shouldRemove(0))
        assertFalse(range.shouldRemove(-1))
        assertFalse(range.shouldRemove(1))
    }

    @Test
    fun `minimum and maximum are exclusive removal boundaries`() {
        val range = VideoPlayCountRange(minimum = 10_000, maximum = 1_000_000)

        assertTrue(range.isEnabled)
        assertTrue(range.shouldRemove(9_999))
        assertFalse(range.shouldRemove(10_000))
        assertFalse(range.shouldRemove(1_000_000))
        assertTrue(range.shouldRemove(1_000_001))
    }

    @Test
    fun `either boundary can be omitted`() {
        val minimumOnly = VideoPlayCountRange(minimum = 10_000, maximum = 0)
        val maximumOnly = VideoPlayCountRange(minimum = 0, maximum = 1_000_000)

        assertTrue(minimumOnly.shouldRemove(9_999))
        assertFalse(minimumOnly.shouldRemove(Long.MAX_VALUE))
        assertFalse(maximumOnly.shouldRemove(1))
        assertTrue(maximumOnly.shouldRemove(1_000_001))
    }

    @Test
    fun `negative or reversed configuration fails open`() {
        listOf(
            VideoPlayCountRange(minimum = -1, maximum = 1_000),
            VideoPlayCountRange(minimum = 10, maximum = -1),
            VideoPlayCountRange(minimum = 2, maximum = 1)
        ).forEach { range ->
            assertFalse(range.isValid)
            assertFalse(range.isEnabled)
            assertFalse(range.shouldRemove(1))
            assertFalse(range.shouldRemove(10_000))
        }
    }

    @Test
    fun `cover text parser accepts wan yi and play suffixes`() {
        assertEquals(1_230L, VideoPlayCountReader.parseCoverText("1,230"))
        assertEquals(12_000L, VideoPlayCountReader.parseCoverText("1.2万播放"))
        assertEquals(12_300L, VideoPlayCountReader.parseCoverText("1.23万"))
        assertEquals(100_000_000L, VideoPlayCountReader.parseCoverText("1亿观看"))
        assertEquals(10_000L, VideoPlayCountReader.parseCoverText("1萬次"))
        assertNull(VideoPlayCountReader.parseCoverText(""))
        assertNull(VideoPlayCountReader.parseCoverText("刚刚"))
        assertNull(VideoPlayCountReader.parseCoverText("播放"))
        assertNull(VideoPlayCountReader.parseCoverText("1.2万弹幕"))
        assertNull(VideoPlayCountReader.parseCoverText("0"))
        assertNull(VideoPlayCountReader.parseCoverText("-1"))
    }

    @Test
    fun `detail reader walks the four-step stat chain and skips broken receivers`() {
        val itemGetter = RelateCard::class.java.getMethod("getAv")
        val statGetter = AvCard::class.java.getMethod("getStat")
        val vtGetter = Stat::class.java.getMethod("getVt")
        val valueGetter = StatInfo::class.java.getMethod("getValue")
        val paths = VideoPlayCountReader.buildMethodPaths(
            listOf(listOf(itemGetter, statGetter, vtGetter, valueGetter))
        )

        assertEquals(
            45_000L,
            VideoPlayCountReader.fromMethods(RelateCard(AvCard(Stat(StatInfo(45_000L)))), paths)
        )
        assertNull(VideoPlayCountReader.fromMethods(RelateCard(null), paths))
        assertNull(
            VideoPlayCountReader.fromMethods(RelateCard(AvCard(Stat(StatInfo(0L)))), paths)
        )
    }

    @Test
    fun `home cover reader requires a vod aid and ignores live viewer counts`() {
        val getter = HomeItem::class.java.getMethod("getPlayerArgs")

        assertEquals(
            12_000L,
            VideoPlayCountReader.fromHomeCover(
                HomeItem(PlayerArgs(aid = 42L), cover = "1.2万观看"),
                getter
            )
        )
        assertNull(
            VideoPlayCountReader.fromHomeCover(
                HomeItem(PlayerArgs(aid = 0L), cover = "1.2万观看"),
                getter
            )
        )
        assertNull(
            VideoPlayCountReader.fromHomeCover(
                HomeItem(PlayerArgs(aid = 42L, isLive = 1), cover = "1.2万观看"),
                getter
            )
        )
        assertNull(
            VideoPlayCountReader.fromHomeCover(
                HomeItem(PlayerArgs(aid = 42L, roomId = 9L), cover = "1.2万观看"),
                getter
            )
        )
        val booleanLiveGetter = BooleanLiveItem::class.java.getMethod("getPlayerArgs")
        assertNull(
            VideoPlayCountReader.fromHomeCover(
                BooleanLiveItem(BooleanLiveArgs(aid = 42L, isLive = true), cover = "1.2万观看"),
                booleanLiveGetter
            )
        )
        assertNull(
            VideoPlayCountReader.fromHomeCover(
                HomeItem(PlayerArgs(aid = 42L), cover = "刚刚"),
                getter
            )
        )
    }

    private class HomeItem(
        private val playerArgs: PlayerArgs,
        private val cover: String
    ) {
        fun getPlayerArgs(): PlayerArgs = playerArgs
        fun getCoverLeftText1(): String = cover
    }

    private class PlayerArgs(
        @JvmField val aid: Long,
        @JvmField val isLive: Int = 0,
        @JvmField val roomId: Long = 0L
    )

    private class BooleanLiveItem(
        private val playerArgs: BooleanLiveArgs,
        private val cover: String
    ) {
        fun getPlayerArgs(): BooleanLiveArgs = playerArgs
        fun getCoverLeftText1(): String = cover
    }

    private class BooleanLiveArgs(
        @JvmField val aid: Long,
        @JvmField val isLive: Boolean
    )

    private class RelateCard(private val av: AvCard?) {
        fun getAv(): AvCard? = av
    }

    private class AvCard(private val stat: Stat) {
        fun getStat(): Stat = stat
    }

    private class Stat(private val vt: StatInfo) {
        fun getVt(): StatInfo = vt
    }

    private class StatInfo(private val value: Long) {
        fun getValue(): Long = value
    }
}
