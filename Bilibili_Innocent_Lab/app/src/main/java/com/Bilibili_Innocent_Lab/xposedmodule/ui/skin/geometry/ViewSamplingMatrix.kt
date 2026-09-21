package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry

import android.graphics.Matrix
import android.view.View
import androidx.annotation.MainThread

/**
 * Reusable local/screen/backdrop coordinate bridge, including ancestor matrices and window offsets.
 * Own one instance per renderer. It retains no Views and allocates nothing per call.
 * Uses public APIs available on API 27; View.transformMatrixToGlobal itself only became public in 29.
 */
@MainThread
internal class ViewSamplingMatrix {
    private val sourceGlobal = FloatArray(9)
    private val targetGlobal = FloatArray(9)
    private val result = FloatArray(9)
    private val inverse = FloatArray(9)
    private val step = FloatArray(9)
    private val screenOrigin = IntArray(2)

    fun localToScreen(view: View, out: Matrix): Boolean {
        if (!localToScreen(view, result)) return false
        out.setValues(result)
        return true
    }

    /** Float output is also suitable for a retained surface's complete transform footprint. */
    fun localToScreen(view: View, out: FloatArray): Boolean {
        if (!view.isAttachedToWindow) return false
        SamplingMatrixMath.identity(out)
        var current = view
        var depth = 0
        while (depth++ < 128) {
            // View.matrix includes translation, scale, rotation and their actual pivot.
            current.matrix.getValues(step)
            if (!SamplingMatrixMath.isFinite(step)) return false
            val parent = current.parent as? View
            SamplingMatrixMath.translateAfter(step,
                (current.left.toLong() - (parent?.scrollX ?: 0)).toFloat(),
                (current.top.toLong() - (parent?.scrollY ?: 0)).toFloat())
            SamplingMatrixMath.multiply(step, out, out)
            if (parent == null) {
                if (!current.isAttachedToWindow) return false
                current.getLocationOnScreen(screenOrigin)
                return SamplingMatrixMath.alignRootToScreen(out, step, screenOrigin[0], screenOrigin[1])
            }
            current = parent
        }
        return false
    }

    fun sourceToTarget(source: View, target: View, out: Matrix): Boolean =
        compose(source, target, 1f, 1f, out)

    fun bitmapToTarget(sourceRoot: View, target: View, bitmapWidth: Int, bitmapHeight: Int, out: Matrix): Boolean {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || sourceRoot.width <= 0 || sourceRoot.height <= 0) return false
        return compose(sourceRoot, target, sourceRoot.width.toFloat() / bitmapWidth,
            sourceRoot.height.toFloat() / bitmapHeight, out)
    }

    private fun compose(source: View, target: View, scaleX: Float, scaleY: Float, out: Matrix): Boolean {
        if (!localToScreen(source, sourceGlobal) || !localToScreen(target, targetGlobal) ||
            !SamplingMatrixMath.bitmapToTarget(sourceGlobal, targetGlobal, scaleX, scaleY, result, inverse)) return false
        out.setValues(result)
        return true
    }
}
