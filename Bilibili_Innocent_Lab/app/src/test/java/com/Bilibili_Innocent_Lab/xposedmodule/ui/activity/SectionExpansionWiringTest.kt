package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手风琴分节动画的接线护栏（源码断言，与 AdaptiveGlowRenderGuardTest 同款思路）。
 *
 * 拦的是会**静默退化回旧观感**的写法：展开/收起绕开进度驱动器、
 * 动画帧不喂 Liquid 位移通知（位移表面会折射滞后底图）、收尾时 GONE 与 offsets
 * 复位不同步（控件跳变回来）。
 */
class SectionExpansionWiringTest {

    private fun source(relative: String): String {
        val candidates = sequenceOf(
            File("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$relative"),
            File("app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$relative")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate $relative from ${File(".").absolutePath}")
    }

    @Test fun sectionToggleDelegatesToTheProgressDrivenController() {
        val activity = source("ui/activity/MainActivity.kt")
        val body = activity.substringAfter("private fun animateSecondarySection")
            .substringBefore("override fun onStart")
        assertTrue("animateSecondarySection 必须委托 SectionExpansionController",
            body.contains("SectionExpansionController"))
        assertTrue("controller 必须吃到 Liquid 位移通知入口",
            body.contains("notifyPreparedSkinPositionChanged"))
    }

    @Test fun controllerNotifiesTheRendererOnEveryFrame() {
        val controller = source("ui/activity/SectionExpansionController.kt")
        val applyBody = controller.substringAfter("private fun apply(")
            .substringBefore("private fun finish(")
        assertTrue("apply() 必须逐帧喂位移通知（否则动画期折射滞后底图）",
            applyBody.contains("notifyPositionChanged()"))
    }

    @Test fun collapseFinalizesGoneAndOffsetResetTogether() {
        val controller = source("ui/activity/SectionExpansionController.kt")
        val finishBody = controller.substringAfter("private fun finish(")
            .substringBefore("/** 无动画直达")
        val goneIndex = finishBody.indexOf("View.GONE")
        val resetIndex = finishBody.indexOf("translationY = 0f")
        assertTrue("收尾必须把 content 置回 GONE", goneIndex >= 0)
        assertTrue("GONE 与兄弟/行 offsets 复位必须在同一收尾块",
            resetIndex >= 0)
    }

    @Test fun controllerDrivesCardClipAndSiblingGlideFromOneProgress() {
        val controller = source("ui/activity/SectionExpansionController.kt")
        assertTrue("卡片下边缘必须由逐帧圆角 outline 驱动（矩形 clipBounds 会切成直边截断）",
            controller.contains("clipToOutline") && controller.contains("invalidateOutline"))
        assertTrue("兄弟控件必须走 translationY 滑行（而非逐帧 relayout）",
            controller.contains("sibling.translationY"))
        assertTrue("文字行必须由揭示沿级联显影",
            controller.contains("ExpansionMotionPolicy.rowReveal"))
        assertTrue("箭头转角必须由同一进度驱动",
            controller.contains("chevron.rotation"))
    }
}
