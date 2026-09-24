package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * 能被上层表面"盖住"的涟漪。
 *
 * 顶栏图标按钮的涟漪是 foreground，浅色主题下是 0x8C 白：点击后气泡面板在 ~250ms 内
 * 从按钮长出并盖住它，涟漪的退场动画却还要再跑几百毫秒，隔着半透明玻璃把图标区域
 * 提亮到比稳定打开态还亮（真机 176→219→199），读作"按钮深浅跳一下"。
 * [coverOpacity] 由气泡按表面覆盖度逐帧写入，涟漪颜色的 alpha 随之淡出；
 * RippleDrawable 在 API 31 以前没有取色接口，所以基色在构造时自己记下。
 */
internal class CoverableRippleDrawable(
    private val baseColor: Int,
    content: Drawable?,
    mask: Drawable?
) : RippleDrawable(ColorStateList.valueOf(baseColor), content, mask) {

    var coverOpacity = 0f
        set(value) {
            val clamped = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
            if (clamped == field) return
            field = clamped
            val alpha = (Color.alpha(baseColor) * (1f - clamped)).roundToInt()
            setColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(baseColor, alpha)))
            // API 31+ 的涟漪动画在 RenderThread 上跑，已经开始的那一次不再读新颜色；
            // 表面盖过一半后直接清掉热点（setVisible(false) 走 clearHotspots），
            // 此时按钮已在玻璃下，清掉不会被读成跳变。完全放开后再恢复可见。
            val shouldShow = clamped < COVERED_THRESHOLD
            if (shouldShow != isVisible) setVisible(shouldShow, false)
        }

    private companion object {
        const val COVERED_THRESHOLD = 0.5f
    }
}
