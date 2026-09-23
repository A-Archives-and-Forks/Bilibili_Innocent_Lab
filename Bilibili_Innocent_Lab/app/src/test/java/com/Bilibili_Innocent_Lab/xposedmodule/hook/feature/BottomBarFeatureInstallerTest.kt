package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBarFeatureInstallerTest {

    @Test
    fun `never hides every bottom bar entry`() {
        assertTrue(BottomBarFeatureInstaller.canHide(total = 5, matched = 2))
        assertFalse(BottomBarFeatureInstaller.canHide(total = 5, matched = 0))
        assertFalse(BottomBarFeatureInstaller.canHide(total = 5, matched = 5))
    }

    @Test
    fun `frame values line up with the tabhost field order so both layers derive one key`() {
        // TabHost.h 的 String 字段按 dex 顺序是 名称 → 图标 → 选中图标 → id → tab_id → … → 路由。
        val shape = BottomBarFeatureInstaller.legacyShapeValues(
            name = "会员购",
            icon = "https://i0.hdslb.com/mall.png",
            iconSelected = "https://i0.hdslb.com/mall_s.png",
            id = "177",
            tabId = "mall",
            uri = "bilibili://mall/home"
        )
        assertEquals(
            "会员购" to "https://i0.hdslb.com/mall.png",
            BottomBarFeatureInstaller.shapeKeyParts(shape)
        )
        // 没有图标地址时退到路由；缺失值不占位。
        val noIcon = BottomBarFeatureInstaller.legacyShapeValues("动态", null, null, "2", null, "bilibili://following/home")
        assertEquals("动态" to "bilibili://following/home", BottomBarFeatureInstaller.shapeKeyParts(noIcon))
    }

    @Test
    fun `canonical route ignores the host's own normalization differences`() {
        val expected = "bilibili://main/home"
        listOf(
            "bilibili://main/home",
            "bilibili://main/home/",
            " BILIBILI://Main/home/?from=tab#x ",
        ).forEach { assertEquals(it, expected, BottomBarFeatureInstaller.canonicalRoute(it)) }
        // 旧框架 MainResourceManager.g 的两处改写要还原，两层才出同一个键。
        assertEquals(
            "bilibili://game_center/home",
            BottomBarFeatureInstaller.canonicalRoute("action://game_center/home/menu?from=bottom")
        )
        assertEquals("bilibili://link/im_home", BottomBarFeatureInstaller.canonicalRoute("action://link/home/menu"))
        // 图标、换肤资源与非 URI 不是路由。
        listOf("https://i0.hdslb.com/a.png", "http://x/y", "file:///data/skin/a.png", "首页", "", null)
            .forEach { assertNull(it, BottomBarFeatureInstaller.canonicalRoute(it)) }
    }

    @Test
    fun `tabhost route is the last route-shaped value so a showing bubble never wins`() {
        // dex 顺序：名称 → 图标 → 选中图标 → id → tab_id → 气泡路由(i) → 路由(k) → 换肤图标(file://)
        val values = listOf(
            "会员购",
            "https://i0.hdslb.com/mall.png",
            "https://i0.hdslb.com/mall_s.png",
            "177",
            "mall",
            "bilibili://mall/activity/618",
            "bilibili://mall/home",
            "file:///data/garb/tail.png",
        )
        assertEquals("bilibili://mall/home", BottomBarFeatureInstaller.routeFromShape(values))
    }

    @Test
    fun `both frames derive the same canonical key and the same legacy alias`() {
        val tabHost = BottomBarFeatureInstaller.identityFromShape(
            listOf("会员购", "https://i0.hdslb.com/mall.png", "https://i0.hdslb.com/mall_s.png", "177", "mall", "bilibili://mall/home")
        )
        val frame = BottomBarFeatureInstaller.identityFromFrame(
            BottomBarFeatureInstaller.legacyShapeValues(
                "会员购", "https://i0.hdslb.com/mall.png", "https://i0.hdslb.com/mall_s.png", "177", "mall", "bilibili://mall/home/"
            ),
            "bilibili://mall/home/"
        )
        assertEquals("bottom_tab:uri:bilibili://mall/home", tabHost.canonicalKey)
        assertEquals(tabHost.canonicalKey, frame.canonicalKey)
        assertEquals("bottom_tab:uri:https://i0.hdslb.com/mall.png", tabHost.legacyKey)
        assertEquals(tabHost.legacyKey, frame.legacyKey)

        val entry = tabHost.scanEntry(showing = false)!!
        assertEquals(tabHost.canonicalKey, entry.key)
        assertEquals("bilibili://mall/home", entry.uri)
        assertEquals(setOf(tabHost.legacyKey), entry.aliases)
        assertTrue(tabHost.matches(setOf(tabHost.legacyKey!!)))
        assertTrue(tabHost.matches(setOf(tabHost.canonicalKey!!)))
        assertFalse(tabHost.matches(setOf("bottom_tab:uri:https://i0.hdslb.com/other.png")))
    }

    @Test
    fun `h5 bottom tabs keep the legacy key only, exactly as before`() {
        val identity = BottomBarFeatureInstaller.identityFromShape(
            listOf("商城", "https://i0.hdslb.com/shop.png", "https://mall.bilibili.com/index.html")
        )
        assertNull(identity.canonicalKey)
        val entry = identity.scanEntry(showing = true)!!
        assertEquals("bottom_tab:uri:https://i0.hdslb.com/shop.png", entry.key)
        assertTrue(entry.aliases.isEmpty())
    }

    @Test
    fun `picker confirmation rewrites a legacy selector to the canonical key once`() {
        val identity = BottomBarFeatureInstaller.identityFromShape(
            listOf("会员购", "https://i0.hdslb.com/mall.png", "bilibili://mall/home")
        )
        val entry = identity.scanEntry(showing = false)!!
        val unrelated = "bottom_tab:uri:bilibili://following/home"
        val stored = setOf(identity.legacyKey!!, unrelated)

        assertTrue(ComponentPickerSelection.isSelected(entry, stored))
        // 保持勾选确认：旧键被主键替换，其余键不动。
        assertEquals(
            setOf(identity.canonicalKey!!, unrelated),
            ComponentPickerSelection.merge(stored, listOf(entry to true))
        )
        // 取消勾选确认：主键和旧键都被移除——不会残留一个把条目继续藏起来的旧键。
        assertEquals(setOf(unrelated), ComponentPickerSelection.merge(stored, listOf(entry to false)))
        // 不可编辑的条目不参与合并。
        assertEquals(stored, ComponentPickerSelection.merge(stored, emptyList()))
    }

    @Test
    fun `aliases survive the cross-process snapshot codec and bad aliases are rejected`() {
        val identity = BottomBarFeatureInstaller.identityFromShape(
            listOf("会员购", "https://i0.hdslb.com/mall.png", "bilibili://mall/home")
        )
        val entry = identity.scanEntry(showing = false)!!
        val raw = MineComponentSnapshotCodec.encode(
            "tv.danmaku.bili", setOf("bottom_tab_filter"), listOf(entry),
            surface = MineComponentSnapshotCodec.SURFACE_BOTTOM_BAR
        )
        val decoded = MineComponentSnapshotCodec.decodeOrNull(raw)!!.entries.single()
        assertEquals(entry, decoded)

        val foreign = org.json.JSONObject(entry.toJson().toString()).apply {
            put("aliases", org.json.JSONArray().put("home_tab:id:1"))
        }
        assertNull(MineComponentScanEntry.fromJsonOrNull(foreign))
    }
}
