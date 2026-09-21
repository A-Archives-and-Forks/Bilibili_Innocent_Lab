package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import androidx.core.graphics.createBitmap
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import java.util.WeakHashMap

/**
 * 悬浮表面的实时"玻璃透镜"底图：把同一窗口里位于表面**下方**的内容层（由宿主 [bindSource]
 * 指定，通常是滚动页容器）在每次 pre-draw 时按低分辨率软件绘制到一小块位图，经预乘模糊与
 * [LensRefractionPolicy.remap] 透镜重采样后，作为 BitmapShader 供表面一笔画出。
 *
 * 只采样内容层自身（表面是它的兄弟，不在其中），因此没有反馈回路；采样区域被软件 Canvas
 * 的位图边界裁到表面外沿之内，内容层里落在外面的子 View 会被 quickReject 掉，开销与表面
 * 面积成正比而与页面复杂度无关。任何一次采样抛异常即永久退回静态磨砂（不再尝试），
 * 表面外观退化为原先的样子而不是崩溃。
 */
@MainThread
internal class LiveBackdropSampler(private val density: Float) {
    private class Entry {
        var scale = 0
        var margin = 0
        var sampleWidth = 0
        var sampleHeight = 0
        var outWidth = 0
        var outHeight = 0
        var sample: Bitmap? = null
        var sampleCanvas: Canvas? = null
        var pixels: IntArray? = null
        var out: IntArray? = null
        var texture: Bitmap? = null
        var shader: BitmapShader? = null
        var valid = false

        fun release() {
            valid = false
            shader = null
            texture?.recycle(); texture = null
            sample?.recycle(); sample = null
            sampleCanvas = null
            pixels = null
            out = null
            sampleWidth = 0; sampleHeight = 0; outWidth = 0; outHeight = 0
        }
    }

    private var source: View? = null
    private val entries = WeakHashMap<View, Entry>()
    private val roots = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()
    private val samplingMatrices = ViewSamplingMatrix()
    private val sourceToTarget = Matrix()
    private val shaderMatrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var dirty = true
    private var suspended = false
    private var failed = false
    private var closed = false

    val isActive: Boolean get() = !closed && !failed && !suspended && source != null

    fun bindSource(view: View) {
        if (closed) return
        source = view
        dirty = true
    }

    /** 表面 draw 时登记；同窗口首个表面顺带装上 pre-draw 采样钩子。 */
    fun register(view: View) {
        if (!isActive) return
        if (!entries.containsKey(view)) {
            entries[view] = Entry()
            dirty = true
        }
        val root = view.rootView ?: return
        if (!roots.containsKey(root)) {
            val listener = ViewTreeObserver.OnPreDrawListener { onPreDraw(); true }
            root.viewTreeObserver.addOnPreDrawListener(listener)
            roots[root] = listener
        }
    }

    /** 内容滚动/表面位移：下一帧 pre-draw 重采样。 */
    fun invalidate() {
        dirty = true
    }

    private fun onPreDraw() {
        if (!isActive) return
        val content = source ?: return
        if (!content.isAttachedToWindow || content.width <= 0 || content.height <= 0) return
        if (!dirty && !content.isDirty) return
        dirty = false
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (view, entry) = iterator.next()
            if (!view.isAttachedToWindow) { entry.release(); iterator.remove(); continue }
            if (!view.isShown || view.rootView !== content.rootView) { entry.valid = false; continue }
            val result = runCatching { sample(content, view, entry) }
            if (result.isFailure) {
                failed = true
                releaseAll()
                entries.keys.forEach(View::invalidate)
                return
            }
            if (result.getOrDefault(false)) view.invalidate()
        }
    }

    private fun sample(content: View, view: View, entry: Entry): Boolean {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) { entry.valid = false; return false }
        if (!samplingMatrices.sourceToTarget(content, view, sourceToTarget)) { entry.valid = false; return false }
        val marginPx = LensRefractionPolicy.marginPx(density)
        val scale = LensRefractionPolicy.sampleScale(width + 2 * marginPx, height + 2 * marginPx)
        val margin = ((marginPx + scale - 1) / scale).coerceAtLeast(1)
        val outWidth = ((width + scale - 1) / scale).coerceAtLeast(1)
        val outHeight = ((height + scale - 1) / scale).coerceAtLeast(1)
        val sampleWidth = outWidth + 2 * margin
        val sampleHeight = outHeight + 2 * margin
        if (entry.sampleWidth != sampleWidth || entry.sampleHeight != sampleHeight ||
            entry.outWidth != outWidth || entry.outHeight != outHeight) {
            entry.release()
            entry.sample = createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
            entry.sampleCanvas = Canvas(entry.sample!!)
            entry.pixels = IntArray(sampleWidth * sampleHeight)
            entry.out = IntArray(outWidth * outHeight)
            entry.texture = createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            entry.shader = BitmapShader(entry.texture!!, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            entry.sampleWidth = sampleWidth; entry.sampleHeight = sampleHeight
            entry.outWidth = outWidth; entry.outHeight = outHeight
        }
        entry.scale = scale
        entry.margin = margin
        val bitmap = entry.sample ?: return false
        val canvas = entry.sampleCanvas ?: return false
        bitmap.eraseColor(0)
        val save = canvas.save()
        // 位图像素 = (目标局部坐标 + 外沿) / scale；目标局部坐标 = M · 内容局部坐标。
        canvas.scale(1f / scale, 1f / scale)
        canvas.translate((margin * scale).toFloat(), (margin * scale).toFloat())
        canvas.concat(sourceToTarget)
        content.draw(canvas)
        canvas.restoreToCount(save)

        val pixels = entry.pixels ?: return false
        val out = entry.out ?: return false
        bitmap.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
        LensRefractionPolicy.premultiply(pixels)
        val blurred = ModernBackdropBlur.blur(
            pixels, sampleWidth, sampleHeight, LensRefractionPolicy.blurRadius(scale, density)
        )
        LensRefractionPolicy.remap(blurred, sampleWidth, sampleHeight, margin, out, outWidth, outHeight)
        LensRefractionPolicy.illuminate(out)
        LensRefractionPolicy.unpremultiply(out)
        entry.texture?.setPixels(out, 0, outWidth, 0, 0, outWidth, outHeight)
        entry.valid = true
        return true
    }

    /** 已有有效纹理才画；否则返回 false，表面退回静态磨砂。 */
    fun draw(canvas: Canvas, bounds: RectF, radius: Float, view: View, alpha: Int): Boolean {
        if (!isActive) return false
        val entry = entries[view] ?: return false
        if (!entry.valid) return false
        val shader = entry.shader ?: return false
        shaderMatrix.setScale(bounds.width() / entry.outWidth, bounds.height() / entry.outHeight)
        shaderMatrix.postTranslate(bounds.left, bounds.top)
        shader.setLocalMatrix(shaderMatrix)
        paint.shader = shader
        paint.alpha = alpha
        canvas.drawRoundRect(bounds, radius, radius, paint)
        return true
    }

    /** 内存压力/后台：丢掉全部位图，恢复后首帧重采样。 */
    fun releaseAll() {
        entries.values.forEach(Entry::release)
        paint.shader = null
        dirty = true
    }

    fun suspend() {
        suspended = true
        releaseAll()
    }

    fun resume() {
        if (closed) return
        suspended = false
        dirty = true
    }

    fun close() {
        if (closed) return
        closed = true
        releaseAll()
        roots.forEach { (root, listener) ->
            root.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        roots.clear()
        entries.clear()
        source = null
    }
}
