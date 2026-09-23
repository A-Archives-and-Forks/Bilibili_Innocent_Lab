package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeComponentFilterFeatureInstallerTest {

    /** 新首页框架形状：标记落在父类 `BaseHomeFragment`，运行时类是子类 `HomeFragment`。 */
    private open class BaseHomeFragment
    private class HomeFragment : BaseHomeFragment()

    /** 新框架外壳（底栏页容器）：名字里是 `homeframe`，不能被当成首页容器。 */
    private open class BaseHomeFrameFragment
    private class HomeFrameFragment : BaseHomeFrameFragment()

    @Test
    fun `new home frame page counts as home container through its superclass`() {
        assertTrue(HomeComponentFilterFeatureInstaller.isHomeContainer(HomeFragment::class.java))
        assertFalse(HomeComponentFilterFeatureInstaller.isHomeContainer(HomeFrameFragment::class.java))
        assertFalse(HomeComponentFilterFeatureInstaller.isHomeContainer(Any::class.java))
    }

    @Test
    fun `matches only bilibili home component class candidates`() {
        assertTrue(
            HomeComponentFilterFeatureInstaller.isClassMatched(
                "popularfragment",
                "com.bilibili.app.home.PopularFragment"
            )
        )
        assertFalse(
            HomeComponentFilterFeatureInstaller.isClassMatched(
                "detail",
                "com.bilibili.ship.theseus.detail.UnitedBizDetailsFragment"
            )
        )
        assertFalse(
            HomeComponentFilterFeatureInstaller.isClassMatched(
                "popularfragment",
                "third.party.PopularFragment"
            )
        )
    }
}
