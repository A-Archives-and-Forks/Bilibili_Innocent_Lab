package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import org.junit.Assert.*
import org.junit.Test

class RecommendationSettingsPlacementTest {
    @Test fun feedbackAndRulesAreGroupedInEnhancementBrowsing() {
        val enhance = SettingsUiSource.function("enhanceBrowsingCategory")
        val purify = SettingsUiSource.function("purifyHomeCategory")
        val labels = listOf("home_recommend_section_pick", "home_recommend_blocked_tids",
            "home_recommend_blocked_authors", "recommendation_blocklist_manage")
        val positions = labels.map { label ->
            val position = enhance.indexOf("R.string.$label")
            assertTrue(label, position >= 0)
            assertFalse(label, purify.contains("R.string.$label"))
            position
        }
        assertEquals(positions.sorted(), positions)
        assertTrue(enhance.contains("showRecommendationBlocklistDialog(anchor = it)"))
    }

    /**
     * 「管理推荐屏蔽」必须**主动向宿主拉取**两个点选观测面。
     *
     * `MineComponentSnapshotStore` 只在 `MineComponentSnapshotQueryClient.query` 校验成功后
     * 才被写入。2026-09-15 之前 `query` 的唯一调用点是四个列表面的勾选面板，
     * `section_picks` / `author_picks` 从来没人查过：宿主侧 `payload_section_picks` 有值、
     * 模块侧对应的 key 根本不存在，于是"反馈面板里点了，管理推荐屏蔽里找不到"——
     * 全程无异常、无日志、状态通道一路 success。
     *
     * 这条钉住"打开面板 ⇒ 先查询"这个因果，退回只读本地存储会立刻红。
     */
    @Test fun theBlocklistDialogPullsBothPickSurfacesFromTheHost() {
        val open = SettingsUiSource.function("showRecommendationBlocklistDialog")
        assertTrue("必须主动查询，不能只读本地存储",
            open.contains("MineComponentSnapshotQueryClient.query("))
        listOf("SURFACE_SECTION_PICKS", "SURFACE_AUTHOR_PICKS").forEach { surface ->
            assertTrue(surface, SettingsUiSource.file("RecommendationBlocklistDialogs")
                .contains("MineComponentSnapshotCodec.$surface"))
        }
        // 查询失败不能把面板挡住：已保存的名单仍要能管理。
        assertTrue("查询失败要退回本地快照",
            open.contains("MineComponentSnapshotStore.read("))
    }

    @Test fun manualEditorsDoNotAutomaticallyReimportHostSelections() {
        val enhance = SettingsUiSource.function("enhanceBrowsingCategory")
        assertFalse(enhance.contains("prefilledBlocked"))
        assertTrue(enhance.contains("prefs().getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS"))
        assertTrue(enhance.contains("prefs().getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS"))
        assertFalse(Regex("putString\\(\\s*FeaturePreferences.HOME_RECOMMEND_BLOCKED_\\w+,\\s*recommendationRuleCount").containsMatchIn(enhance))
    }
}
