package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.animation.ValueAnimator
import android.graphics.Outline
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import kotlin.math.roundToInt

/**
 * 手风琴分节的展开/收起驱动器（「兼容」「外观」「进阶/分类」共用）。
 *
 * 模型见 [ExpansionMotionPolicy]：动画期间布局始终保持展开态，单一进度 p 统一驱动
 * 卡片圆角 outline 裁剪、内容各行级联显影、兄弟控件 translationY 与箭头转角；p→0 收尾时
 * `GONE` 的重布局与同帧 offsets 复位互相抵消，全程零控件跳变、零逐帧 measure。
 *
 * 打断语义：反转只是翻转 [ExpansionMotionPolicy.Spring.target]，当前 p 与速度原样
 * 续跑——展开到一半收起、收起到一半展开都从中间态连续倒带，不存在 cancel 链。
 */
internal class SectionExpansionController(
    private val card: ViewGroup,
    private val content: ViewGroup,
    private val chevron: View,
    private val density: Float,
    cornerRadiusDp: Float = CARD_CORNER_DP,
    private val notifyPositionChanged: () -> Unit
) {
    private val spring = ExpansionMotionPolicy.Spring()
    private val featherPx = ExpansionMotionPolicy.FEATHER_DP * density
    private val settlePx = ExpansionMotionPolicy.ROW_SETTLE_DP * density
    private val risePx = ExpansionMotionPolicy.CONTENT_RISE_DP * density
    private val cornerRadiusPx = cornerRadiusDp * density

    /**
     * 卡片揭示沿：clipToOutline + 逐帧圆角 outline。矩形 clipBounds 会把圆角卡片
     * 切成直边"截断"（2026-09-22 真机截图实证）；outline 让下边缘全程保持卡片圆角，
     * 读作一张实体薄片在收卷，而不是被刀裁。
     */
    private var clipBottomPx = 0f
    private var clipActive = false
    private val clipOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val h = clipBottomPx.roundToInt()
            if (view.width <= 0 || h <= 0) {
                outline.setEmpty()
                return
            }
            outline.setRoundRect(
                0, 0, view.width, h,
                minOf(cornerRadiusPx, h * 0.5f)
            )
        }
    }

    private var siblings: List<View> = emptyList()
    private var rows: List<View> = emptyList()
    private var rowTops = FloatArray(0)
    private var contentHeightPx = 0
    private var collapsedCardHeightPx = 0
    private var expandedCardHeightPx = 0

    private var pendingSetup: ViewTreeObserver.OnPreDrawListener? = null
    private var ticking = false
    private var lastFrameNanos = 0L

    /** 当前逻辑目标态；与 MainActivity 侧的布尔标志互为冗余但独立可信。 */
    var expanded = false
        private set

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // teardown 之后已投递的回调仍会进这里一次，必须拦掉。
            if (!ticking) return
            val last = lastFrameNanos
            lastFrameNanos = frameTimeNanos
            val dt = if (last == 0L) 1f / 60f else (frameTimeNanos - last) / 1e9f
            if (!card.isAttachedToWindow || !content.isAttachedToWindow) {
                ticking = false
                settleNow(spring.target >= 0.5f)
                return
            }
            val atRest = spring.step(dt)
            apply(spring.p)
            if (atRest) finish(spring.target >= 0.5f)
            else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * 切换到目标态。[animate] 为 false 或布局尚未就绪时直接落位；
     * 其余路径都走进度弹簧，任意时刻可再反向。
     */
    fun setExpanded(target: Boolean, animate: Boolean = true) {
        if (!animate || !card.isLaidOut || !ValueAnimator.areAnimatorsEnabled()) {
            teardown()
            snapTo(target)
            return
        }
        if (target == expanded && pendingSetup == null && !ticking) return
        expanded = target
        spring.target = if (target) 1f else 0f
        if (target) {
            if (pendingSetup != null) return // 展开布点已排队，等首帧
            if (content.visibility == View.VISIBLE && contentHeightPx > 0) {
                // 收起中途反转：几何仍有效，直接续跑
                startTicker()
            } else {
                beginExpandSetup()
            }
        } else {
            if (pendingSetup != null) {
                // 展开尚未起步即被打断：撤销布点，回到干净的收起态
                teardown()
                snapTo(false)
                return
            }
            captureGeometry()
            startTicker()
        }
    }

    /** 展开：先记下收起态卡高，VISIBLE 后等一次布局拿到展开几何，再起步。 */
    private fun beginExpandSetup() {
        collapsedCardHeightPx = if (content.visibility == View.VISIBLE) {
            // 内容已占位的残留态：反推收起高，避免把展开高误当收起高。
            card.height - content.height
        } else {
            card.height
        }
        content.visibility = View.VISIBLE
        val listener = ViewTreeObserver.OnPreDrawListener {
            pendingSetup?.let { pending ->
                content.viewTreeObserver.takeIf { it.isAlive }
                    ?.removeOnPreDrawListener(pending)
            }
            pendingSetup = null
            if (!content.isAttachedToWindow) return@OnPreDrawListener true
            expandedCardHeightPx = card.height
            contentHeightPx = content.height
            captureActors()
            apply(spring.p)
            startTicker()
            true
        }
        pendingSetup = listener
        content.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /** 收起路径（含展开动画中途反转）：内容仍占展开位，直接量取即可。 */
    private fun captureGeometry() {
        contentHeightPx = content.height
        expandedCardHeightPx = card.height
        collapsedCardHeightPx = expandedCardHeightPx - contentHeightPx
        captureActors()
    }

    /**
     * 跟随者收集：内容收缩 h 会让卡片的**每一层 wrap_content 祖先**同步变矮，
     * 因此要被顶起/放下的不只是卡片的同级兄弟——要沿祖先链向上走，
     * 收集每一层垂直 LinearLayout 中位于该祖先之后的全部子项。
     * 遇到非垂直线性布局（ScrollView、横向容器等）即停止上溯。
     */
    private fun captureActors() {
        val followers = ArrayList<View>()
        var node: View? = card
        while (true) {
            val parent = node?.parent as? ViewGroup ?: break
            if (parent !is LinearLayout || parent.orientation != LinearLayout.VERTICAL) break
            val index = parent.indexOfChild(node)
            if (index >= 0) {
                for (i in index + 1 until parent.childCount) {
                    followers += parent.getChildAt(i)
                }
            }
            node = parent
        }
        siblings = followers
        rows = (0 until content.childCount).map(content::getChildAt)
        rowTops = FloatArray(rows.size) { rows[it].top.toFloat() }
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * 单帧映射：p 是唯一的驱动源。
     * 卡片下边缘扫动 → 内容揭示沿 → 行级联显影 → 兄弟滑行 → 箭头 → Liquid 位移通知。
     */
    private fun apply(p: Float) {
        val spatial = ExpansionMotionPolicy.spatial(p)
        clipBottomPx = (
            collapsedCardHeightPx +
                (expandedCardHeightPx - collapsedCardHeightPx) * spatial
            ).coerceAtLeast(0f)
        if (!clipActive) {
            card.outlineProvider = clipOutlineProvider
            card.clipToOutline = true
            clipActive = true
        }
        card.invalidateOutline()
        content.translationY = -risePx * (1f - spatial)

        // 揭示沿要换算进内容的绘制空间：布局顶边 + 当前上浮位移。
        val clipY = clipBottomPx - (content.top + content.translationY)
        for (i in rows.indices) {
            val reveal = ExpansionMotionPolicy.rowReveal(clipY, rowTops[i], featherPx)
            rows[i].alpha = reveal
            rows[i].translationY =
                ExpansionMotionPolicy.rowSettleOffset(reveal, settlePx)
        }
        val siblingOffset = -contentHeightPx * (1f - spatial)
        for (sibling in siblings) sibling.translationY = siblingOffset
        chevron.rotation = ExpansionMotionPolicy.CHEVRON_DEGREES * spatial
        // 位移表面（卡片/兄弟的 CARD 表面）每帧换原点——喂通知让渲染层走
        // motionLite+稳定底图，动画停止 160ms 静默后自行恢复实时档。
        notifyPositionChanged()
    }

    /** 收尾/取消时恢复卡片裁剪与背景 outline 的默认行为。 */
    private fun clearClip() {
        if (clipActive) {
            card.clipToOutline = false
            card.outlineProvider = ViewOutlineProvider.BACKGROUND
            clipActive = false
        }
        card.clipBounds = null
    }

    /** 弹簧静止收尾：目标态的全部几何一次性落位，收起时同帧 GONE + 复位。 */
    private fun finish(toExpanded: Boolean) {
        ticking = false
        if (!toExpanded) content.visibility = View.GONE
        clearClip()
        content.translationY = 0f
        for (row in rows) {
            row.alpha = 1f
            row.translationY = 0f
        }
        for (sibling in siblings) sibling.translationY = 0f
        siblings = emptyList()
        chevron.rotation = if (toExpanded) ExpansionMotionPolicy.CHEVRON_DEGREES else 0f
        notifyPositionChanged()
    }

    /** 无动画直达目标态；同时清掉动画残留的所有视觉补偿。 */
    private fun snapTo(target: Boolean) {
        expanded = target
        spring.p = if (target) 1f else 0f
        spring.v = 0f
        spring.target = spring.p
        ticking = false
        content.visibility = if (target) View.VISIBLE else View.GONE
        clearClip()
        content.translationY = 0f
        for (row in rows) {
            row.alpha = 1f
            row.translationY = 0f
        }
        for (sibling in siblings) sibling.translationY = 0f
        siblings = emptyList()
        chevron.rotation = if (target) ExpansionMotionPolicy.CHEVRON_DEGREES else 0f
    }

    /** 视图树重建/节区卸载时调用：停帧、撤掉排队中的 preDraw 布点、清残留补偿。 */
    fun cancel() {
        teardown()
        clearClip()
        content.translationY = 0f
        for (row in rows) {
            row.alpha = 1f
            row.translationY = 0f
        }
        rows = emptyList()
        rowTops = FloatArray(0)
        for (sibling in siblings) sibling.translationY = 0f
        siblings = emptyList()
    }

    private fun teardown() {
        ticking = false
        pendingSetup?.let { pending ->
            content.viewTreeObserver.takeIf { it.isAlive }
                ?.removeOnPreDrawListener(pending)
        }
        pendingSetup = null
    }

    /** 视图脱离窗口时的安全落位：直接吸附到弹簧目标态。 */
    private fun settleNow(toExpanded: Boolean) {
        pendingSetup = null
        snapTo(toExpanded)
    }

    private companion object {
        /** 与四个分节卡片 `skinCardBackground(surface, 12f)` 的圆角一致。 */
        const val CARD_CORNER_DP = 12f
    }
}
