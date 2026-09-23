package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/** 嵌套区域先扣子级，再收父级；每段高度只由一个控制器计入共享祖先。 */
internal object NestedExpansionPolicy {
    fun ownShrink(height: Float, descendantShrink: Float, progress: Float): Float =
        (height - descendantShrink).coerceAtLeast(0f) * (1f - progress.coerceAtLeast(0f))

    /** 子级引起的前序行收缩必须先进入自然行顶，再做父级的牌堆折叠。 */
    fun rowTop(index: Int, layoutTop: Float, precedingOffset: Float, progress: Float, peek: Float): Float =
        ExpansionMotionPolicy.rowFoldedTop(index, layoutTop + precedingOffset, progress, peek)

    fun scrollPosition(
        shrink: Float,
        startShrink: Float,
        endShrink: Float,
        startScroll: Int,
        endScroll: Int
    ): Float {
        val distance = endShrink - startShrink
        if (kotlin.math.abs(distance) < 0.001f) return startScroll.toFloat()
        val fraction = ((shrink - startShrink) / distance).coerceIn(0f, 1f)
        return startScroll + (endScroll - startScroll) * fraction
    }
}
