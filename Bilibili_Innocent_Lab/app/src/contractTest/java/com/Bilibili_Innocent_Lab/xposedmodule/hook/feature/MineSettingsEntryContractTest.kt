package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「我的」页三条过滤路径（数据层剪枝 / getItemList 返回值 / 8.84–8.91 旧字段）都必须先判「设置」入口。
 *
 * 漏掉任意一条，用户隐藏「设置」且其余分组被清空时，宿主会对空分组列表取第 -1 项而崩溃
 * （2026-09-21 用户诊断包：`MineUserCenterViewModelV2$1$1` IndexOutOfBoundsException）。
 */
class MineSettingsEntryContractTest {
    private val source = SourceContract.read("hook/feature/MineComponentFilterFeatureInstaller.kt")

    @Test fun everyItemFilterPathChecksTheSettingsEntryBeforeHiding() {
        val itemMatches = Regex("""matchesHidden\("item"""").findAll(source).count()
        val guarded = Regex("""!protected && matchesHidden\("item"""").findAll(source).count()
        assertEquals("每个 item 判定都要先排除「设置」", itemMatches, guarded)
        assertEquals(3, guarded)
    }

    @Test fun protectedEntriesAreNotOfferedInThePicker() {
        assertEquals(3, Regex("""selectable = !protected""").findAll(source).count())
    }

    @Test fun hiddenGroupsKeepTheSettingsEntry() {
        assertTrue(source.contains("MineSettingsEntryGuard.settingsOnly("))
    }
}
