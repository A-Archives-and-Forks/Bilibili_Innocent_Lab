package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.R
import org.junit.Assert.assertEquals
import org.junit.Test

class AiStrongModeSummaryTest {
    @Test
    fun `summary lists the two items in panel order`() {
        assertEquals(
            listOf(R.string.ai_declared_block_author, R.string.ai_declared_precheck),
            aiStrongModeActiveItems(blockAuthor = true, precheck = true, authorized = true)
        )
        assertEquals(emptyList<Int>(), aiStrongModeActiveItems(blockAuthor = false, precheck = false, authorized = true))
    }

    /** 与宿主侧 effectivePrecheck 一致：没授权时「获取 access_key」不算开着；屏蔽发布者不看授权。 */
    @Test
    fun `precheck only counts with the authorization while author blocking never needs it`() {
        assertEquals(emptyList<Int>(), aiStrongModeActiveItems(blockAuthor = false, precheck = true, authorized = false))
        assertEquals(
            listOf(R.string.ai_declared_block_author),
            aiStrongModeActiveItems(blockAuthor = true, precheck = true, authorized = false)
        )
    }
}
