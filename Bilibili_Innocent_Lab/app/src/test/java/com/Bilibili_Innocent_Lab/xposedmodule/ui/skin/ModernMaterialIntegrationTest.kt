package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ModernMaterialIntegrationTest {
    private fun source(path: String): String = sequenceOf(
        File("src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$path"),
        File("app/src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/$path")
    ).first(File::isFile).readText()

    @Test fun neutralModulePaletteDoesNotChangeHostWallpaperPaletteImplementation() {
        val skin = source("ui/skin/activity/SkinnedActivity.kt")
        assertTrue(skin.contains("ModernPalette.resolve(this)"))
        assertFalse(source("ui/theme/MonetColors.kt").contains("ModernPalette"))
        val neutral = skin.substringAfter("internal fun neutralWindowBackground()").substringBefore("internal fun skinFloatingBackground")
        assertTrue(neutral.contains("ModernMaterialDrawables.neutralWindow(monetColors)"))
        assertFalse(neutral.contains("prepareSkinSession"))
        assertFalse(neutral.contains("SkinPrefs"))
    }

    @Test fun materialUsesOneSharedPreblurredUnderlayWithoutTakingLiquidOwnership() {
        val renderer = source("ui/skin/material/FrostedMaterialRenderer.kt")
        assertTrue(renderer.contains("ModernBackdropBlur.blur(pixels"))
        assertTrue(renderer.contains("BitmapShader(result.blurred"))
        assertTrue(renderer.contains("worker.submit"))
        assertTrue(renderer.contains("if (!lifecycle.accepts(token)) return"))
        assertTrue(renderer.contains("failedWidth == newWidth && failedHeight == newHeight"))
        assertFalse(renderer.contains("PixelCopy"))
        assertFalse(renderer.contains("claimLiquidRenderSession"))
        assertFalse(renderer.contains(".recycle()"))
        val session = source("ui/skin/runtime/ActivitySkinSession.kt")
        assertTrue(session.contains("if (requestedSkin != SkinId.LIQUID) return materialRenderer.bindRoot(root)"))
        assertTrue(session.contains("val owner = if (requestedSkin == SkinId.LIQUID)"))
        assertTrue(session.contains("materialRenderer.close()"))
    }

    @Test fun chromeRolesRemainDistinctAndGeometryRemainsCallerOwned() {
        val skin = source("ui/skin/activity/SkinnedActivity.kt")
        assertTrue(skin.contains("skinBackground(color, radiusDp, materialOutline = true, role = SurfaceRole.FLOATING)"))
        assertTrue(skin.contains("skinBackground(color, radiusDp, materialOutline = false, role = SurfaceRole.TOP_BAR)"))
        assertTrue(skin.contains("skinBackground(color, radiusDp, materialOutline = true, role = SurfaceRole.SELECTED_ITEM)"))
        val renderer = source("ui/skin/material/FrostedMaterialRenderer.kt")
        assertTrue(renderer.contains("radiusDp * density"))
        assertTrue(renderer.contains("windowRoot.viewTreeObserver.addOnScrollChangedListener(observer)"))
        assertTrue(renderer.contains("removeOnScrollChangedListener(listener)"))
        assertTrue(renderer.contains("ValueAnimator.areAnimatorsEnabled()"))
    }
}
