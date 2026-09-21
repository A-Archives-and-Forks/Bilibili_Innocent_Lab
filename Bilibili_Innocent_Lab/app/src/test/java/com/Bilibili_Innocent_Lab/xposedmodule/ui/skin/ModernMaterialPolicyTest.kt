package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernBackdropBlur
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernMaterialPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.ModernPalette
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import org.junit.Assert.*
import org.junit.Test

class ModernMaterialPolicyTest {
    @Test fun backgroundBudgetIsBoundedForPhonesTabletsAndExtremeAspectRatios() {
        for ((width, height) in listOf(1 to 1, 1080 to 2400, 1440 to 3200, 3840 to 2160,
            1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val (w, h) = ModernMaterialPolicy.sampleSize(width, height)
            assertTrue(w > 0 && h > 0)
            assertTrue(w.toLong() * h <= ModernMaterialPolicy.MAX_BACKDROP_PIXELS)
            assertTrue(w <= width && h <= height)
        }
        assertTrue(runCatching { ModernMaterialPolicy.sampleSize(0, 100) }.isFailure)
    }

    @Test fun trueBlurPreservesConstantImagesAndDoesNotMutateTheSource() {
        val source = IntArray(13 * 7) { 0xFF253648.toInt() }
        val before = source.copyOf()
        val output = ModernBackdropBlur.blur(source, 13, 7, 3)
        assertArrayEquals(before, output)
        assertArrayEquals(before, source)
        assertNotSame(source, output)
    }

    @Test fun blurActuallySpreadsAnImpulseAndReducesHighFrequencyContrast() {
        val source = IntArray(25 * 25) { 0xFF000000.toInt() }
        source[12 * 25 + 12] = 0xFFFFFFFF.toInt()
        val output = ModernBackdropBlur.blur(source, 25, 25, 2)
        assertTrue((output[12 * 25 + 12] and 255) in 1..254)
        assertTrue((output[12 * 25 + 13] and 255) > 0)
        assertEquals(0xFF000000.toInt(), output[0])
        assertTrue(output.all { it ushr 24 == 255 })
        val stripes = IntArray(21 * 11) { if (it % 21 % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val soft = ModernBackdropBlur.blur(stripes, 21, 11, 3)
        val centerRow = (5 * 21 + 6..5 * 21 + 14).map { soft[it] and 255 }
        assertTrue(centerRow.max() - centerRow.min() < 32)
    }

    @Test fun degenerateBlurIsSafeAndInvalidBuffersAreRejected() {
        val pixel = intArrayOf(0xFF998877.toInt())
        assertArrayEquals(pixel, ModernBackdropBlur.blur(pixel, 1, 1, 18))
        assertTrue(runCatching { ModernBackdropBlur.blur(pixel, 2, 1, 2) }.isFailure)
        assertTrue(runCatching { ModernBackdropBlur.blur(pixel, 1, 1, 0) }.isFailure)
    }

    @Test fun surfacesAreNeutralWhileAccentAndSemanticColorsArePreserved() {
        val accents = MonetColors(0xFFAA22EE.toInt(), -1, 0xFF008866.toInt(), 0xFFAA6600.toInt(), 1, 2, 3)
        for (dark in listOf(false, true)) {
            val palette = ModernPalette.from(accents, dark)
            assertEquals(accents.primary, palette.primary)
            assertEquals(accents.secondary, palette.secondary)
            assertEquals(accents.tertiary, palette.tertiary)
            assertEquals(accents.onPrimary, palette.onPrimary)
            for (color in listOf(palette.surface, palette.surfaceVariant, palette.background)) {
                val channels = listOf((color ushr 16) and 255, (color ushr 8) and 255, color and 255)
                assertTrue(channels.max() - channels.min() <= 8)
                assertTrue(if (dark) channels.max() < 52 else channels.min() >= 240)
            }
        }
    }

    @Test fun roleHierarchyKeepsModalsReadableAndFloatingBarsLighterThanCards() {
        for (dark in listOf(false, true)) {
            val card = ModernMaterialPolicy.surface(SurfaceRole.CARD, dark)
            val modal = ModernMaterialPolicy.surface(SurfaceRole.MODAL, dark)
            val floating = ModernMaterialPolicy.surface(SurfaceRole.FLOATING, dark)
            assertTrue(modal.tintAlpha > card.tintAlpha)
            assertTrue(floating.tintAlpha < card.tintAlpha)
            SurfaceRole.entries.forEach { role ->
                val style = ModernMaterialPolicy.surface(role, dark)
                assertTrue(style.tintAlpha in 1..255)
                if (role == SurfaceRole.TOP_BAR) {
                    assertEquals(0, style.upperEdgeAlpha)
                    assertEquals(0, style.lowerEdgeAlpha)
                } else assertTrue(style.upperEdgeAlpha > style.lowerEdgeAlpha)
            }
        }
    }
}
