package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import fm1.k
import fm1.n
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFrameTabLocatorTest {

    private val loader = javaClass.classLoader!!

    private fun model(): HomeFrameTabLocator.Model {
        val resolution = HomeFrameTabLocator.resolve(loader)
        assertTrue("新首页框架应被定位到: $resolution", resolution is HomeFrameTabLocator.Resolution.Found)
        return (resolution as HomeFrameTabLocator.Resolution.Found).model
    }

    private fun tab(
        id: String,
        name: String,
        uri: String,
        icon: String = "https://i0.hdslb.com/$id.png",
        defaultSelected: Int = 0,
        tabId: String = "t_$id"
    ) = n(id, name, uri, icon, "$icon?selected", defaultSelected, 0, tabId)

    @Test
    fun `locates tab data through view model generics and serial names only`() {
        val model = model()
        assertEquals(k::class.java, model.dataClass)
        assertEquals(n::class.java, model.item.itemClass)
        // 元素 tab/bottom 必须落在第 2/3 个字段（b/c），不是按类型猜的第一个 List。
        assertEquals("b", model.slot(HomeFrameTabLocator.ELEMENT_TAB)!!.field.name)
        assertEquals("c", model.slot(HomeFrameTabLocator.ELEMENT_BOTTOM)!!.field.name)
        assertEquals(3, model.constructors.size)
        val home = tab("1", "首页", "bilibili://main/home", defaultSelected = 1)
        assertEquals("首页", model.item.string(home, "name"))
        assertEquals("bilibili://main/home", model.item.string(home, "uri"))
        assertEquals("t_1", model.item.string(home, "tab_id"))
        assertTrue(model.item.isDefaultSelected(home))
        assertFalse(model.item.isDefaultSelected(tab("2", "动态", "bilibili://following/home")))
    }

    @Test
    fun `hosts without the new frame are absent rather than failed`() {
        val withoutFrame = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == HomeFrameTabLocator.VIEW_MODEL_CLASS) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        assertSame(HomeFrameTabLocator.Resolution.Absent, HomeFrameTabLocator.resolve(withoutFrame))
    }

    @Test
    fun `field order follows dex sorting by name then type descriptor`() {
        assertEquals(listOf("a", "b", "c", "d", "e"), HomeFrameTabLocator.orderedInstanceFields(k::class.java).map { it.name })
        assertEquals("I", HomeFrameTabLocator.descriptorOf(Int::class.javaPrimitiveType!!))
        assertEquals("Ljava/lang/String;", HomeFrameTabLocator.descriptorOf(String::class.java))
        assertEquals("[J", HomeFrameTabLocator.descriptorOf(LongArray::class.java))
    }

    @Test
    fun `list hook filters once at the outermost constructor and never drops the default tab`() {
        val model = model()
        val slot = model.slot(HomeFrameTabLocator.ELEMENT_BOTTOM)!!
        val scans = mutableListOf<Int>()
        val hook = HomeFrameListHook(
            slot = slot,
            item = model.item,
            onScan = { scans += it.size },
            shouldHide = { item -> (item as n).b in setOf("首页", "会员购") },
            onApplied = { _, _ -> }
        )
        val bottom = listOf(
            tab("1", "首页", "bilibili://main/home", defaultSelected = 1),
            tab("2", "动态", "bilibili://following/home"),
            tab("3", "会员购", "bilibili://mall/home"),
            tab("4", "我的", "bilibili://user_center/mine")
        )
        val data = k(emptyList(), emptyList(), bottom, emptyList(), null)

        // 默认参数构造器 → 主构造器：内层结束时不处理，最外层结束才处理一次。
        hook.enter(); hook.enter()
        hook.exit(data)
        assertTrue(scans.isEmpty())
        hook.exit(data)
        assertEquals(listOf(4), scans)
        assertEquals(listOf("首页", "动态", "我的"), data.c.map { it.b })
        assertTrue(data.c is HomeFrameListHook.FilteredTabList)

        // 宿主 copy() 把已过滤列表原样传回构造器：认出标记，不再扫描（否则面板会丢掉已隐藏项）。
        val copy = k(0, data.a, data.b, data.c, data.d, null)
        hook.enter(); hook.exit(copy)
        assertEquals(listOf(4), scans)
        assertSame(data.c, copy.c)
    }

    @Test
    fun `filtering everything away falls back to the original list`() {
        val only = listOf<Any?>("x", "y")
        assertSame(only, HomeFrameListHook.filterFrameTabs(only, { true }, { false }))
        val kept = HomeFrameListHook.filterFrameTabs(only, { it == "x" }, { false })
        assertNotSame(only, kept)
        assertEquals(listOf("y"), kept)
    }

    // ---- 安装器层：通过真实 Modern DSL 触发构造器回调 ----

    private val statuses = mutableListOf<Pair<String, String>>()
    private val snapshots = mutableListOf<Pair<String, ScanSnapshotContent>>()

    private fun environment(registrar: HookRegistrar) = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { channel, value -> statuses += channel to value },
        writeScanSnapshot = { surface, content -> snapshots += surface to content; true }
    )

    /** 模拟宿主构造完一个 HomeTabData：对全部已登记构造器 Hook 触发一次 before/after。 */
    private fun PlayerPortTestRegistrar.construct(prefix: String, data: k) {
        val id = hooks.keys.first { it.startsWith(prefix) }
        invoke(id, receiver = data)
    }

    @Test
    fun `bottom bar frame layer reuses the tabhost selector key so old picks keep working`() {
        val home = tab("1", "首页", "bilibili://main/home", defaultSelected = 1)
        val mall = tab("3", "会员购", "bilibili://mall/home", icon = "https://i0.hdslb.com/mall.png")
        // 手机模式（TabHost 层）勾选时产出的键：值形状取"第一个带 :// 的值"，即图标地址。
        val phoneKey = MineComponentSelector.key("bottom_tab", "会员购", null, "https://i0.hdslb.com/mall.png")!!
        val registrar = PlayerPortTestRegistrar()
        val result = BottomBarFeatureInstaller(
            rules = "",
            selectors = MineComponentSelectionCodec.encode(setOf(phoneKey)),
            points = null
        ).install(environment(registrar))

        assertEquals(FeatureInstallResult.Installed(1), result)
        assertTrue("bottom_bar_layers" to "tabhost=absent,frame=ok" in statuses)
        assertTrue("bottom_bar_status" to "success:filter+scan" in statuses)
        assertTrue(registrar.hooks.keys.all { it.startsWith("bottom.frame.ctor.") })

        val data = k(emptyList(), emptyList(), listOf(home, mall), emptyList(), null)
        registrar.construct("bottom.frame.ctor.", data)
        assertEquals(listOf("首页"), data.c.map { it.b })

        val (surface, content) = snapshots.last()
        assertEquals(MineComponentSnapshotCodec.SURFACE_BOTTOM_BAR, surface)
        val hidden = content.entries.single { !it.showing }
        // 条目以路由主键发布、旧图标键作别名：面板显示为已勾选，确认后旧键被换成主键。
        assertEquals("bottom_tab:uri:bilibili://mall/home", hidden.key)
        assertEquals(setOf(phoneKey), hidden.aliases)
        assertTrue(ComponentPickerSelection.isSelected(hidden, setOf(phoneKey)))
    }

    @Test
    fun `bottom bar frame layer hides by the canonical route key regardless of icon`() {
        val mall = tab("3", "会员购", "bilibili://mall/home/?from=tab", icon = "https://i0.hdslb.com/new-skin.png")
        val registrar = PlayerPortTestRegistrar()
        BottomBarFeatureInstaller(
            rules = "",
            selectors = MineComponentSelectionCodec.encode(setOf("bottom_tab:uri:bilibili://mall/home")),
            points = null
        ).install(environment(registrar))
        val home = tab("1", "首页", "bilibili://main/home", defaultSelected = 1)
        val data = k(emptyList(), emptyList(), listOf(home, mall), emptyList(), null)
        registrar.construct("bottom.frame.ctor.", data)
        assertEquals(listOf("首页"), data.c.map { it.b })
    }

    @Test
    fun `home tab frame layer filters the tab element with the legacy id key`() {
        val recommend = tab("推荐id", "推荐", "bilibili://pegasus/promo", defaultSelected = 1)
        val film = tab("影视id", "影视", "bilibili://pgc/home")
        // 旧框架 MainResourceManager 把 JSON `id` 放进第一个字段，键就是 home_tab:id:<id>。
        val legacyKey = MineComponentSelector.key("home_tab", "影视", "影视id", "bilibili://pgc/home")!!
        val registrar = PlayerPortTestRegistrar()
        val result = HomeTabFilterFeatureInstaller(
            rules = "",
            selectors = MineComponentSelectionCodec.encode(setOf(legacyKey)),
            points = null
        ).install(environment(registrar))

        assertEquals(FeatureInstallResult.Installed(1), result)
        assertTrue("home_tab_filter_layers" to "legacy=absent,frame=ok" in statuses)

        val bottom = listOf(tab("b", "首页", "bilibili://main/home", defaultSelected = 1))
        val data = k(emptyList(), listOf(recommend, film), bottom, emptyList(), null)
        registrar.construct("home.tabs.frame.ctor.", data)
        assertEquals(listOf("推荐"), data.b.map { it.b })
        // 另一个元素不受影响。
        assertSame(bottom, data.c)
        assertEquals(MineComponentSnapshotCodec.SURFACE_HOME_TABS, snapshots.last().first)
    }

    @Test
    fun `losing both layers is reported instead of passing silently`() {
        val result = BottomBarFeatureInstaller(
            rules = "会员购",
            points = null,
            frameResolver = { HomeFrameTabLocator.Resolution.Failed("missing-tab-data") }
        ).install(environment(PlayerPortTestRegistrar()))
        assertTrue(result is FeatureInstallResult.Skipped)
        assertTrue("bottom_bar_layers" to "tabhost=absent,frame=failed" in statuses)
    }

    @Test
    fun `scan only mode never rewrites host data`() {
        val registrar = PlayerPortTestRegistrar()
        BottomBarFeatureInstaller(rules = "", points = null).install(environment(registrar))
        assertTrue("bottom_bar_status" to "success:scan" in statuses)
        val bottom = listOf(
            tab("1", "首页", "bilibili://main/home", defaultSelected = 1),
            tab("2", "动态", "bilibili://following/home")
        )
        val data = k(emptyList(), emptyList(), bottom, emptyList(), null)
        registrar.construct("bottom.frame.ctor.", data)
        assertSame(bottom, data.c)
        assertEquals(2, snapshots.last().second.entries.size)
    }
}
