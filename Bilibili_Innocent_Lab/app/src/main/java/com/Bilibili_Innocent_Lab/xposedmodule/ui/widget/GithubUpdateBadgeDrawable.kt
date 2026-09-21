package com.Bilibili_Innocent_Lab.xposedmodule.ui.widget

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 复用现有气泡 Path，将箭头翻到左下方；箭头包含在 View 尺寸内。
 *
 * 描边以路径为中心向内、外各半，故整体内缩半个描边宽度：填充与描边都收敛在
 * View 边界内，箭头尖与四边不会多出一圈绘制越界。
 */
class GithubUpdateBadgeDrawable(color: Int, private val density: Float, rimColor: Int = 0) : Drawable() {
    private val strokeWidthPx = if (rimColor == 0) 0f else .8f * density
    private val strokeInset = strokeWidthPx / 2f
    private val bubble = BubbleDrawable(color, 6f * density, 3f * density, 5f * density, 5f * density,
        rimColor, strokeWidthPx)
    private var bodyHeight = 0

    override fun onBoundsChange(bounds: Rect) {
        // 垂直预算 = 总高 - 箭头 3dp - 上下各半个描边；水平同理左右内缩。
        bodyHeight = (bounds.height() - 3f * density - 2f * strokeInset).toInt().coerceAtLeast(0)
        // 左取上整、右取下整：整数 bounds 只会比理想内缩更保守，描边依然在界内。
        val left = ceil(strokeInset).toInt()
        val right = floor(bounds.width() - strokeInset).toInt().coerceAtLeast(left)
        bubble.setBounds(left, 0, right, bodyHeight)
    }

    override fun draw(canvas: Canvas) {
        if (bodyHeight <= 0) return
        val checkpoint = canvas.save()
        canvas.translate(bounds.left + strokeInset, bounds.top + strokeInset + bodyHeight.toFloat())
        canvas.scale(1f, -1f)
        bubble.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    override fun setAlpha(alpha: Int) { bubble.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { bubble.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
