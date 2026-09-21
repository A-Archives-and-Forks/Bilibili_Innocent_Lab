package com.Bilibili_Innocent_Lab.xposedmodule.ui.theme

import android.content.Context
import android.content.res.Configuration

/** Module windows keep wallpaper accents while large surfaces remain neutral and readable. */
internal object ModernPalette {
    fun resolve(context: Context): MonetColors = from(
        MonetColors.fromWallpaper(context),
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    )

    fun from(accent: MonetColors, dark: Boolean): MonetColors = MonetColors(
        primary = accent.primary,
        onPrimary = accent.onPrimary,
        secondary = accent.secondary,
        tertiary = accent.tertiary,
        background = if (dark) 0xFF101114.toInt() else 0xFFF3F2F6.toInt(),
        surface = if (dark) 0xFF24252A.toInt() else 0xFFFCFBFE.toInt(),
        surfaceVariant = if (dark) 0xFF292A30.toInt() else 0xFFF8F7FB.toInt()
    )
}
