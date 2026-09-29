package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.highcapable.betterandroid.ui.extension.view.firstChildOrNull
import com.highcapable.betterandroid.ui.extension.view.childOrNull
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticJudge
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticPresets
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSensitivity
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSurface
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.MaxHeightScrollView
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.textToString
import com.highcapable.betterandroid.ui.extension.view.toast
import android.widget.CheckBox as NativeCheckBox
import android.widget.EditText as NativeEditText
import android.widget.FrameLayout as NativeFrameLayout
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/** JEV 当前配置的一行摘要；Key 只判断有无，绝不回显。 */
internal fun MainActivity.semanticJevSummaryText(): String {
    val prefs = prefs()
    val configured = !prefs.getString(RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY, "").isNullOrBlank()
    if (!configured) return getString(R.string.semantic_jev_summary_unconfigured)
    val sensitivity = when (SemanticSensitivity.fromId(prefs.getString(FeaturePreferences.SEMANTIC_JEV_SENSITIVITY, null))) {
        SemanticSensitivity.LOW -> R.string.semantic_jev_sensitivity_low_short
        SemanticSensitivity.MEDIUM -> R.string.semantic_jev_sensitivity_medium_short
        SemanticSensitivity.HIGH -> R.string.semantic_jev_sensitivity_high_short
    }
    val mode = if (prefs.getBoolean(FeaturePreferences.SEMANTIC_JEV_WAIT_FIRST_SCREEN, false)) {
        R.string.semantic_jev_mode_wait_short
    } else {
        R.string.semantic_jev_mode_pass_short
    }
    return getString(R.string.semantic_jev_summary_configured, getString(sensitivity), getString(mode))
}

/** 兼容区入口行：标题 + 摘要两行，与规则入口行同一结构。 */
internal fun MainActivity.semanticJevEntryText(): String =
    getString(R.string.semantic_jev_settings) + "\n" + semanticJevSummaryText()

/** 选中框滑动：与气泡取消同一条强调减速曲线。 */
private const val SELECTION_SLIDE_MS = 260L
private val selectionSlideCurve = PathInterpolator(0.2f, 0f, 0f, 1f)

/**
 * JEV 配置面板（实验性功能 → 兼容）：API Key、接口地址、灵敏度、首屏等待。
 *
 * - 打开时实时读偏好（不是 onCreate 快照），保存时一次写入；任何一项都只在点「保存」后生效。
 * - API Key 是 hook_config 运行时键，不在 SettingsCatalog，所以不进备份；输入框为密码类型。
 * - 输入框里只放短提示，完整说明放在框下方单独一行，避免长提示被截断。
 * - 地址非法时不保存、不关闭面板，避免把 Key 发到错误的地方。
 */
internal fun MainActivity.showSemanticJevSettingsDialog(anchor: View? = null, onSaved: () -> Unit) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val prefs = prefs()
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()

    container.addView(NativeTextView(this).apply {
        text = getString(R.string.semantic_jev_settings)
        textColor = getColor(R.color.colorTextDark)
        textSize = 17f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(-1, -2))

    val body = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }

    fun label(textRes: Int, top: Int) = NativeTextView(this).apply {
        text = getString(textRes)
        textColor = getColor(R.color.colorTextGray)
        textSize = 14f
    }.also { body.addView(it, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }) }

    fun help(textRes: Int, top: Int = 4) = NativeTextView(this).apply {
        text = getString(textRes)
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        alpha = 0.72f
        setLineSpacing(3 * density, 1f)
    }.also { body.addView(it, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }) }

    fun field(initial: String, hintRes: Int, secret: Boolean) = NativeEditText(this).apply {
        setText(initial)
        hint = getString(hintRes)
        textColor = getColor(R.color.colorTextDark)
        setHintTextColor(ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x99))
        textSize = 14f
        isSingleLine = true
        inputType = if (secret) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(monetColors.surfaceVariant)
            setStroke(density.toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x38))
        }
    }.also { body.addView(it, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }

    label(R.string.semantic_jev_api_key, 14)
    val keyField = field(
        prefs.getString(RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY, "").orEmpty(),
        R.string.semantic_jev_api_key_hint_short,
        secret = true
    )
    help(R.string.semantic_jev_api_key_help)
    label(R.string.semantic_jev_endpoint, 12)
    val endpointField = field(
        prefs.getString(FeaturePreferences.SEMANTIC_JEV_ENDPOINT, "").orEmpty(),
        R.string.semantic_jev_endpoint_hint_short,
        secret = false
    )
    help(R.string.semantic_jev_endpoint_help)

    label(R.string.semantic_jev_sensitivity, 14)
    var sensitivity = SemanticSensitivity.fromId(prefs.getString(FeaturePreferences.SEMANTIC_JEV_SENSITIVITY, null))
    val options = listOf(
        SemanticSensitivity.LOW to R.string.semantic_jev_sensitivity_low,
        SemanticSensitivity.MEDIUM to R.string.semantic_jev_sensitivity_medium,
        SemanticSensitivity.HIGH to R.string.semantic_jev_sensitivity_high
    )
    val picker = createSlidingChoice(
        labels = options.map { getString(it.second) },
        selectedIndex = options.indexOfFirst { it.first == sensitivity }
    ) { index -> sensitivity = options[index].first }
    body.addView(picker, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

    val waitSwitch = com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch(this, null).apply {
        text = getString(R.string.semantic_jev_wait_first_screen)
        textColor = getColor(R.color.colorTextDark)
        textSize = 14f
        isChecked = prefs.getBoolean(FeaturePreferences.SEMANTIC_JEV_WAIT_FIRST_SCREEN, false)
    }
    body.addView(waitSwitch, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    help(R.string.semantic_jev_wait_first_screen_tip, top = 2)

    val scrollCap = minOf(dp(460), (resources.displayMetrics.heightPixels * 0.56f).toInt())
    container.addView(MaxHeightScrollView(this, scrollCap).apply {
        addView(body, NativeFrameLayout.LayoutParams(-1, -2))
    }, NativeLinearLayout.LayoutParams(-1, -2))

    val buttons = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.HORIZONTAL; gravity = Gravity.END }
    buttons.addView(createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    }, NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    buttons.addView(createTermsActionButton(getString(R.string.semantic_jev_save), filled = true) {
        val endpoint = endpointField.textToString().trim()
        if (endpoint.isNotEmpty() && SemanticJudge.normalizeEndpoint(endpoint) == null) {
            toast(getString(R.string.semantic_jev_endpoint_invalid))
            return@createTermsActionButton
        }
        val key = keyField.textToString().trim().take(RemoteHookConfigContract.MAX_SEMANTIC_JEV_API_KEY_LENGTH)
        runCatching {
            prefs.edit {
                putString(RemoteHookConfigContract.KEY_SEMANTIC_JEV_API_KEY, key)
                putString(FeaturePreferences.SEMANTIC_JEV_ENDPOINT, endpoint)
                putString(FeaturePreferences.SEMANTIC_JEV_SENSITIVITY, sensitivity.id)
                putBoolean(FeaturePreferences.SEMANTIC_JEV_WAIT_FIRST_SCREEN, waitSwitch.isChecked)
            }
        }.onFailure { Log.e("BilibiliInnocentLab", "write semantic jev prefs failed", it) }
        dismissWithAnimation(dialog, container) {
            toast(getString(R.string.semantic_jev_saved))
            onSaved()
        }
    }, NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
    container.addView(buttons, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    presentModalDialog(dialog, container, anchor)
}

/**
 * 单选列表，选中框是一块独立的皮肤选中面：点新选项时它从旧位置连续滑到新位置（位置与高度一起插值），
 * 标题颜色同步渐变。首帧布局完成前不做动画，直接落在初始选中项上；动画可被下一次点击打断并从当前位置接续。
 */
private fun MainActivity.createSlidingChoice(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
): View {
    val frame = NativeFrameLayout(this)
    val indicator = createSelectionIndicator()
    frame.addView(indicator, NativeFrameLayout.LayoutParams(-1, 0))
    val rows = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
    frame.addView(rows, NativeFrameLayout.LayoutParams(-1, -2))
    val selectedColor = monetColors.primary
    val normalColor = getColor(R.color.colorTextGray)
    val density = resources.displayMetrics.density
    var current = selectedIndex.coerceIn(0, labels.lastIndex)
    var animator: ValueAnimator? = null

    fun titleOf(index: Int) = rows.childOrNull<ViewGroup>(index)?.firstChildOrNull<NativeTextView>()

    fun place(top: Float, height: Int) {
        indicator.translationY = top
        if (indicator.layoutParams.height != height) {
            indicator.layoutParams = indicator.layoutParams.apply { this.height = height }
        }
    }

    fun select(index: Int, animate: Boolean) {
        val target = rows.childOrNull<View>(index) ?: return
        val previous = current
        current = index
        animator?.cancel()
        if (!animate || !frame.isLaidOut) {
            place(target.top.toFloat(), target.height)
            labels.indices.forEach { titleOf(it)?.textColor = if (it == index) selectedColor else normalColor }
            return
        }
        val fromTop = indicator.translationY
        val fromHeight = indicator.height.takeIf { it > 0 } ?: target.height
        val fromColor = titleOf(previous)?.currentTextColor ?: normalColor
        val intoColor = titleOf(index)?.currentTextColor ?: normalColor
        val evaluator = ArgbEvaluator()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SELECTION_SLIDE_MS
            interpolator = selectionSlideCurve
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                place(fromTop + (target.top - fromTop) * t, (fromHeight + (target.height - fromHeight) * t).toInt())
                if (previous != index) titleOf(previous)?.textColor = evaluator.evaluate(t, fromColor, normalColor) as Int
                titleOf(index)?.textColor = evaluator.evaluate(t, intoColor, selectedColor) as Int
            }
            start()
        }
    }

    labels.forEachIndexed { index, label ->
        rows.addView(
            createGitHubMenuRow(label, "", highlight = false) {
                if (index != current) {
                    select(index, animate = true)
                    onSelected(index)
                }
            },
            NativeLinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = (4 * density).toInt() }
        )
    }
    rows.doOnLayout { select(current, animate = false) }
    frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            animator?.cancel()
        }
    })
    return frame
}

/** 各过滤面的开关键与勾选键。 */
internal fun semanticRulesKey(surface: SemanticSurface): String = when (surface) {
    SemanticSurface.DYNAMIC -> FeaturePreferences.DYNAMIC_SEMANTIC_FILTER_RULES
    SemanticSurface.DANMAKU -> FeaturePreferences.DANMAKU_SEMANTIC_FILTER_RULES
    SemanticSurface.COMMENT -> FeaturePreferences.COMMENT_SEMANTIC_FILTER_RULES
    SemanticSurface.VIDEO -> FeaturePreferences.VIDEO_SEMANTIC_FILTER_RULES
}

/** 当前勾选（实时读偏好；从未设置过时为默认勾选）。 */
internal fun MainActivity.semanticSelectedIds(surface: SemanticSurface): Set<String> {
    val raw = prefs().getString(semanticRulesKey(surface), null) ?: SemanticPresets.defaultSelection(surface)
    return SemanticPresets.selected(surface, raw).mapTo(linkedSetOf()) { it.id }
}

/** 入口行文字：「屏蔽类型」+ 已选数量。 */
internal fun MainActivity.semanticRulesEntryText(surface: SemanticSurface): String {
    val count = semanticSelectedIds(surface).size
    val summary = if (count == 0) getString(R.string.semantic_rules_summary_none)
    else getString(R.string.semantic_rules_summary, count)
    return getString(R.string.semantic_rules_title) + "\n" + summary
}

/**
 * 屏蔽类型勾选面板：列出该过滤面的全部预设，每项带一行说明，点「保存」一次写入。
 * 规则集合进入宿主缓存键，改勾选后旧判定自然作废（重启哔哩哔哩生效）。
 */
internal fun MainActivity.showSemanticRulesDialog(
    surface: SemanticSurface,
    anchor: View? = null,
    onSaved: () -> Unit
) {
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.semantic_rules_title)
        textColor = getColor(R.color.colorTextDark)
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.semantic_rules_dialog_tip)
        textColor = getColor(R.color.colorTextGray)
        textSize = 12f
        alpha = 0.72f
        setLineSpacing(4 * density, 1f)
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })

    val selected = semanticSelectedIds(surface)
    val list = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
    val boxes = SemanticPresets.of(surface).mapNotNull { rule ->
        val (labelRes, descRes) = SemanticRuleLabels.of(surface, rule.id) ?: return@mapNotNull null
        val box = NativeCheckBox(this).apply {
            text = getString(labelRes)
            textSize = 14f
            textColor = getColor(R.color.colorTextDark)
            isChecked = rule.id in selected
            isFocusable = true
        }
        list.addView(box, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        list.addView(NativeTextView(this).apply {
            text = getString(descRes)
            textColor = getColor(R.color.colorTextGray)
            textSize = 12f
            alpha = 0.72f
            setLineSpacing(3 * density, 1f)
        }, NativeLinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(12)
            marginEnd = dp(8)
            bottomMargin = dp(2)
        })
        rule.id to box
    }
    val listCap = minOf(dp(420), (resources.displayMetrics.heightPixels * 0.52f).toInt())
    container.addView(MaxHeightScrollView(this, listCap).apply {
        addView(list, NativeFrameLayout.LayoutParams(-1, -2))
    }, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

    val buttons = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.HORIZONTAL; gravity = Gravity.END }
    buttons.addView(createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    }, NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    buttons.addView(createTermsActionButton(getString(R.string.semantic_jev_save), filled = true) {
        val ids = boxes.filter { it.second.isChecked }.mapTo(hashSetOf()) { it.first }
        runCatching {
            prefs().edit { putString(semanticRulesKey(surface), SemanticPresets.encode(surface, ids)) }
        }.onFailure { Log.e("BilibiliInnocentLab", "write semantic rules failed", it) }
        dismissWithAnimation(dialog, container) {
            toast(getString(R.string.semantic_jev_saved))
            onSaved()
        }
    }, NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
    container.addView(buttons, NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    presentModalDialog(dialog, container, anchor)
}

/** 预设 id → (名称, 说明) 文案；显式对照表，不用 getIdentifier（资源收缩安全、可被 lint 检查）。 */
internal object SemanticRuleLabels {
    private val table: Map<SemanticSurface, Map<String, Pair<Int, Int>>> = mapOf(
        SemanticSurface.DYNAMIC to mapOf(
            "lottery" to (R.string.semantic_rule_dynamic_lottery to R.string.semantic_rule_dynamic_lottery_desc),
            "goods" to (R.string.semantic_rule_dynamic_goods to R.string.semantic_rule_dynamic_goods_desc),
            "sponsored" to (R.string.semantic_rule_dynamic_sponsored to R.string.semantic_rule_dynamic_sponsored_desc),
            "traffic" to (R.string.semantic_rule_dynamic_traffic to R.string.semantic_rule_dynamic_traffic_desc),
            "flame" to (R.string.semantic_rule_dynamic_flame to R.string.semantic_rule_dynamic_flame_desc),
            "abuse" to (R.string.semantic_rule_dynamic_abuse to R.string.semantic_rule_dynamic_abuse_desc),
            "bait" to (R.string.semantic_rule_dynamic_bait to R.string.semantic_rule_dynamic_bait_desc),
            "marketing" to (R.string.semantic_rule_dynamic_marketing to R.string.semantic_rule_dynamic_marketing_desc)
        ),
        SemanticSurface.DANMAKU to mapOf(
            "spoiler" to (R.string.semantic_rule_danmaku_spoiler to R.string.semantic_rule_danmaku_spoiler_desc),
            "flood" to (R.string.semantic_rule_danmaku_flood to R.string.semantic_rule_danmaku_flood_desc),
            "flame" to (R.string.semantic_rule_danmaku_flame to R.string.semantic_rule_danmaku_flame_desc),
            "abuse" to (R.string.semantic_rule_danmaku_abuse to R.string.semantic_rule_danmaku_abuse_desc),
            "promotion" to (R.string.semantic_rule_danmaku_promotion to R.string.semantic_rule_danmaku_promotion_desc),
            "checkin" to (R.string.semantic_rule_danmaku_checkin to R.string.semantic_rule_danmaku_checkin_desc),
            "offtopic" to (R.string.semantic_rule_danmaku_offtopic to R.string.semantic_rule_danmaku_offtopic_desc),
            "warning" to (R.string.semantic_rule_danmaku_warning to R.string.semantic_rule_danmaku_warning_desc)
        ),
        SemanticSurface.COMMENT to mapOf(
            "flame" to (R.string.semantic_rule_comment_flame to R.string.semantic_rule_comment_flame_desc),
            "abuse" to (R.string.semantic_rule_comment_abuse to R.string.semantic_rule_comment_abuse_desc),
            "sarcasm" to (R.string.semantic_rule_comment_sarcasm to R.string.semantic_rule_comment_sarcasm_desc),
            "fandom" to (R.string.semantic_rule_comment_fandom to R.string.semantic_rule_comment_fandom_desc),
            "polarize" to (R.string.semantic_rule_comment_polarize to R.string.semantic_rule_comment_polarize_desc),
            "promotion" to (R.string.semantic_rule_comment_promotion to R.string.semantic_rule_comment_promotion_desc),
            "spoiler" to (R.string.semantic_rule_comment_spoiler to R.string.semantic_rule_comment_spoiler_desc),
            "checkin" to (R.string.semantic_rule_comment_checkin to R.string.semantic_rule_comment_checkin_desc),
            "fishing" to (R.string.semantic_rule_comment_fishing to R.string.semantic_rule_comment_fishing_desc)
        ),
        SemanticSurface.VIDEO to mapOf(
            "clickbait" to (R.string.semantic_rule_video_clickbait to R.string.semantic_rule_video_clickbait_desc),
            "marketing" to (R.string.semantic_rule_video_marketing to R.string.semantic_rule_video_marketing_desc),
            "borderline" to (R.string.semantic_rule_video_borderline to R.string.semantic_rule_video_borderline_desc),
            "outrage" to (R.string.semantic_rule_video_outrage to R.string.semantic_rule_video_outrage_desc),
            "anxiety" to (R.string.semantic_rule_video_anxiety to R.string.semantic_rule_video_anxiety_desc),
            "repost" to (R.string.semantic_rule_video_repost to R.string.semantic_rule_video_repost_desc)
        )
    )

    fun of(surface: SemanticSurface, id: String): Pair<Int, Int>? = table[surface]?.get(id)

    /** 每个预设都要有文案，单测据此钉住"加了预设忘了加名称"。 */
    fun covers(surface: SemanticSurface): Boolean =
        SemanticPresets.of(surface).all { table[surface]?.containsKey(it.id) == true }
}
