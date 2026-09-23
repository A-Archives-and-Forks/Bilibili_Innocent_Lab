package com.Bilibili_Innocent_Lab.xposedmodule.ui.theme

/**
 * 取色种子的来源优先级（纯函数，可在 JVM 单测里直接跑）。
 *
 * 背景：模块界面的全部强调色都由一个种子色经 HCT/TonalSpot 推导。种子取不到时旧实现
 * 直接退到中性灰 `0xFF656565`——灰色的 chroma≈0，推导出来的整套调色板**完全去饱和**，
 * 现场就是"取色设计失效，全界面没有颜色"。而调色板在 Activity 内缓存，一次取色失败会把
 * 界面锁死成灰色直到下次重建（切换高级材质正好是一次重建，所以症状在那之后出现）。
 *
 * 修法是把"单一来源 + 灰兜底"换成一条有序来源链，灰色退居最后一手：
 *
 * 1. [wallpaper] —— `WallpaperManager.getWallpaperColors(FLAG_SYSTEM)`。保持首位，
 *    取色成功时的观感与旧实现逐字一致。**它会为动态/直播壁纸与部分 OEM ROM 返回 null**，
 *    这正是故障来源。
 * 2. [systemAccent] —— 平台 Material You 强调色 `android.R.color.system_accent1_500`
 *    （API 31+，无需权限）。它由系统自己从壁纸/主题算出，还能反映用户在主题设置里
 *    **手动挑的颜色**，比壁纸取色更可得。
 * 3. [cached] —— 上一次成功取到的种子（持久化）。用于两个来源同时短暂不可用的时刻，
 *    保证界面不会因为一次瞬时失败就整屏掉色。
 * 4. [fallback] —— 中性灰。只有"从未取到过任何颜色"的全新设备才会走到这里。
 */
internal object MonetSeedPolicy {

    /** 按来源链解析种子色。 */
    fun resolve(
        wallpaper: Int?,
        systemAccent: Int?,
        cached: Int?,
        fallback: Int
    ): Int = wallpaper ?: systemAccent ?: cached ?: fallback

    /**
     * 是否值得写回缓存。
     *
     * 只缓存**实时来源**（壁纸/平台强调色）取到的值：缓存自己读出来的值再写回去没有意义，
     * 灰兜底更不能被写进缓存——一旦写进去，真正的取色恢复后仍会被它压着。
     */
    fun shouldRemember(live: Int?, cached: Int?): Boolean = live != null && live != cached
}
