package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class ModernSurfaceStyle(val tintAlpha: Int, val upperEdgeAlpha: Int, val lowerEdgeAlpha: Int)

/** Pure appearance policy: caller-owned radii are deliberately never changed here. */
internal object ModernMaterialPolicy {
    const val MAX_BACKDROP_PIXELS = 160_000
    const val SAMPLE_SCALE = 0.20f

    fun sampleSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = minOf(SAMPLE_SCALE.toDouble(), sqrt(MAX_BACKDROP_PIXELS.toDouble() / (width.toDouble() * height)))
        var w = (width * scale).toInt().coerceAtLeast(1)
        var h = (height * scale).toInt().coerceAtLeast(1)
        // Extremely narrow windows can hit the minimum one-pixel axis.
        if (w.toLong() * h > MAX_BACKDROP_PIXELS) {
            if (w > h) w = (MAX_BACKDROP_PIXELS / h).coerceAtLeast(1)
            else h = (MAX_BACKDROP_PIXELS / w).coerceAtLeast(1)
        }
        return w to h
    }

    fun surface(role: SurfaceRole, dark: Boolean): ModernSurfaceStyle {
        val tint = when (role) {
            SurfaceRole.MODAL -> if (dark) 240 else 234
            SurfaceRole.FLOATING -> if (dark) 190 else 180
            SurfaceRole.TOP_BAR -> if (dark) 174 else 162
            SurfaceRole.SELECTED_ITEM -> if (dark) 218 else 210
            SurfaceRole.MOTION_SURFACE -> if (dark) 218 else 216
            SurfaceRole.FILLED_BUTTON -> 235
            SurfaceRole.TEXT_BUTTON -> if (dark) 166 else 158
            else -> if (dark) 206 else 204
        }
        val upper = when (role) {
            SurfaceRole.TOP_BAR -> 0
            SurfaceRole.MODAL -> if (dark) 34 else 120
            SurfaceRole.SELECTED_ITEM -> if (dark) 16 else 60
            else -> if (dark) 26 else 112
        }
        val lower = when (role) {
            SurfaceRole.TOP_BAR -> 0
            SurfaceRole.MODAL -> if (dark) 14 else 28
            SurfaceRole.SELECTED_ITEM -> if (dark) 5 else 12
            else -> if (dark) 10 else 24
        }
        return ModernSurfaceStyle(tint, upper, lower)
    }

    fun blurRadius(sampleWidth: Int, fullWidth: Int, density: Float): Int =
        (22f * density * sampleWidth / fullWidth.coerceAtLeast(1)).roundToInt().coerceIn(2, 18)
}

/** Three separable box passes approximate a Gaussian; only used off the UI thread on bounded bitmaps. */
internal object ModernBackdropBlur {
    fun blur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        require(width > 0 && height > 0 && width.toLong() * height == source.size.toLong())
        require(radius in 1..32)
        var input = source.copyOf()
        var output = IntArray(source.size)
        repeat(3) {
            pass(input, output, width, height, radius, horizontal = true)
            val swap = input; input = output; output = swap
            pass(input, output, width, height, radius, horizontal = false)
            val next = input; input = output; output = next
        }
        return input
    }

    private fun pass(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Backdrop replaced")
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val stride = if (horizontal) 1 else width
        val window = radius * 2 + 1
        for (line in 0 until lines) {
            val base = if (horizontal) line * width else line
            var a = 0; var r = 0; var g = 0; var b = 0
            fun accumulate(position: Int, direction: Int) {
                val color = input[base + position.coerceIn(0, length - 1) * stride]
                a += (color ushr 24) * direction
                r += ((color ushr 16) and 255) * direction
                g += ((color ushr 8) and 255) * direction
                b += (color and 255) * direction
            }
            for (offset in -radius..radius) accumulate(offset, 1)
            for (position in 0 until length) {
                output[base + position * stride] = ((a / window) shl 24) or
                    ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                accumulate(position - radius, -1)
                accumulate(position + radius + 1, 1)
            }
        }
    }
}
