package com.Bilibili_Innocent_Lab.xposedmodule.ui.theme

import android.content.Context
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences

/**
 * 上一次成功取到的取色种子。
 *
 * **不是用户设置**：它是系统取色结果的派生缓存，因此刻意不进 `SettingsCatalog`——
 * 不参与设置备份、也不随授权文档发布给宿主。它唯一的职责是让一次瞬时取色失败
 * 不至于把整个界面掉成灰色（见 [MonetSeedPolicy]）。
 *
 * 写入走 `apply()`：这是缓存而非用户意图，不需要像 `MaterialColorSpecStore` 那样
 * 同步落盘再读回；读取发生在 Activity 创建期，与同一位置已有的规范读取同属一次偏好访问。
 */
internal object MonetSeedCache {
    const val PREF_KEY = "material_color_seed_cache"

    /** 无缓存返回 null；读失败按无缓存处理，绝不让取色路径因偏好异常而崩。 */
    fun read(context: Context): Int? = runCatching {
        val preferences = context.modulePreferences()
        if (!preferences.contains(PREF_KEY)) null else preferences.getInt(PREF_KEY, 0)
    }.getOrNull()

    fun remember(context: Context, seed: Int) {
        runCatching {
            context.modulePreferences().edit { putInt(PREF_KEY, seed) }
        }
    }
}
