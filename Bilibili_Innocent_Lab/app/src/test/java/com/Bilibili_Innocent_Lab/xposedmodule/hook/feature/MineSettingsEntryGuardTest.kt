package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MineSettingsEntryGuardTest {

    @Test fun onlyTheHostSettingsRouteIsProtected() {
        assertTrue(MineSettingsEntryGuard.isSettingsEntry("activity://main/preference"))
        assertTrue(MineSettingsEntryGuard.isSettingsEntry(" activity://main/preference?from=mine "))
        assertTrue(MineSettingsEntryGuard.isSettingsEntry("activity://main/preference#x"))
        assertFalse(MineSettingsEntryGuard.isSettingsEntry("activity://main/preference-other"))
        assertFalse(MineSettingsEntryGuard.isSettingsEntry("bilibili://main/scan"))
        assertFalse(MineSettingsEntryGuard.isSettingsEntry(""))
        assertFalse(MineSettingsEntryGuard.isSettingsEntry(null))
    }

    /** 整组隐藏但组里有「设置」：只留「设置」，而不是把整组连同设置一起删掉。 */
    @Test fun hiddenGroupKeepsOnlyTheSettingsEntry() {
        val items = listOf("bilibili://main/scan", "activity://main/preference", "bilibili://history")
        assertEquals(listOf("activity://main/preference"), MineSettingsEntryGuard.settingsOnly(items) { it as String })
    }

    @Test fun groupWithoutSettingsIsHiddenAsBefore() {
        assertNull(MineSettingsEntryGuard.settingsOnly(listOf("bilibili://history", null)) { it as String? })
        assertNull(MineSettingsEntryGuard.settingsOnly(emptyList<Any>()) { it as String? })
    }
}
