package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.math.BigDecimal

/** 百分比整数沿用项目现有配置类型；0 表示跟随宿主，不引入 Float 偏好或协议类型。 */
internal object PlayerSpeedConfig {
    const val FOLLOW_HOST = 0
    /**
     * 0.1～8 倍，最多两位小数（百分比整数 10..800）。宿主 `setPlaySpeed` 在 Java 层不夹取（只把 2.0 换成 1.99），
     * 值原样交给原生播放器；原先的 0.25～4 与宿主菜单一致，不是宿主的硬限制。旧版保存的两位小数值天然仍在范围内。
     */
    const val MIN_PERCENT = 10
    const val MAX_PERCENT = 800
    const val MIN_MULTIPLIER = MIN_PERCENT / 100f
    const val MAX_MULTIPLIER = MAX_PERCENT / 100f
    val supportedPercents: Set<Int> = setOf(FOLLOW_HOST) + (MIN_PERCENT..MAX_PERCENT)
    private val decimalInput = Regex("(?:[0-9]{1,3}(?:\\.[0-9]{1,2})?|\\.[0-9]{1,2})")

    fun normalize(percent: Int): Int = percent.takeIf { it in supportedPercents } ?: FOLLOW_HOST

    fun multiplier(percent: Int): Float? = normalize(percent).takeIf { it != FOLLOW_HOST }?.div(100f)

    fun effectiveLongPressPercent(disabled: Boolean, percent: Int): Int =
        if (disabled) FOLLOW_HOST else normalize(percent)

    fun parseMultiplier(text: String): Int? = runCatching {
        val value = text.trim()
        if (!decimalInput.matches(value)) return@runCatching null
        BigDecimal(value).movePointRight(2).intValueExact().takeIf { it in supportedPercents }
    }.getOrNull()

    fun formatMultiplier(percent: Int): String =
        BigDecimal.valueOf(normalize(percent).toLong(), 2).stripTrailingZeros().toPlainString()
}
