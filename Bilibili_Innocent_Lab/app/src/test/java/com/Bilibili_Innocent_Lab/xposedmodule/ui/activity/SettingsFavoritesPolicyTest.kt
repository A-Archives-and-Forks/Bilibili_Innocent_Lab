package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class SettingsFavoritesPolicyTest {
    private fun decode(ids: String) = SettingsFavoritesPolicy.decode(mapOf("schema" to 1, "ids" to ids))

    @Test fun freshStateCanBeEditedAndRoundTripsInUserOrder() {
        val empty = SettingsFavoritesPolicy.decode(emptyMap<String, Any>())
        assertTrue(empty.canWrite)
        val first = SettingsFavoritesPolicy.add(empty, "player.speed.enabled")!!
        val second = SettingsFavoritesPolicy.add(first, "home.ad.hidden")!!
        assertEquals(listOf("player.speed.enabled", "home.ad.hidden"), second.ids)
        assertEquals(second, decode(SettingsFavoritesPolicy.encode(second)!!))
    }

    @Test fun duplicateEntriesKeepFirstPositionAndAddingExistingDoesNotReorder() {
        val state = decode("feature.b\nfeature.a\nfeature.b")
        assertEquals(listOf("feature.b", "feature.a"), state.ids)
        assertEquals(state, SettingsFavoritesPolicy.add(state, "feature.b"))
        assertEquals("feature.b\nfeature.a", SettingsFavoritesPolicy.encode(state))
    }

    @Test fun moveAndRemovePreserveOtherEntriesAndRejectInvalidPositions() {
        val original = decode("a\nb\nc")
        val moved = SettingsFavoritesPolicy.move(original, "a", 2)!!
        assertEquals(listOf("b", "c", "a"), moved.ids)
        assertEquals(original, SettingsFavoritesPolicy.move(moved, "a", 0))
        assertEquals(listOf("b", "a"), SettingsFavoritesPolicy.remove(moved, "c")!!.ids)
        assertEquals(original, SettingsFavoritesPolicy.remove(original, "missing"))
        assertNull(SettingsFavoritesPolicy.move(original, "a", -1))
        assertNull(SettingsFavoritesPolicy.move(original, "a", 3))
        assertNull(SettingsFavoritesPolicy.move(original, "missing", 0))
        assertEquals(listOf("a", "b", "c"), original.ids)
    }

    @Test fun unavailableIdsAreOnlyHiddenFromDisplayAndSurviveEditsAndRoundTrip() {
        val original = decode("future.feature\navailable.one\nremoved.feature\navailable.two")
        assertEquals(listOf("available.one", "available.two"),
            SettingsFavoritesPolicy.visibleIds(original, setOf("available.one", "available.two")))
        assertEquals(emptyList<String>(), SettingsFavoritesPolicy.visibleIds(original, emptySet()))
        val added = SettingsFavoritesPolicy.add(original, "available.three")!!
        assertEquals(original.ids + "available.three", decode(SettingsFavoritesPolicy.encode(added)!!).ids)
        val moved = SettingsFavoritesPolicy.move(added, "available.two", 1)!!
        assertEquals(listOf("future.feature", "available.two", "available.one", "removed.feature", "available.three"), moved.ids)
    }

    @Test fun malformedAndFutureDataNeverBecomeWritableEmptyStates() {
        val malformed = listOf(
            mapOf("schema" to 1), mapOf("ids" to "a"), mapOf("schema" to "1", "ids" to "a"),
            mapOf("schema" to 1, "ids" to setOf("a")), mapOf("schema" to 1, "ids" to "a\n"),
            mapOf("schema" to 1, "ids" to "a\n\nb"), mapOf("schema" to 1, "ids" to "a b"),
            mapOf("schema" to 1, "ids" to "a\rb"), mapOf("schema" to 1, "ids" to ".a")
        )
        malformed.forEach { values ->
            val state = SettingsFavoritesPolicy.decode(values)
            assertEquals(SettingsFavoritesStatus.CORRUPT, state.status)
            assertFalse(state.canWrite)
            assertNull(SettingsFavoritesPolicy.add(state, "a"))
            assertNull(SettingsFavoritesPolicy.remove(state, "a"))
            assertNull(SettingsFavoritesPolicy.move(state, "a", 0))
            assertNull(SettingsFavoritesPolicy.encode(state))
        }
        for (version in listOf(-1, 0, 2, Int.MAX_VALUE)) {
            val raw = mapOf("schema" to version, "ids" to "future.feature")
            val state = SettingsFavoritesPolicy.decode(raw)
            assertEquals(SettingsFavoritesStatus.UNSUPPORTED, state.status)
            assertNull(SettingsFavoritesPolicy.add(state, "a"))
            assertEquals("future.feature", raw["ids"])
        }
    }

    @Test fun capacityAndIdBoundsRejectDataWithoutTruncatingSelections() {
        val full = decode((0 until SettingsFavoritesPolicy.MAX_ITEMS).joinToString("\n") { "feature.$it" })
        assertEquals(SettingsFavoritesPolicy.MAX_ITEMS, full.ids.size)
        assertNull(SettingsFavoritesPolicy.add(full, "one.more"))
        assertEquals(full, SettingsFavoritesPolicy.add(full, "feature.0"))
        assertFalse(decode(full.ids.joinToString("\n") + "\nextra").canWrite)
        assertFalse(decode("x".repeat(SettingsFavoritesPolicy.MAX_ID_LENGTH + 1)).canWrite)
        assertTrue(decode("x".repeat(SettingsFavoritesPolicy.MAX_ID_LENGTH)).canWrite)
        assertNull(SettingsFavoritesPolicy.add(SettingsFavoritesState(), ""))
        assertNull(SettingsFavoritesPolicy.add(SettingsFavoritesState(), "injected\nid"))
        assertNull(SettingsFavoritesPolicy.add(SettingsFavoritesState(status = SettingsFavoritesStatus.UNAVAILABLE), "a"))
    }
}
