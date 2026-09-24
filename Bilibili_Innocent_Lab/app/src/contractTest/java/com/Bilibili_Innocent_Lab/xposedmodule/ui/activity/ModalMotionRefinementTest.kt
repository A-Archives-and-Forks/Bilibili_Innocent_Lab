package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModalMotionRefinementTest {
    @Test fun bubbleAxesHaveIndependentProfilesAndExactEndpoints() {
        for (entry in listOf(true, false)) {
            assertEquals(0f, BubbleMotionSpec.scaleX(0f, entry), 0f)
            assertEquals(0f, BubbleMotionSpec.scaleY(0f, entry), 0f)
            assertEquals(1f, BubbleMotionSpec.scaleX(1f, entry), 0f)
            assertEquals(1f, BubbleMotionSpec.scaleY(1f, entry), 0f)
            assertTrue(BubbleMotionSpec.scaleX(.5f, entry) > BubbleMotionSpec.scaleY(.5f, entry))
        }
    }

    @Test fun entryHasNoOvershootAndSettlesPrecisely() {
        var maxX = 0f
        var maxY = 0f
        for (step in 0..1000) {
            val x = BubbleMotionSpec.scaleX(step / 1000f, true)
            val y = BubbleMotionSpec.scaleY(step / 1000f, true)
            assertTrue(x in 0f..1f)
            assertTrue(y in 0f..1f)
            maxX = maxOf(maxX, x)
            maxY = maxOf(maxY, y)
        }
        assertEquals(1f, maxX, 0f)
        assertEquals(1f, maxY, 0f)
        assertTrue(BubbleMotionSpec.ENTER_DURATION_MS in 240L..250L)
    }

    @Test fun settledCloseShrinksBothAxesWithoutRebound() {
        var lastX = 1f
        var lastY = 1f
        for (step in 1000 downTo 0) {
            val x = BubbleMotionSpec.scaleX(step / 1000f, false)
            val y = BubbleMotionSpec.scaleY(step / 1000f, false)
            assertTrue(x <= lastX && y <= lastY)
            assertTrue(x in 0f..1f && y in 0f..1f)
            lastX = x
            lastY = y
        }
    }

    @Test fun axisProfilesRemainContinuousAcrossGrowthAndReboundBoundaries() {
        for (entry in listOf(true, false)) {
            for (boundary in listOf(.60f, .70f, .78f, .86f, 1f)) {
                assertTrue(kotlin.math.abs(BubbleMotionSpec.scaleX(boundary - .00001f, entry) -
                    BubbleMotionSpec.scaleX(boundary + .00001f, entry)) < .0001f)
                assertTrue(kotlin.math.abs(BubbleMotionSpec.scaleY(boundary - .00001f, entry) -
                    BubbleMotionSpec.scaleY(boundary + .00001f, entry)) < .0001f)
            }
        }
    }

    @Test fun matchingTitlesCanMoveButRelatedDifferentTitlesCannot() {
        assertTrue(ModalTitleMotionSpec.matches("首页推荐过滤", "首页推荐过滤"))
        assertFalse(ModalTitleMotionSpec.matches(" Portrait content filters ", "Portrait content filters"))
        assertFalse(ModalTitleMotionSpec.matches("自定义首页组件", "隐藏首页组件规则"))
        assertFalse(ModalTitleMotionSpec.matches("首页推荐过滤\n已选择 2 项", "首页推荐过滤"))
        assertFalse(ModalTitleMotionSpec.matches("首页推荐过滤", "首页推荐过滤？"))
        assertFalse(ModalTitleMotionSpec.matches("", ""))
    }

    @Test fun titleBaselineAndSizeInterpolationHasExactClampedEndpoints() {
        assertEquals(24f, ModalTitleMotionSpec.interpolate(24f, 160f, 0f), 0f)
        assertEquals(160f, ModalTitleMotionSpec.interpolate(24f, 160f, 1f), 0f)
        assertEquals(92f, ModalTitleMotionSpec.interpolate(24f, 160f, .5f), 0f)
        assertEquals(24f, ModalTitleMotionSpec.interpolate(24f, 160f, -1f), 0f)
        assertEquals(160f, ModalTitleMotionSpec.interpolate(24f, 160f, 2f), 0f)
    }

    @Test fun carrierSurfaceFollowsTheAnimatedCardRectInsteadOfTheWindow() {
        // 形变期间可见表面是承载层的 background（全屏 View bounds）。若 drawable 按
        // 全屏矩形绘制，模态描边与顶沿高光会绕窗口计算再被 outline 裁掉——"通透
        // 光泽"要等动画播完、交还卡片自身 drawable 才出现（真机实测 pf 帧对比）。
        // 修复是两通道同步：Liquid 经 LiquidMotionSurfaceFrameProvider 读形变
        // 边界，Material 的 Drawable 读 bounds。
        val layer = source("IconAnchoredMotionLayer")
        assertTrue(layer.contains("LiquidMotionSurfaceFrameProvider"))
        assertTrue(layer.contains("override fun copyLiquidMotionBounds"))
        assertTrue(layer.contains("override fun liquidMotionCornerRadiusPx"))
        assertTrue(layer.contains("override fun liquidMotionFallbackColor"))
        val applyFrame = layer.substringAfter("fun applyFrame(")
            .substringBefore("fun clearShape(")
        assertTrue(applyFrame.contains("background?.setBounds("))
        // 收起形变后必须复位：残留卡片矩形会让"下次常驻表面"按旧边界画。
        val clearShape = layer.substringAfter("fun clearShape(")
            .substringBefore("private fun updateRestingSurface(")
        assertTrue(clearShape.contains("background?.setBounds(0, 0, width, height)"))
        // 未成形时 provider 必须回报空矩形，否则常驻态会拿着空 bounds 走运动分支。
        val provider = layer.substringAfter("override fun copyLiquidMotionBounds")
            .substringBefore("override fun liquidMotionCornerRadiusPx")
        assertTrue(provider.contains("shaped"))
        assertTrue(provider.contains("setEmpty()"))
    }

    @Test fun carrierActiveKeepsCardOwnBackgroundOutOfTheFrame() {
        // 模态表面是半透明玻璃后，承载层 drawable 与卡片自身背景两张同色同矩形
        // 叠画会让填充越叠越实、描边越叠越亮，落定摘层时通透度跳回来。承载层
        // 在场期间 contentBackground 必须归 0，只在承载层缺席的兜底路径上才按
        // strokeAlpha 渐出。
        val controller = source("IconAnchoredMotionController")
        val apply = controller.substringAfter("private fun apply(")
            .substringBefore("private fun finish(")
        assertTrue(apply.contains("layer.background == null"))
        val prep = controller.substringAfter("fun prepareFirstFrame(")
            .substringBefore("fun startEntry(")
        assertTrue(prep.contains("contentBackground?.alpha = 0"))
        val exit = controller.substringAfter("private fun prepareExitFrame(")
            .substringBefore("private fun animateTo(")
        assertTrue(exit.contains("contentBackground?.alpha = 0"))
        // 稳定端与硬关都要把卡片背景恢复回 255，不能留着 0 给复用 container 的路径。
        assertTrue(controller.contains("contentBackground?.alpha = 255"))
    }

    @Test fun expansionTargetTracksTheLiveCardRectEveryFrame() {
        // 几何在形变开始前解析一次，之后卡片仍可能被重排版（insets 落定/标题交接），
        // 陈旧的 expandedBounds 会让承载层最后一帧与卡片错位 ~1px——交接瞬间整圈
        // 描边与光学采样区平移一档（"落定瞬间边缘光跳变"）。apply() 必须用卡片
        // 当前 left/top/right/bottom 重建展开端目标。
        val controller = source("IconAnchoredMotionController")
        val apply = controller.substringAfter("private fun apply(")
            .substringBefore("private fun finish(", "MISSING")
        assertTrue(apply != "MISSING")
        assertTrue(apply.contains("content.left.toFloat()"))
        assertTrue(apply.contains("expandedBounds = liveExpanded"))
    }

    /**
     * 覆盖式子面板淡出的必须是父面板的**卡片层**，不是整张 decorView（2026-09-22 真机实证）。
     *
     * 父面板的压暗层就在 decorView 里，跟着淡到 0 就等于背景压暗消失；而子面板按
     * "父面板那层还在"的前提**故意不加自己的 scrim**，两条假设一撞，开子面板时整屏变亮
     * （实测面板外背景 BGR 25.7/29.3/26.1 → 42.0/48.0/42.7，底页文字透出）。
     */
    @Test fun coveringASubPanelKeepsTheParentScrimAlive() {
        val present = SettingsUiSource.function("presentSizedModalDialog")
        assertTrue("必须从 dialogScrims 认出父面板的压暗层", present.contains("dialogScrims[parent]"))
        assertTrue("淡出目标必须是非 scrim 的那个卡片层",
            present.contains("firstOrNull { it !== parentScrim }"))
        val coveredIndex = present.indexOf("val coveredContent")
        val decorIndex = present.indexOf("parent.window?.decorView", coveredIndex)
        assertTrue("decorView 只能作为拿不到卡片层时的兜底", coveredIndex in 0 until decorIndex)
    }

    private fun source(name: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/$name.kt"
        return sequenceOf(File(path), File("app/$path")).first(File::isFile).readText()
    }

    @Test fun missingSnapshotUsesSameAnchoredRuleEditorWithoutChangingSelectionRules() {
        // 这两个窗口原来靠"到下一个函数为止"划界，已经被搬迁悄悄撑破过一次：
        // showRecommendVideoDurationRangeDialog 外移后分隔符失配，substringBefore
        // 返回整段剩余源码，断言变成在半份文件里找字符串——照样通过，护栏没了。
        val fallback = SettingsUiSource.function("showComponentManualRuleEditor")
        assertTrue(fallback.contains("spec.summaryView()"))
        assertTrue(fallback.contains("spec.currentRules(), anchor"))
        val editor = SettingsUiSource.function("showRuleEditorDialog")
        assertTrue(editor.contains("presentModalDialog(dialog, container, anchor)"))
        assertFalse(fallback.contains("remove("))
        assertFalse(fallback.contains("clear("))
    }

    @Test fun titleOverlayIsOptionalRestoredAndNeverReflowsPerFrame() {
        val title = source("ModalTitleMotion")
        // 目标标题必须独占一行；来源允许是"标题 \n 摘要"的合成 TextView，
        // 由渲染后的首行复核（见 ModalTitleHandoffTest 的首行配对用例）。
        assertTrue(title.contains("targetLayout.lineCount != 1"))
        assertTrue(title.contains("renderedTitleLine("))
        assertTrue(title.contains("getEllipsisCount(0) != 0"))
        // 来源行只淡文字颜色，**不能**动 View 的 alpha：那会把 ripple 一起变透明并冻结它的
        // 动画，等形变结束才补播一次高光（见 ModalTitleHandoffTest 的 ripple 用例）。
        assertFalse(title.contains("source.alpha ="))
        assertTrue(title.contains("sourceTextColors.withAlpha("))
        assertTrue(title.contains("sourceColors.release(source, sourceOwner)?.let(source::setTextColor)"))
        assertTrue(title.contains("target.alpha = targetAlpha"))
        val draw = title.substringAfter("override fun onDraw(").substringBefore("companion object")
        for (forbidden in listOf("requestLayout", "Bitmap", "find(", "TextPaint(", "textSize =")) {
            assertFalse(forbidden, draw.contains(forbidden))
        }
        val controller = source("IconAnchoredMotionController")
        assertTrue(controller.contains("titleMotion?.prepare(0f)"))
        assertTrue(controller.contains("titleMotion?.prepare(expansion)"))
        assertTrue(controller.contains("titleMotion?.apply(clamped)"))
        assertTrue(controller.contains("titleMotion?.expanded()"))
        assertTrue(controller.contains("title.finishAfterSourceDraw(onClosed)"))
        assertTrue(controller.contains("titleMotion?.dispose()"))
        assertEquals(1, Regex("NavigationMotionPolicy.remainingDuration\\(").findAll(controller).count())
    }

    @Test fun theMotionLayerOwnsTheShadowAcrossMorphAndRest() {
        // 2026-09-22 逐帧实测：落定瞬间卡片外 12px 环带暗 ~2 档——12dp elevation
        // 阴影在形变期被裁掉、落定一帧弹出。最终方案：阴影归承载层（构造期
        // elevation 常量），outline 形变期=形变矩形、落定后=卡片矩形，阴影全程
        // 连续。表面 View 绝不能带 elevation——ViewGroup 按 Z 排序绘制，Z>0 的
        // 表面会排到卡片之后，半透明玻璃盖住正文（实测行文字 211→66）。
        val layer = source("IconAnchoredMotionLayer")
        assertTrue(layer.contains("surfaceElevation: Float = 0f"))
        assertTrue(layer.contains("elevation = surfaceElevation"))
        // outline 三分支：形变矩形（alpha 1）→ 落定卡片矩形（alpha 1）→ 无（alpha 0）。
        val provider = layer.substringAfter("outlineProvider =")
            .substringBefore("fun applyFrame(")
        assertEquals(2, Regex("outline\\.alpha = 1f").findAll(provider).count())
        assertTrue(provider.contains("surfaceRadiusPx"))
        // 持久分支逐帧刷新投影轮廓；落定矩形回写时也刷新。
        val applyFrame = layer.substringAfter("fun applyFrame(").substringBefore("fun clearShape(")
        assertTrue(applyFrame.contains("invalidateOutline()"))
        assertTrue(layer.substringAfter("private fun updateRestingSurface(").contains("invalidateOutline()"))
        // 飞行标题浮层必须高于承载层（否则形变期被面板盖住），且自身空 outline 不投影。
        val present = SettingsUiSource.function("presentSizedModalDialog")
        assertTrue(present.contains("title.elevation = morphLayer.elevation + 1f"))
        val title = source("ModalTitleMotion")
        assertTrue(title.contains("outline.alpha = 0f"))
        // 普通面板移交卡片 elevation；覆盖式面板（cover != null）不新增阴影。
        assertTrue(present.contains("surfaceElevation = if (cover == null) container.elevation else 0f"))
        // 控制器不再逐帧搬移 elevation（常量由层构造期持有）。
        val controller = source("IconAnchoredMotionController")
        assertFalse(controller.contains("layer.elevation ="))
    }

    @Test fun theCoveredParentFadesOutLateSoTwoStrokesNeverStackAtTheEnd() {
        // 两张卡片矩形完全重合时各画一条半透明描边，叠加后比单独任何一张都亮：
        // 真机实测同一条左边缘，父面板独自稳定 87，子面板落位后 103，且这一跳在最后一帧。
        assertEquals(0f, IconAnchoredMotionSpec.coveredParentAlpha(1f), 0f)
        assertEquals(1f, IconAnchoredMotionSpec.coveredParentAlpha(0f), 0f)
        // 起点必须够晚：早了父面板的正文会当着用户的面褪色。
        assertTrue(IconAnchoredMotionSpec.COVERED_PARENT_FADE_START >= 0.85f)
        assertEquals(1f, IconAnchoredMotionSpec.coveredParentAlpha(
            IconAnchoredMotionSpec.COVERED_PARENT_FADE_START), 0f)
        // 单调不回头，否则父面板会在末段闪一下。
        var previous = 1f
        for (step in 0..1000) {
            val value = IconAnchoredMotionSpec.coveredParentAlpha(step / 1000f)
            assertTrue(value in 0f..1f)
            assertTrue(value <= previous + 1e-6f)
            previous = value
        }
        val present = SettingsUiSource.function("presentSizedModalDialog")
        // 只有覆盖场景才淡父面板；普通弹窗没有父面板可淡。
        assertTrue(present.contains("val coveredContent = if (cover != null) coveredParent?.let"))
        // 淡的必须是**卡片层整层**：气泡面板的表面连同描边是 BubblePanelLayer 画的，容器自己
        // background = null，只淡容器会让文字变淡、描边纹丝不动（实测 103 没有回到 87）。
        // 但也**不能**淡整张 decorView——父面板的 scrim 在里面，跟着淡掉背景压暗就整个消失
        // （2026-09-22 真机实测：面板外 BGR 25.7/29.3/26.1 → 42.0/48.0/42.7）。
        assertTrue(present.contains("firstOrNull { it !== parentScrim }"))
        assertFalse(present.contains("coveredParent?.window?.decorView?.findViewById"))
        // 入场与退场两条 onFrame 都要驱动它，否则收起时父面板不会淡回来。
        assertEquals(2, Regex("coveredContent\\?\\.alpha = IconAnchoredMotionSpec\\.coveredParentAlpha")
            .findAll(present).count())
        // 硬关会停在半路，父面板不能留着半透明的 alpha。
        assertTrue(present.contains("coveredContent?.alpha = 1f"))
    }

    @Test fun reversalKeepsEntryShapeUntilStableEndpoint() {
        val controller = source("BubbleMotionController")
        val close = controller.substringAfter("fun requestClose(").substringBefore("fun handleWindowSizeChange")
        assertFalse(close.contains("entryShape ="))
        assertTrue(controller.contains("layer.applyFrame(clamped, entryShape)"))
        val layer = source("BubblePanelLayer")
        assertTrue(layer.contains("BubbleMotionSpec.scaleX(progress, entryShape)"))
        assertTrue(layer.contains("BubbleMotionSpec.scaleY(progress, entryShape)"))
    }
}
