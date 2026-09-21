package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material

import android.graphics.Bitmap
import android.animation.ValueAnimator
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.AmbientBackdropScene
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidMotionSurfaceFrameProvider
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.SamplingMatrixMath
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.roundToInt

/** Background-only frost. It never captures text, other windows, or live page content. */
internal class FrostedMaterialRenderer(private val palette: MonetColors, private val density: Float) : AutoCloseable {
    private val dark = ColorUtils.calculateLuminance(palette.background) < .5
    private var root: View? = null
    private var frame: ModernBackdropFrame? = null
    private var sampleShader: BitmapShader? = null
    internal var revealFraction = 0f
        private set
    private var revealAnimator: ValueAnimator? = null
    private val shaderMatrix = Matrix()
    private val samplingMatrices = ViewSamplingMatrix()
    private val samplePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rootPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val movedTransform = FloatArray(9)
    private val sourceTransform = FloatArray(9)
    private class SurfaceTransform {
        val target = FloatArray(9)
        val source = FloatArray(9)
    }
    private val surfaces = WeakHashMap<View, SurfaceTransform>()
    private val windows = WeakHashMap<View, ViewTreeObserver.OnScrollChangedListener>()
    private val lifecycle = FrostedMaterialLifecycle()
    private val generation: Long get() = lifecycle.generation
    private val closed: Boolean get() = lifecycle.isClosed
    private var width = 0
    private var height = 0
    private var failedWidth = 0
    private var failedHeight = 0
    private var workerStarted = false
    private var work: Future<*>? = null
    private val worker by lazy { Executors.newSingleThreadExecutor { task -> Thread(task, "BIL-SoftFrost").apply { isDaemon = true } } }
    private val handler = Handler(Looper.getMainLooper())
    private val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> requestBackdrop(r - l, b - t) }

    fun bindRoot(view: View): Boolean {
        if (closed || (root != null && root !== view)) return false
        if (root === view) return true
        root = view
        view.addOnLayoutChangeListener(layoutListener)
        view.background = object : Drawable() {
            private var drawingAlpha = 255
            override fun draw(canvas: Canvas) {
                val current = frame
                rootPaint.color = ColorUtils.setAlphaComponent(palette.background, drawingAlpha)
                canvas.drawRect(bounds, rootPaint)
                if (current != null) {
                    rootPaint.alpha = (drawingAlpha * revealFraction).toInt()
                    canvas.drawBitmap(current.original, null, bounds, rootPaint)
                }
            }
            override fun setAlpha(alpha: Int) { drawingAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
            override fun getAlpha() = drawingAlpha
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        requestBackdrop(view.width, view.height)
        return true
    }

    fun surface(color: Int, radiusDp: Float, role: SurfaceRole): Drawable =
        ModernSurfaceDrawable(this, color, radiusDp * density, density, ModernMaterialPolicy.surface(role, dark))

    private fun requestBackdrop(newWidth: Int, newHeight: Int) {
        if (!lifecycle.canWork || newWidth <= 0 || newHeight <= 0 || (width == newWidth && height == newHeight && (frame != null || work != null))) return
        if (failedWidth == newWidth && failedHeight == newHeight) return
        width = newWidth; height = newHeight
        val token = lifecycle.beginRequest() ?: return
        work?.cancel(true)
        val recipient = WeakReference(this)
        val colors = palette
        val scale = density
        val completion = handler
        workerStarted = true
        work = worker.submit {
            val result = runCatching { ModernBackdropFactory.create(colors, newWidth, newHeight, scale) }.getOrNull()
            completion.post { recipient.get()?.acceptBackdrop(token, result) }
        }
    }

    private fun acceptBackdrop(token: Long, result: ModernBackdropFrame?) {
        if (!lifecycle.accepts(token)) return
        work = null
        if (result == null) {
            failedWidth = width; failedHeight = height
            return // A readable neutral surface remains; retry only on resize or a later foreground session.
        }
        frame = result
        sampleShader = BitmapShader(result.blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        samplePaint.shader = sampleShader
        revealAnimator?.cancel()
        revealFraction = if (ValueAnimator.areAnimatorsEnabled()) 0f else 1f
        if (revealFraction == 0f) {
            revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 160L
                addUpdateListener {
                    if (!closed && generation == token) {
                        revealFraction = it.animatedValue as Float
                        root?.invalidate(); surfaces.keys.forEach(View::invalidate)
                    }
                }
                start()
            }
        }
        root?.invalidate()
        surfaces.keys.forEach(View::invalidate)
    }

    internal fun drawSample(canvas: Canvas, bounds: RectF, radius: Float, view: View?, opacity: Int): Boolean {
        if (closed || view == null) return false
        val current = frame ?: return false
        val sourceRoot = root ?: return false
        if (!samplingMatrices.bitmapToTarget(sourceRoot, view, current.blurred.width, current.blurred.height, shaderMatrix)) return false
        sampleShader?.setLocalMatrix(shaderMatrix)
        samplePaint.alpha = opacity
        canvas.drawRoundRect(bounds, radius, radius, samplePaint)
        return true
    }

    internal fun register(view: View) {
        if (!lifecycle.canWork) return
        val position = surfaces[view] ?: SurfaceTransform().also { surfaces[view] = it }
        if (!samplingMatrices.localToScreen(view, position.target)) position.target.fill(Float.NaN)
        val source = root
        if (source == null || !samplingMatrices.localToScreen(source, position.source)) position.source.fill(Float.NaN)
        val windowRoot = view.rootView
        if (!windows.containsKey(windowRoot)) {
            val observer = ViewTreeObserver.OnScrollChangedListener(::notifyPositionChanged)
            windowRoot.viewTreeObserver.addOnScrollChangedListener(observer)
            windows[windowRoot] = observer
        }
    }

    fun notifyPositionChanged() {
        if (!lifecycle.canWork) return
        val source = root
        val sourceValid = source != null && samplingMatrices.localToScreen(source, sourceTransform)
        val iterator = surfaces.entries.iterator()
        while (iterator.hasNext()) {
            val (view, position) = iterator.next()
            if (!view.isAttachedToWindow) { iterator.remove(); continue }
            if (!view.isShown) continue
            if (!sourceValid || !samplingMatrices.localToScreen(view, movedTransform) ||
                !SamplingMatrixMath.equal(position.target, movedTransform) ||
                !SamplingMatrixMath.equal(position.source, sourceTransform)) view.invalidate()
        }
    }

    fun releaseMemory() {
        lifecycle.invalidate()
        work?.cancel(true); work = null
        revealAnimator?.cancel(); revealAnimator = null; revealFraction = 0f
        // Never recycle a bitmap that can still be referenced by a hardware display list.
        frame = null; sampleShader = null; samplePaint.shader = null
        root?.invalidate(); surfaces.keys.forEach(View::invalidate)
    }

    fun stop() {
        lifecycle.stop()
        releaseMemory()
    }

    fun resume() {
        if (!lifecycle.resume()) return
        failedWidth = 0; failedHeight = 0
        root?.let { requestBackdrop(it.width, it.height) }
    }

    override fun close() {
        if (closed) return
        lifecycle.close()
        releaseMemory()
        root?.removeOnLayoutChangeListener(layoutListener)
        windows.forEach { (view, listener) ->
            view.viewTreeObserver.takeIf { it.isAlive }?.removeOnScrollChangedListener(listener)
        }
        windows.clear(); surfaces.clear(); root = null
        if (workerStarted) worker.shutdownNow()
    }
}

/** Prepared allows bind-before-onStart. Stopped and closed states cannot create or accept work. */
internal class FrostedMaterialLifecycle {
    private var stopped = false
    var isClosed = false
        private set
    var generation = 0L
        private set
    val canWork: Boolean get() = !stopped && !isClosed

    fun beginRequest(): Long? {
        if (!canWork) return null
        return ++generation
    }

    fun accepts(token: Long): Boolean = canWork && generation == token
    fun invalidate() { generation++ }
    fun stop() { stopped = true; invalidate() }
    fun resume(): Boolean {
        if (isClosed) return false
        stopped = false
        return true
    }
    fun close() { isClosed = true; stopped = true; invalidate() }
}

private data class ModernBackdropFrame(val original: Bitmap, val blurred: Bitmap)

private object ModernBackdropFactory {
    fun create(palette: MonetColors, width: Int, height: Int, density: Float): ModernBackdropFrame {
        val (w, h) = ModernMaterialPolicy.sampleSize(width, height)
        val original = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(original)
        val dark = ColorUtils.calculateLuminance(palette.background) < .5
        AmbientBackdropScene.paint(canvas, palette, w, h, dark)
        val pixels = IntArray(w * h)
        original.getPixels(pixels, 0, w, 0, 0, w, h)
        val blurredPixels = ModernBackdropBlur.blur(pixels, w, h, ModernMaterialPolicy.blurRadius(w, width, density))
        val blurred = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        blurred.setPixels(blurredPixels, 0, w, 0, 0, w, h)
        // 颗粒只进可见底图，不进模糊副本：打散渐变色带、给出细材质纹理，
        // 磨砂表面的采样底保持干净。
        AmbientBackdropScene.addGrain(pixels)
        original.setPixels(pixels, 0, w, 0, 0, w, h)
        original.prepareToDraw(); blurred.prepareToDraw()
        return ModernBackdropFrame(original, blurred)
    }
}

/** A direct background keeps its View callback, including in separately hosted Dialog windows. */
private class ModernSurfaceDrawable(
    private val renderer: FrostedMaterialRenderer?,
    private val color: Int,
    private val radius: Float,
    private val density: Float,
    private val style: ModernSurfaceStyle
) : Drawable() {
    private val rect = RectF()
    private val edgeRect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; strokeWidth = density.coerceAtLeast(1f) * .65f }
    private val edgeShader = LinearGradient(0f, 0f, 0f, 1f,
        ColorUtils.setAlphaComponent(Color.WHITE, style.upperEdgeAlpha),
        ColorUtils.setAlphaComponent(Color.WHITE, style.lowerEdgeAlpha), Shader.TileMode.CLAMP)
    private val edgeMatrix = Matrix()
    private var edgeTop = Float.NaN
    private var edgeBottom = Float.NaN
    private var drawingAlpha = 255

    init { edge.shader = edgeShader }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
    }

    override fun draw(canvas: Canvas) {
        val view = callback as? View
        val motionProvider = view as? LiquidMotionSurfaceFrameProvider
        var drawRadius = radius
        var drawColor = color
        if (motionProvider != null) {
            motionProvider.copyLiquidMotionBounds(rect)
            drawRadius = motionProvider.liquidMotionCornerRadiusPx()
            drawColor = motionProvider.liquidMotionFallbackColor()
        } else rect.set(bounds)
        // An uninitialized or collapsed motion frame must never fall back to the full View rectangle.
        if (rect.isEmpty || !rect.left.isFinite() || !rect.top.isFinite() ||
            !rect.right.isFinite() || !rect.bottom.isFinite() || !drawRadius.isFinite()) return
        drawRadius = drawRadius.coerceIn(0f, minOf(rect.width(), rect.height()) * .5f)
        // ARGB belongs to every caller, including ordinary diagnostic entry Views without a motion provider.
        val frameAlpha = FrostedMotionSurfaceAlpha.frameAlpha(drawColor, drawingAlpha)
        if (frameAlpha <= 0) return
        if (view != null) renderer?.register(view)
        val tintAlpha = (255 + (style.tintAlpha - 255) * (renderer?.revealFraction ?: 0f)).toInt()
        val overlayAlpha = tintAlpha * frameAlpha / 255
        // Preserve the host's ARGB opacity without allocating an offscreen saveLayer for each frame.
        val sampleAlpha = FrostedMotionSurfaceAlpha.sampleAlpha(frameAlpha, overlayAlpha)
        val sampled = renderer?.drawSample(canvas, rect, drawRadius, view, sampleAlpha) == true
        fill.color = ColorUtils.setAlphaComponent(drawColor, if (sampled) overlayAlpha else frameAlpha)
        canvas.drawRoundRect(rect, drawRadius, drawRadius, fill)
        // Motion hosts own their collapsing stroke. A second full-opacity edge would flash at handoff.
        if (motionProvider == null) {
            edgeRect.set(rect)
            edgeRect.inset(edge.strokeWidth / 2, edge.strokeWidth / 2)
            if (edgeTop != rect.top || edgeBottom != rect.bottom) {
                edgeTop = rect.top; edgeBottom = rect.bottom
                edgeMatrix.setScale(1f, rect.height().coerceAtLeast(1f))
                edgeMatrix.postTranslate(0f, rect.top)
                edgeShader.setLocalMatrix(edgeMatrix)
            }
            edge.alpha = frameAlpha
            canvas.drawRoundRect(edgeRect, (drawRadius - edge.strokeWidth / 2).coerceAtLeast(0f),
                (drawRadius - edge.strokeWidth / 2).coerceAtLeast(0f), edge)
        }
    }

    override fun setAlpha(alpha: Int) { drawingAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha() = drawingAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; edge.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    /**
     * 静态几何报告真实圆角；缺省实现是无半径矩形，会让弹性长按高光与 View 阴影
     * 都按方形裁剪。运动变形帧（motionProvider）只存在于 draw，outline 用静态
     * bounds + 构造半径即可——高光在 DOWN 时一次性取样。
     */
    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) outline.setEmpty()
        else outline.setRoundRect(bounds, radius)
    }
}

/** A source-over tint and sample together retain the exact caller-owned motion opacity. */
internal object FrostedMotionSurfaceAlpha {
    fun frameAlpha(color: Int, drawableAlpha: Int): Int =
        (color ushr 24) * drawableAlpha.coerceIn(0, 255) / 255

    fun sampleAlpha(frameAlpha: Int, overlayAlpha: Int): Int {
        val frame = frameAlpha.coerceIn(0, 255)
        val overlay = overlayAlpha.coerceIn(0, frame)
        if (overlay == 255) return 0
        return ((frame - overlay) * 255f / (255 - overlay)).roundToInt().coerceIn(0, 255)
    }
}

internal object ModernMaterialDrawables {
    // 与 ModernBackdropFactory 同一套色阶语言：顶部向 surface 轻抬、底部沉向
    // surfaceVariant——条款同意页等无皮肤兜底背景也不再是一块纯色。
    fun neutralWindow(palette: MonetColors): Drawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            ColorUtils.blendARGB(palette.background, palette.surface, .34f),
            palette.background,
            ColorUtils.blendARGB(palette.background, palette.surfaceVariant, .3f)
        ))

    fun fallback(color: Int, radiusPx: Float, density: Float, role: SurfaceRole, dark: Boolean): Drawable =
        ModernSurfaceDrawable(null, color, radiusPx, density, ModernMaterialPolicy.surface(role, dark))
}
