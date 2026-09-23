@file:Suppress("SameParameterValue")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.view

import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.activity.SkinnedActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidChoiceDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.ModernPalette
import com.highcapable.betterandroid.ui.extension.component.base.isUiInNightMode
import com.highcapable.betterandroid.ui.extension.component.base.toPx
import com.highcapable.hikage.annotation.HikageView
import top.defaults.drawabletoolbox.DrawableBuilder

@HikageView
class MaterialSwitch(context: Context, attrs: AttributeSet?) : SwitchCompat(context, attrs) {

    /** Explicit catalog identity; never inferred from localized text. */
    internal var settingId: String? = null
    internal var supportsFavoriteToggle: Boolean = false
    private var stateObservers: MutableList<() -> Unit>? = null
    private var monetStyleApplied = false

    internal fun observeState(observer: () -> Unit): () -> Unit {
        val observers = stateObservers ?: mutableListOf<() -> Unit>().also { stateObservers = it }
        observers += observer
        return { observers.remove(observer) }
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        // The existing listener may reject/revert a request. Peers see the final authoritative state.
        stateObservers?.toList()?.forEach { it() }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        stateObservers?.toList()?.forEach { it() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 弹窗（Dialog）内的控件不在 Activity 根视图树里，收不到 stylePreparedSkinControls
        // 的遍历替换，只能停留在构造期硬编码的灰色样式上。这里在挂载时自取 Monet 配色兜底，
        // 保证任何容器里的开关都是 Material You 取色样式；同时附带整行按压涟漪——否则
        // 按压反馈只剩轨道那一小条的状态变化，没有覆盖整个控件的按压光晕。
        if (!monetStyleApplied) {
            monetStyleApplied = true
            applyMonetStyle()
        }
    }

    private fun applyMonetStyle() {
        val activity = generateSequence(context) { c -> (c as? ContextWrapper)?.baseContext }
            .filterIsInstance<SkinnedActivity>().firstOrNull()
        val palette = activity?.monetColors ?: ModernPalette.resolve(context)
        val density = resources.displayMetrics.density
        val thumbSize = (20 * density).toInt()
        fun choice(width: Int, height: Int, thumb: Boolean) = LiquidChoiceDrawable(
            width, height, density, palette.surface, palette.primary,
            palette.onPrimary, context.getColor(R.color.colorTextGray), false, thumb)
        thumbTintList = null
        trackTintList = null
        thumbDrawable = choice(thumbSize, thumbSize, thumb = true)
        trackDrawable = choice(thumbSize * 2, thumbSize, thumb = false)
        splitTrack = false
        // 新装的 drawable 起始状态为空，`setThumbDrawable`/`setTrackDrawable` 都不推状态；
        // 不显式刷新就要等下一次 drawableStateChanged()——换皮肤的 recreate() 发生在已获焦
        // 的窗口里，那一次根本不会来，已开启的开关于是停在未选中的灰色配色。
        refreshDrawableState()
        // 整行按压涟漪：primary 低透明度 + 圆角 mask，按压时光晕铺满整个控件而非只有轨道。
        val rippleMask = GradientDrawable().apply {
            cornerRadius = 10f * density
            setColor(Color.WHITE)
        }
        background = RippleDrawable(
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(palette.primary, 0x33)),
            ColorDrawable(Color.TRANSPARENT),
            rippleMask
        )
    }

    private fun trackColors(selected: Int, pressed: Int, normal: Int): ColorStateList {
        val colors = intArrayOf(selected, pressed, normal)
        val states = arrayOfNulls<IntArray>(3)
        states[0] = intArrayOf(android.R.attr.state_checked)
        states[1] = intArrayOf(android.R.attr.state_pressed)
        states[2] = intArrayOf()
        return ColorStateList(states, colors)
    }

    private val thumbColor
        get() = if (resources.configuration.isUiInNightMode) 0xFF7C7C7C else 0xFFCCCCCC

    init {
        trackDrawable = DrawableBuilder()
            .rectangle()
            .rounded()
            .solidColor(0xFF656565.toInt())
            .height(20.toPx(context))
            .cornerRadius(15.toPx(context))
            .build()
        thumbDrawable = DrawableBuilder()
            .rectangle()
            .rounded()
            .solidColor(Color.WHITE)
            .size(20.toPx(context), 20.toPx(context))
            .cornerRadius(20.toPx(context))
            .strokeWidth(8.toPx(context))
            .strokeColor(Color.TRANSPARENT)
            .build()
        trackTintList = trackColors(
            0xFF656565.toInt(),
            thumbColor.toInt(),
            thumbColor.toInt()
        )
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
}
