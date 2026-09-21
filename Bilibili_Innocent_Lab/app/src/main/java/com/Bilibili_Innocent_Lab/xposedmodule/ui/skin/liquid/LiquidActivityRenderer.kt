package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.highcapable.betterandroid.ui.component.activity.AppViewsActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundMode
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidParameters
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidRenderBackend
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** 动态形变表面向 Liquid Drawable 暴露当前帧，不把 renderer 泄漏给业务 View。 */
internal interface LiquidMotionSurfaceFrameProvider {
    fun copyLiquidMotionBounds(outBounds: RectF)
    fun liquidMotionCornerRadiusPx(): Float
    fun liquidMotionFallbackColor(): Int
}

/** Surface 的真实 Drawable 几何；只在已有对象上更新，实时反馈遮罩逐帧零分配。 */
private class LiquidSurfaceFootprint {
    val refreshState = LiquidSurfaceRefreshState()
    var left = 0
    var top = 0
    var right = 0
    var bottom = 0
    var radiusPx = 0f

    /**
     * 上一次录制 display list 时该表面在屏幕上的位置。
     *
     * `backdropOrigin` 这个 uniform 是在 draw 里按当时的 `getLocationOnScreen` 写入的，会被
     * Skia 快照进 display list。视图只是被移动（滚动改 RenderNode 位置、translation 动画）
     * 而没有失效时，display list 会带着**旧原点**重放，玻璃里的背景于是停在旧位置，直到下一次
     * 失效才突然对齐——这就是慢速滑动时控件内背景抖动的来源。记录原点是为了只失效真正移动过的
     * 表面。
     */
    var originX = Int.MIN_VALUE
        private set
    var originY = Int.MIN_VALUE
        private set

    val hasOrigin: Boolean
        get() = originX != Int.MIN_VALUE && originY != Int.MIN_VALUE

    fun update(bounds: Rect, radiusPx: Float, originX: Int, originY: Int) {
        left = bounds.left
        top = bounds.top
        right = bounds.right
        bottom = bounds.bottom
        this.radiusPx = radiusPx
        this.originX = originX
        this.originY = originY
    }

    fun matchesOrigin(x: Int, y: Int): Boolean = originX == x && originY == y
}

private class LiquidWindowRefresh(
    val observer: WeakReference<ViewTreeObserver>,
    val preDraw: ViewTreeObserver.OnPreDrawListener,
    val scroll: ViewTreeObserver.OnScrollChangedListener,
    val batch: LiquidRefreshBatch = LiquidRefreshBatch()
)

private class LiquidCaptureRequest(
    val ticket: LiquidCaptureRequestState.Ticket,
    val source: LiquidBackdropSource,
    val stableBackdrop: LiquidBackdropSource,
    val root: WeakReference<View>,
    val width: Int,
    val height: Int,
    // Exclusively borrowed until this request completes: no next request can rewind the mask meanwhile.
    val mask: Path,
    val maskReady: Boolean
)

/**
 * MainActivity 首批使用的 Activity 级 Liquid renderer。
 *
 * 每个实例持有一个稳定 root underlay；用户启用高负载模式后，Surface Drawable 可改采三缓冲
 * PixelCopy source。Bitmap、RuntimeShader、RenderEffect 都在绑定/切换路径创建，draw 只更新位置
 * 和 uniform。
 */
internal class LiquidActivityRenderer(
    private val activity: AppViewsActivity,
    private val palette: MonetColors
) : AutoCloseable {
    private val density = activity.resources.displayMetrics.density
    private val darkPalette = ColorUtils.calculateLuminance(palette.surface) < 0.5
    private val hardwareAccelerated = activity.isHardwareAccelerationRequested()
    private val realtimeCaptureRequested = LiquidRealtimeCaptureStore.isEnabled(activity)
    private val realtimeCaptureSupported = LiquidRealtimeCapturePolicy.isSupported(
        sdkInt = AndroidVersion.code,
        hardwareAccelerated = hardwareAccelerated
    )
    private val effectProfile = if (realtimeCaptureRequested && realtimeCaptureSupported) {
        LiquidEffectProfile.REALTIME_CAPTURE
    } else LiquidEffectProfile.STANDARD
    private val visualTuning = LiquidVisualTuningPolicy.resolve(
        dark = darkPalette
    )
    private val backgroundConfig = LiquidBackgroundStore.read(activity).config
    private val backgroundWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "liquid-background-loader").apply { isDaemon = true }
    }
    private val parameters: LiquidParameters = LiquidTokenResolver.resolve(
        tuning = visualTuning,
        profile = effectProfile
    )
    private val backendCandidates = LiquidCapabilityPolicy.candidateOrder(
        sdkInt = AndroidVersion.code,
        hardwareAccelerated = hardwareAccelerated
    )
    private val fallbackPlan = LiquidBackendFallbackPlan(backendCandidates)
    private val preparedDrivers = linkedMapOf<LiquidRenderBackend, LiquidBackendDriver>()
    private val backendFailures = linkedMapOf<LiquidRenderBackend, String>()
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = parameters.highlightWidthDp * density
    }
    // 模态边框高光：顶沿 WHITE、在固定竖向行程内渐隐到 BASE_RATIO，再经 paint.alpha 整体
    // 缩放——与勾选控件同一套"光从顶沿沉入边框"的语言。渐变建在局部坐标（0..fadePx），
    // 由 localMatrix 平移跟随面板位置，不随面板高度拉伸、也不在 draw 里重建。
    private val modalEdgeShader = LinearGradient(
        0f, 0f, 0f, MODAL_EDGE_FADE_DP * density,
        Color.WHITE,
        ColorUtils.setAlphaComponent(Color.WHITE, (255 * MODAL_EDGE_BASE_RATIO).roundToInt()),
        Shader.TileMode.CLAMP
    )
    private val modalEdgeMatrix = Matrix()
    private val modalEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = parameters.highlightWidthDp * density
        shader = modalEdgeShader
    }
    // 廉价路径的光晕带描边：无 shader 的均匀白，模拟折射 rim 的 Fresnel 圈。
    private val edgeBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val rootFallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.background
    }
    private val surfaceViews = WeakHashMap<View, LiquidSurfaceFootprint>()
    private val refreshWindows = WeakHashMap<View, LiquidWindowRefresh>()
    private val visibilityMatrix = FloatArray(9)
    private val retiredBackdropSources = LinkedHashSet<LiquidBackdropSource>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val performanceController =
        if (effectProfile == LiquidEffectProfile.REALTIME_CAPTURE) {
            LiquidPerformanceController(activity, ::onThermalStatusChanged)
        } else null
    private val realtimeCaptureCanvas = Canvas()
    private val realtimeCaptureMask = Path()
    private val realtimeCaptureBounds = Rect()
    private val suppressionScaleBounds = Rect()
    private var realtimeSamplePixelBudget = LiquidRealtimeCapturePolicy.TARGET_SAMPLE_PIXELS
    private val realtimeCaptureSourceRect = Rect()
    private val realtimeRootLocation = IntArray(2)
    private val movedSurfaceLocation = IntArray(2)
    private val refreshWindowLocation = IntArray(2)
    private val refreshWindowBounds = RectF()
    // Used only within one refresh pass; never retain a dismissed Dialog's root between callbacks.
    private var refreshWindowRoot: View? = null

    /**
     * 抑制遮罩是否已按**发起截图那一刻**的几何构建完成。
     *
     * `PixelCopy` 读的是最近一次已合成的帧，而回调最快也要下一帧才到。原实现在回调里用**当时**
     * 的 `getLocationOnScreen` 建遮罩，快速滑动时位置已经比截图内容前进了几十像素：
     * 一部分上一帧的玻璃没被抑制、原样留在截图里被再次折射（反馈残影），一部分干净背景反而被
     * 抹成底图。两条错位带每帧随滚动移动，就是"快速滑动仍抖动"。慢速滑动时错位只有几像素，
     * 所以看不出来。改为在发起截图时用当帧已绘制的 footprint 几何构建，工作量不变、时机对齐。
     */
    private var realtimeMaskReady = false

    /** 实测采集吞吐；只统计连续成功完成之间的间隔，失败/熔断/重建都会重置。 */
    private val captureThroughput = LiquidCaptureThroughputTracker()

    /** 吞吐自适应给出的刷新率上限；`null` 表示尚未降档。会话内只降不升。 */
    private var throughputRefreshRateCap: Float? = null

    /**
     * 预缩放到截图尺寸的稳定底图，供反馈抑制按 1:1 填充。
     *
     * 抑制原本用 0.25 倍的稳定底图逐帧**双线性放大**填进截图（1440p 上是 360×800 → 671×1490，
     * 约 2.9 倍面积），这是主线程上的软件光栅化，夹在 GPU→CPU 回读与纹理上传之间。预缩放一次后
     * 逐帧只剩 1:1 的 alpha 混合，输出内容不变（同一双线性滤波、同一源，只是重采样从每帧一次变成
     * 尺寸变化时一次）。代价是一张截图尺寸的位图（1,000,000 px 约 3.81 MiB），内存压力下释放。
     */
    private var suppressionUnderlay: Bitmap? = null
    private var suppressionUnderlayShader: BitmapShader? = null
    private var suppressionUnderlaySource: LiquidBackdropSource? = null
    private val suppressionPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var backendDriver: LiquidBackendDriver? = null

    /**
     * 当前 backdrop 只绑定到**正在使用**的后端。
     *
     * 旧实现每帧遍历 `preparedDrivers` 全量绑定：API 33 上 BLUR 后端永远不会被绘制，却仍在
     * 每帧 `discardDisplayList()` + `beginRecording()` 重录一个引用整张截图的 display list。
     * 现在改为记录"已绑定的 source"，切换后端时再补绑。
     */
    private val driverBoundSources = HashMap<LiquidRenderBackend, LiquidBackdropSource>()
    private var backdropSource: LiquidBackdropSource? = null
    private var realtimeBackdropSource: LiquidBackdropSource? = null
    private var realtimeCaptureSources: List<LiquidBackdropSource> = emptyList()
    private var realtimeCaptureNextIndex = 0
    private val captureRequests = LiquidCaptureRequestState()
    private var realtimeCaptureInFlight: LiquidCaptureRequest? = null
    private var realtimeCaptureFailureCount = 0
    private var realtimeCaptureSuspended = false
    private var realtimeFrameCallbackPosted = false
    private var realtimeNextCaptureNanos = Long.MAX_VALUE

    /**
     * 内容位移静默判定：滚动回调/显式位移会把已绑定的实时截屏变成过期采样源——PixelCopy
     * 至少滞后一帧，继续折射它会把旧位置的文字透进玻璃，形成沿滑动方向偏移的残影。
     * 位移活跃期间玻璃改采稳定底图，静止 [SCROLL_QUIET_MS] 后由下一次采集自动切回。
     */
    private var lastContentShiftNanos = 0L
    private var realtimeSamplingSuppressed = false
    private var scrollSettlePending = false
    private val scrollSettleCheck = Runnable { onScrollSettleCheck() }
    private var realtimeFrameIntervalNanos =
        LiquidRealtimeCapturePolicy.frameIntervalNanos(60f)
    private var realtimeTargetRefreshRate = 60f
    private var originalPreferredRefreshRate: Float? = null
    private var appliedPreferredRefreshRate: Float? = null
    private var originalPreferredDisplayModeId: Int? = null
    private var appliedPreferredDisplayModeId: Int? = null
    private var stretchOpticalIntensity = 1f
    /**
     * 当前回弹方向：-1 = 顶部下拉（表面上边缘发光）、+1 = 底部上拉、0 = 无。
     * 与 [stretchOpticalIntensity] 一起进 shader——方向只投到对应边缘，不再四边等亮。
     */
    private var stretchEdgeDirY = 0f
    private var activityVisible = false
    private var boundRoot: View? = null
    private var rootDrawable: LiquidRootDrawable? = null
    private var rootLayoutListener: View.OnLayoutChangeListener? = null
    private var rootScrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var backdropRebuildPosted = false
    private var customBackdropFuture: Future<*>? = null
    private var customBackdropRequest: String? = null
    private var customBackdropLoadGeneration = 0L
    private var customBackdropFailed = false
    private var onFirstVisibleDraw: (() -> Unit)? = null
    private var onFatalFailure: (() -> Unit)? = null
    private var successfulDraw = false
    private var healthPosted = false
    private var fatalPosted = false
    private var closed = false
    private val rootScreenLocation = IntArray(2)
    private val realtimeFrameCallback = Choreographer.FrameCallback(::onRealtimeFrame)

    val backend: LiquidRenderBackend?
        get() = backendDriver?.backend ?: fallbackPlan.current

    init {
        backendCandidates.forEach { candidate ->
            runCatching { createBackend(candidate) }
                .onSuccess { preparedDrivers[candidate] = it }
                .onFailure { throwable -> recordBackendFailure(candidate, throwable) }
        }
        selectCurrentPreparedBackend()
    }

    /**
     * 记录某个后端为什么用不了。
     *
     * `RuntimeShader` 在构造期由厂商驱动编译 AGSL，失败会直接抛异常。原实现把它整个吞掉，
     * 于是 Adreno 能跑、Mali 被拒这类跨驱动问题在用户侧只表现为"效果变朴素了"，没有任何可上报的
     * 线索。这里只保留异常类型与截断后的 message，不含任何用户数据。
     */
    private fun recordBackendFailure(backend: LiquidRenderBackend, throwable: Throwable) {
        val message = throwable.message
            ?.replace('\n', ' ')
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.take(MAX_BACKEND_FAILURE_MESSAGE)
        backendFailures[backend] = if (message == null) {
            throwable.javaClass.simpleName
        } else {
            "${throwable.javaClass.simpleName}: $message"
        }
    }

    /**
     * 当前后端之前那些更优先候选的失败原因；没有降级时为 null。
     *
     * 供设置页在后端名称旁展示，用户可以直接把它反馈回来，而不是只说"不好看"。
     */
    val backendDegradeReason: String?
        get() {
            val active = backendDriver?.backend ?: fallbackPlan.current ?: return null
            if (backendFailures.isEmpty()) return null
            return backendCandidates
                .takeWhile { it != active }
                .firstNotNullOfOrNull { candidate ->
                    backendFailures[candidate]?.let { "${candidate.name}: $it" }
                }
        }

    @MainThread
    fun bindRoot(
        root: View,
        onFirstVisibleDraw: () -> Unit,
        onFatalFailure: () -> Unit
    ): Boolean {
        if (closed) return false
        val existingRoot = boundRoot
        if (existingRoot != null && existingRoot !== root) return false
        this.onFirstVisibleDraw = onFirstVisibleDraw
        this.onFatalFailure = onFatalFailure
        if (existingRoot === root) return true

        boundRoot = root
        root.getLocationOnScreen(rootScreenLocation)
        val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom,
                                                            oldLeft, oldTop, oldRight, oldBottom ->
            val widthChanged = right - left != oldRight - oldLeft
            val heightChanged = bottom - top != oldBottom - oldTop
            if (widthChanged || heightChanged) {
                captureRequests.invalidate()
                scheduleBackdropRebuild(root)
            }
            root.getLocationOnScreen(rootScreenLocation)
        }
        rootLayoutListener = layoutListener
        root.addOnLayoutChangeListener(layoutListener)
        // 滚动监听不能省。`backdropOrigin` 在 draw 时写入并被快照进 display list，滚动只移动
        // RenderNode 而不重录，采样原点因此会滞留在旧位置；实时档的采样回调只有在 PixelCopy
        // 真正完成时才失效表面，而单飞回读的完成节奏远低于 UI 帧率，两者错开就表现为控件内
        // 背景抖动。这里保留监听，但只失效**位置真的变了**的表面，比原来的无条件全量失效更省。
        val scrollListener = ViewTreeObserver.OnScrollChangedListener {
            invalidateMovedSurfaces()
        }
        rootScrollListener = scrollListener
        root.viewTreeObserver.addOnScrollChangedListener(scrollListener)

        rebuildBackdrop(root)
        val drawable = LiquidRootDrawable(this, palette.background)
        rootDrawable = drawable
        root.background = drawable

        root.invalidate()
        configureRealtimeRefreshRate(root)
        if (activityVisible) scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.INITIAL_DELAY_MS)
        return true
    }

    @MainThread
    fun onActivityStarted() {
        if (closed) return
        activityVisible = true
        // 新会话重新从设备最高档开始探测；只降不升的策略靠会话边界自愈。
        captureThroughput.reset()
        throughputRefreshRateCap = null
        if (effectProfile == LiquidEffectProfile.REALTIME_CAPTURE &&
            !realtimeCaptureSuspended
        ) {
            performanceController?.start(realtimeFrameIntervalNanos)
            boundRoot?.let(::configureRealtimeRefreshRate)
        }
        scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.INITIAL_DELAY_MS)
    }

    @MainThread
    fun onActivityStopped() {
        activityVisible = false
        captureRequests.invalidate()
        clearScrollSuppression()
        removeRealtimeFrameCallback()
        performanceController?.stop()
        restorePreferredRefreshRate()
    }

    fun createSurfaceDrawable(
        fallbackColor: Int,
        radiusDp: Float,
        role: SurfaceRole
    ): Drawable = LiquidSurfaceDrawable(
        renderer = this,
        fallbackColor = fallbackColor,
        radiusPx = radiusDp.coerceAtLeast(0f) * density,
        role = role
    )

    /**
     * 将现有滚动容器包进透明 stretch viewport；失败时保持原层级，不上报皮肤失败。
     *
     * viewport 只让滚动前景共享同一个 Android 12+ stretch RenderNode（底层 Activity 背景保持
     * 静止），并按回弹距离提升表面光学强度；不再绘制任何边界采样环。
     */
    @MainThread
    @SuppressLint("ReplaceWithAndroidVersion")
    fun installStretchViewport(
        scrollTarget: View,
        isStretchAllowed: () -> Boolean
    ): View? {
        if (closed || Build.VERSION.SDK_INT < 31 ||
            !activity.isHardwareAccelerationRequested()
        ) {
            return null
        }
        return runCatching {
            LiquidStretchViewport.installAround(
                scrollTarget = scrollTarget,
                isStretchAllowed = isStretchAllowed,
                onStretchDistance = ::onStretchDistanceChanged
            )
        }.getOrNull()
    }

    @MainThread
    fun finishStretchViewport(view: View?) {
        (view as? LiquidStretchViewport)?.finishStretch()
    }

    private fun onStretchDistanceChanged(distance: Float, edge: LiquidStretchEdge) {
        if (closed || effectProfile != LiquidEffectProfile.REALTIME_CAPTURE) return
        val next = LiquidRealtimeCapturePolicy.stretchOpticalIntensity(distance)
        val nextDir = when (edge) {
            LiquidStretchEdge.TOP -> -1f
            LiquidStretchEdge.BOTTOM -> 1f
            LiquidStretchEdge.NONE -> 0f
        }
        // epsilon 挡住回弹尾段的亚感知步进（全程范围 0.85，0.004 ≈ 0.5%）；
        // 归零那帧 edge 变为 NONE、nextDir 必变，终态永远会发布，不会卡在半亮状态。
        if (abs(next - stretchOpticalIntensity) < 0.004f && nextDir == stretchEdgeDirY) return
        stretchOpticalIntensity = next
        stretchEdgeDirY = nextDir
        // 回弹不切换采样路径：玻璃覆盖区在截屏里本就被抑制遮罩换成稳定底图，过期
        // 像素进不了表面；而切到光学直采会让整圈边缘光在两条路径间乒乓闪烁
        // （2026-09-21 真机实证）。保持折射路径，方向性增益照常点亮回弹侧边缘。
        // 位移时间戳照常更新：若页面滑动已使抑制生效，回弹位移会顺延静默窗口。
        lastContentShiftNanos = System.nanoTime()
        invalidateRegisteredSurfaces()
    }

    @MainThread
    fun onTrimMemory(level: Int) {
        if (closed || !LiquidMemoryPolicy.shouldReleaseGraphics(level)) return
        releaseGraphicsForMemoryPressure()
    }

    @MainThread
    fun onLowMemory() {
        if (closed) return
        releaseGraphicsForMemoryPressure()
    }

    private fun releaseGraphicsForMemoryPressure() {
        releaseSuppressionUnderlay()
        suspendRealtimeCapture(releaseBuffers = true)
        // 高阶折射/模糊和实时三缓冲可以在压力下永久降级，但最多 2 MiB 的稳定 underlay
        // 仍是用户可见背景本身。释放它会让当前 Activity 无重建地退回纯色，表现为自定义
        // 图片“过一段时间丢失”；保留稳定 source，同时切到零额外资源的 TRANSLUCENT 表面。
        advanceToTranslucent()
        boundRoot?.invalidate()
        invalidateRegisteredSurfaces()
    }

    internal fun drawRoot(
        canvas: Canvas,
        bounds: Rect,
        alpha: Int,
        viewX: Int,
        viewY: Int,
        fallbackColor: Int
    ) {
        rootScreenLocation[0] = viewX
        rootScreenLocation[1] = viewY
        if (closed || fatalPosted) {
            rootFallbackPaint.color = ColorUtils.setAlphaComponent(fallbackColor, alpha)
            canvas.drawRect(bounds, rootFallbackPaint)
            return
        }
        val source = backdropSource
        if (source != null && !source.isClosed) {
            source.drawRoot(canvas, bounds, alpha)
        } else {
            rootFallbackPaint.color = ColorUtils.setAlphaComponent(fallbackColor, alpha)
            canvas.drawRect(bounds, rootFallbackPaint)
        }
    }

    internal fun drawSurface(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        alpha: Int,
        viewX: Int,
        viewY: Int,
        fallbackColor: Int,
        role: SurfaceRole,
        host: View? = null
    ) {
        val effectiveRadiusPx = radiusPx.coerceIn(
            0f,
            minOf(bounds.width(), bounds.height()).coerceAtLeast(0) * 0.5f
        )
        if (closed || fatalPosted) {
            overlayPaint.color = ColorUtils.setAlphaComponent(fallbackColor, alpha)
            canvas.drawRoundRect(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat(),
                effectiveRadiusPx, effectiveRadiusPx, overlayPaint
            )
            return
        }

        // Bitmap 截图等一次性软件 Canvas 只使用本次 fallback，不得永久销毁窗口的 GPU 后端。
        if (!canvas.isHardwareAccelerated) {
            drawSurfaceLayers(
                canvas = canvas,
                bounds = bounds,
                radiusPx = effectiveRadiusPx,
                alpha = alpha,
                fallbackColor = fallbackColor,
                role = role,
                translucentFallback = true
            )
            return
        }

        // 弹窗等外部窗口里的表面不能折射实时截屏：PixelCopy 只抓 Activity 窗口，
        // 采样到的是未被压暗/模糊的锐利底页，文字会穿透面板与内部控件混排。
        // 改采稳定底图的光学副本（默认渐变或预模糊自定义图），得到干净的磨砂分层。
        // 位移抑制期不走直采路径：驱动层已绑到稳定底图，shader 以 motionLite 单
        // 取样模式跑——折射弯曲对平滑底图无收益，但边缘光/通透全程与静止态一致，
        // 不再出现"切页瞬间高光消失再加载"的路径切换跳变（2026-09-21 真机实证）。
        val foreignWindow = host != null && host.rootView !== boundRoot?.rootView
        if (role == SurfaceRole.MODAL) {
            android.util.Log.d(
                "ModalGlass",
                "draw host=${host?.javaClass?.simpleName} bounds=$bounds " +
                    "r=$effectiveRadiusPx alpha=$alpha foreign=$foreignWindow " +
                    "off=${viewX - rootScreenLocation[0]},${viewY - rootScreenLocation[1]}"
            )
        }
        drawWithFallback { driver ->
            if (driver.backend != LiquidRenderBackend.TRANSLUCENT) {
                if (foreignWindow) {
                    // 填充透明度沿用折射路径的 glassContentAlpha：浮动条透出
                    // 真实下层内容，"对下取色"与主窗口一致。
                    backdropSource?.takeIf { !it.isClosed }?.drawOpticalRegion(
                        canvas = canvas,
                        localBounds = bounds,
                        radiusPx = effectiveRadiusPx,
                        rootOffsetX = (viewX - rootScreenLocation[0]).toFloat(),
                        rootOffsetY = (viewY - rootScreenLocation[1]).toFloat(),
                        alpha = (LiquidSurfaceAlphaPolicy.glassContentAlpha(role) * 255f)
                            .roundToInt()
                    )
                } else {
                    checkNotNull(realtimeBackdropSource ?: backdropSource) {
                        "GPU Liquid backend has no backdrop source"
                    }
                    driver.drawBackdrop(
                        canvas,
                        bounds,
                        effectiveRadiusPx,
                        viewX - rootScreenLocation[0],
                        viewY - rootScreenLocation[1],
                        if (effectProfile == LiquidEffectProfile.REALTIME_CAPTURE) {
                            // 浮动条常驻一档折射强度：真实下层透入时折射弯曲可见，
                            // 是"有光感的玻璃"而非磨砂贴片；回弹增益仍可继续叠上去。
                            if (role == SurfaceRole.FLOATING) {
                                maxOf(stretchOpticalIntensity, FLOATING_OPTICAL_FLOOR)
                            } else stretchOpticalIntensity
                        } else 1f,
                        if (effectProfile == LiquidEffectProfile.REALTIME_CAPTURE) {
                            stretchEdgeDirY
                        } else 0f,
                        LiquidSurfaceAlphaPolicy.glassContentAlpha(role),
                        motionLite = realtimeSamplingSuppressed
                    )
                }
            }
            drawSurfaceLayers(
                canvas = canvas,
                bounds = bounds,
                radiusPx = effectiveRadiusPx,
                alpha = alpha,
                fallbackColor = fallbackColor,
                role = role,
                translucentFallback = driver.backend == LiquidRenderBackend.TRANSLUCENT,
                luminousEdge = foreignWindow
            )
        }
        scheduleHealthConfirmationAfterDraw()
    }

    private fun drawSurfaceLayers(
        canvas: Canvas,
        bounds: Rect,
        radiusPx: Float,
        alpha: Int,
        fallbackColor: Int,
        role: SurfaceRole,
        translucentFallback: Boolean,
        luminousEdge: Boolean = false
    ) {
        val surfaceFraction = LiquidSurfaceAlphaPolicy.resolve(
            role = role,
            translucentFallback = translucentFallback,
            parameters = parameters
        )
        val surfaceAlpha = (surfaceFraction * alpha).toInt().coerceIn(0, 255)
        // 高阶玻璃使用中性的 surface 轻染色；fallback 才恢复业务传入的实色以保证可读性。
        val tintColor = when {
            role == SurfaceRole.SELECTED_ITEM -> ColorUtils.blendARGB(palette.surface, palette.primary, .06f)
            translucentFallback -> fallbackColor
            else -> palette.surface
        }
        overlayPaint.color = ColorUtils.setAlphaComponent(tintColor, surfaceAlpha)
        canvas.drawRoundRect(
            bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat(),
            radiusPx, radiusPx, overlayPaint
        )
        // Thin neutral separation; floating bars get a clearer edge without a saturated fill.
        val edgeAlpha = (parameters.highlightAlpha *
            LiquidSurfaceEdgePolicy.alphaMultiplier(role) * alpha).toInt().coerceIn(0, 255)
        val inset = outlinePaint.strokeWidth * 0.5f
        // 光学直采路径（位移抑制/外部窗口）不跑折射 shader：菲涅尔/镜面/焦散那条
        // 边缘光晕带整条缺席，只剩细描边——切页瞬间所有控件"边缘高光消失再加载"
        // 的观感正源于此。此路径统一改走顶沿提亮渐变描边，保留"边缘有光"的读感。
        val useLuminousEdge = edgeAlpha > 0 &&
            (luminousEdge || role == SurfaceRole.MODAL || role == SurfaceRole.FLOATING)
        if (useLuminousEdge) {
            // 高光收进边框线条：顶沿提亮、固定行程内落回基础描边色。
            // paint.alpha 对 shader 输出整体缩放，逐帧只改 alpha 与平移。
            // 浮动条共享同一套"光从顶沿沉入边框"的语言，与模态、勾选控件一致。
            // 廉价路径上普通角色的提亮收敛到 OPTICAL_EDGE_TOP_BOOST：真实折射 rim
            // 只有 1~2% 白度，过强的顶沿高光会读成描边而不是光。
            val topBoost = if (role == SurfaceRole.MODAL || role == SurfaceRole.FLOATING)
                MODAL_EDGE_TOP_BOOST else OPTICAL_EDGE_TOP_BOOST
            modalEdgePaint.alpha = (edgeAlpha * topBoost).toInt().coerceIn(0, 255)
            modalEdgeMatrix.setTranslate(0f, bounds.top.toFloat())
            modalEdgeShader.setLocalMatrix(modalEdgeMatrix)
            canvas.drawRoundRect(
                bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset,
                (radiusPx - inset).coerceAtLeast(0f),
                (radiusPx - inset).coerceAtLeast(0f),
                modalEdgePaint
            )
            if (luminousEdge) {
                // 折射 rim 的有效亮度只有 Fresnel≈0.025/specular≈0.06 量级——
                // 光晕带只是一层极淡的内圈辉光，不是亮环。单层 10dp 描边内缩半宽
                // 使外侧与表面边缘齐平（无需 clipPath），alpha 压到同一量级，
                // 只保留"边缘微微泛光"的读感，避免出现硬边描边轮廓。
                val bandW = OPTICAL_EDGE_BAND_DP * density
                edgeBandPaint.strokeWidth = bandW
                edgeBandPaint.alpha =
                    (edgeAlpha * OPTICAL_EDGE_BAND_ALPHA).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(
                    bounds.left + bandW * 0.5f, bounds.top + bandW * 0.5f,
                    bounds.right - bandW * 0.5f, bounds.bottom - bandW * 0.5f,
                    (radiusPx - bandW * 0.5f).coerceAtLeast(0f),
                    (radiusPx - bandW * 0.5f).coerceAtLeast(0f),
                    edgeBandPaint
                )
            }
        } else {
            outlinePaint.color = ColorUtils.setAlphaComponent(Color.WHITE, edgeAlpha)
            canvas.drawRoundRect(
                bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset,
                (radiusPx - inset).coerceAtLeast(0f),
                (radiusPx - inset).coerceAtLeast(0f),
                outlinePaint
            )
        }
    }

    private inline fun drawWithFallback(draw: (LiquidBackendDriver) -> Unit) {
        while (!closed) {
            val driver = backendDriver ?: if (selectCurrentPreparedBackend()) backendDriver else null
            if (driver == null) {
                dispatchFatalFailure()
                return
            }
            val result = runCatching { draw(driver) }
            if (result.isSuccess) {
                successfulDraw = true
                return
            }
            if (!advanceAfterFailure(driver.backend)) {
                dispatchFatalFailure()
                return
            }
        }
    }

    private fun scheduleBackdropRebuild(root: View) {
        if (closed || backdropRebuildPosted) return
        backdropRebuildPosted = true
        root.postOnAnimation {
            backdropRebuildPosted = false
            if (!closed && boundRoot === root) rebuildBackdrop(root)
        }
    }

    private fun rebuildBackdrop(root: View) {
        if (closed) return
        val width = root.width.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val height = root.height.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val existing = backdropSource
        if (existing != null && existing.fullWidth == width && existing.fullHeight == height) {
            scheduleCustomBackdropIfNeeded(root, width, height)
            return
        }

        val targetSize = LiquidBackdropSizingPolicy.resolve(width, height)
        if (existing != null &&
            existing.bitmap.width == targetSize.width &&
            existing.bitmap.height == targetSize.height
        ) {
            captureRequests.invalidate()
            existing.updateFullSize(width, height)
            bindPreparedBackendsToBackdrop(existing)
            root.invalidate()
            invalidateRegisteredSurfaces()
            scheduleCustomBackdropIfNeeded(root, width, height)
            return
        }

        // 先创建并绑定新 source，再让下一次 traversal 接收全部 invalidation 后断开旧 source；
        // close 只释放 Java 所有权而不调用 Bitmap.recycle()，不能把帧回调误当作 GPU fence。
        val created = runCatching {
            LiquidBackdropSource.create(palette, width, height)
        }.getOrNull()
        if (created == null) {
            if (existing == null) advanceToTranslucent()
            return
        }
        captureRequests.invalidate()
        created.markPublished()
        backdropSource = created
        bindPreparedBackendsToBackdrop(created)
        root.invalidate()
        invalidateRegisteredSurfaces()
        if (existing != null) retireBackdropAfterFrame(root, existing)
        scheduleCustomBackdropIfNeeded(root, width, height)
    }

    /**
     * 外部图片解码永远离开主线程和 Drawable.draw；首帧先使用自动 Monet source，完成后再原子
     * 切换。图片资产失败只保留自动 source，不触发 Liquid renderer 的后端/皮肤回滚。
     */
    private fun scheduleCustomBackdropIfNeeded(root: View, width: Int, height: Int) {
        if (closed || customBackdropFailed || backgroundConfig.mode != LiquidBackgroundMode.CUSTOM) {
            return
        }
        val assetId = requireNotNull(backgroundConfig.assetId)
        val existing = backdropSource
        if (existing?.customAssetId == assetId &&
            existing.fullWidth == width && existing.fullHeight == height
        ) {
            return
        }
        val target = LiquidBackdropSizingPolicy.resolve(width, height)
        val request = "$assetId:${target.width}x${target.height}:$width:$height"
        if (customBackdropRequest == request && customBackdropFuture?.isDone == false) return

        customBackdropFuture?.cancel(true)
        val generation = ++customBackdropLoadGeneration
        customBackdropRequest = request
        customBackdropFuture = backgroundWorker.submit {
            val bitmap = runCatching {
                LiquidBackgroundStore.decodeBackdrop(
                    context = activity.applicationContext,
                    config = backgroundConfig,
                    targetWidth = target.width,
                    targetHeight = target.height,
                    backgroundColor = palette.background,
                    dark = darkPalette
                )
            }.getOrNull()
            if (bitmap == null) {
                mainHandler.post {
                    if (!closed && generation == customBackdropLoadGeneration) {
                        customBackdropRequest = null
                        customBackdropFailed = true
                    }
                }
                return@submit
            }
            if (Thread.currentThread().isInterrupted) { bitmap.recycle(); return@submit }
            val source = runCatching {
                LiquidBackdropSource.fromCustomBitmap(bitmap, assetId, width, height, density)
            }.getOrElse {
                bitmap.recycle()
                if (!Thread.currentThread().isInterrupted) mainHandler.post {
                    if (!closed && generation == customBackdropLoadGeneration) {
                        customBackdropFailed = true
                        customBackdropRequest = null
                    }
                }
                return@submit
            }
            if (Thread.currentThread().isInterrupted) { source.discardUnpublished(); return@submit }
            val posted = mainHandler.post {
                if (closed || generation != customBackdropLoadGeneration || boundRoot !== root) {
                    source.discardUnpublished()
                    return@post
                }
                val currentWidth = root.width.takeIf { it > 0 }
                    ?: activity.resources.displayMetrics.widthPixels.coerceAtLeast(1)
                val currentHeight = root.height.takeIf { it > 0 }
                    ?: activity.resources.displayMetrics.heightPixels.coerceAtLeast(1)
                val currentTarget = LiquidBackdropSizingPolicy.resolve(currentWidth, currentHeight)
                if (currentWidth != width || currentHeight != height || currentTarget != target) {
                    source.discardUnpublished()
                    customBackdropRequest = null
                    scheduleBackdropRebuild(root)
                    return@post
                }
                if (runCatching { source.markPublished() }.isFailure) {
                    source.close() // Publication may have reached HWUI; never recycle this pair.
                    customBackdropRequest = null
                    customBackdropFailed = true
                    return@post
                }
                val previous = backdropSource
                captureRequests.invalidate()
                backdropSource = source
                customBackdropRequest = null
                bindPreparedBackendsToBackdrop(source)
                root.invalidate()
                invalidateRegisteredSurfaces()
                if (previous != null) retireBackdropAfterFrame(root, previous)
            }
            if (!posted) source.discardUnpublished()
        }
    }

    private fun retireBackdropAfterFrame(root: View, source: LiquidBackdropSource) {
        retiredBackdropSources += source
        root.postOnAnimation {
            if (retiredBackdropSources.remove(source)) source.close()
        }
    }

    /** Surface Drawable 在 draw 时更新真实几何；弱键避免 renderer 反向延长 View 生命周期。 */
    internal fun registerSurfaceView(
        view: View,
        bounds: Rect,
        radiusPx: Float,
        originX: Int,
        originY: Int
    ) {
        if (closed) return
        registerRefreshWindow(view.rootView)
        val footprint = surfaceViews[view] ?: LiquidSurfaceFootprint().also {
            surfaceViews[view] = it
        }
        footprint.update(bounds, radiusPx, originX, originY)
    }

    /**
     * 显式变换回调（按下缩放、弹性拖拽、导航条指示器位移等）不等于内容位移：
     * 按下缩放绕中心缩放、表面原点不变，此时抑制只会把底图 real→stable 白闪一下。
     * 抑制交给 [flushSurfaceRefresh] 在确认表面原点真的变化后再触发。
     */
    @MainThread
    fun notifyPositionChanged() {
        lastContentShiftNanos = System.nanoTime()
        queueSurfaceRefresh(contentChanged = false)
    }

    /**
     * 只失效采样原点已经过期的表面。
     *
     * 每次滚动回调做的是 O(表面数 × 层级) 的 `getLocationOnScreen` 比对，没有移动的表面不会被
     * 重录，也不会重跑折射 shader。
     */
    private fun invalidateMovedSurfaces() {
        lastContentShiftNanos = System.nanoTime()
        // OnScrollChangedListener 只在真实滚动位移时触发：内容已经在某个表面下方
        // 滑动（哪怕表面自身没动，滞后截屏也会把旧位置像素折射进去），立即抑制。
        suppressRealtimeSamplingWhileScrolling()
        queueSurfaceRefresh(contentChanged = false)
    }

    /**
     * 位移活跃期把玻璃采样从滞后截屏切到稳定底图：表面立刻按正确原点重录一次，
     * 滚动中不再折射旧位置像素，也不再为每一帧截图触发整组表面重录。
     */
    private fun suppressRealtimeSamplingWhileScrolling() {
        // 只门控效果档位，不门控"是否已有实时缓冲"：首帧采集完成前就开始的滑动同样需要
        // 抑制——否则那一小段手势既折射过期底图又继续触发每帧 PixelCopy。
        if (closed || realtimeSamplingSuppressed ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE) return
        val stable = backdropSource
        if (stable == null || stable.isClosed) return
        realtimeSamplingSuppressed = true
        driverBoundSources.clear()
        bindPreparedBackendsToBackdrop(stable)
        invalidateRegisteredSurfaces()
        if (!scrollSettlePending) {
            scrollSettlePending = true
            mainHandler.postDelayed(scrollSettleCheck, LiquidRealtimeCapturePolicy.SCROLL_QUIET_MS)
        }
    }

    private fun onScrollSettleCheck() {
        scrollSettlePending = false
        if (closed || !realtimeSamplingSuppressed) return
        val quietNanos = System.nanoTime() - lastContentShiftNanos
        // 回弹形变未归零时同样保持抑制：按住不动没有新位移回调，静默窗口会自然
        // 攒满——此时解除会让表面重录回折射路径，下一次位移又切回光学直采，
        // 边缘光在两条路径之间闪烁。形变归零后（intensity 回落 1）才允许解除。
        if (quietNanos < LiquidRealtimeCapturePolicy.SCROLL_QUIET_MS * NANOS_PER_MILLISECOND ||
            stretchOpticalIntensity > 1f
        ) {
            scrollSettlePending = true
            mainHandler.postDelayed(scrollSettleCheck, LiquidRealtimeCapturePolicy.SCROLL_QUIET_MS)
            return
        }
        realtimeSamplingSuppressed = false
        // 抑制期录制的都是光学直采路径，解除后要重录回折射路径——实时模式下随后的
        // 采集完成会再失效一次；采集已挂起（suspended）时则靠这次失效恢复玻璃观感。
        invalidateRegisteredSurfaces()
        // 立刻排一次新采集；完成时 handleRealtimeCaptureResult 会把实时缓冲绑回去。
        realtimeNextCaptureNanos = 0L
        postRealtimeFrameCallback()
    }

    private fun clearScrollSuppression() {
        realtimeSamplingSuppressed = false
        if (scrollSettlePending) {
            scrollSettlePending = false
            mainHandler.removeCallbacks(scrollSettleCheck)
        }
    }

    private fun invalidateRegisteredSurfaces() {
        queueSurfaceRefresh(contentChanged = true)
    }

    private fun registerRefreshWindow(windowRoot: View) {
        val observer = windowRoot.viewTreeObserver
        val existing = refreshWindows[windowRoot]
        if (existing?.observer?.get() === observer && observer.isAlive) return
        existing?.let(::removeRefreshWindow)
        val rootRef = WeakReference(windowRoot)
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            rootRef.get()?.let(::flushSurfaceRefresh)
            true
        }
        val scroll = ViewTreeObserver.OnScrollChangedListener {
            rootRef.get()?.let { refreshWindows[it]?.batch?.mark(contentChanged = false) }
        }
        refreshWindows[windowRoot] = LiquidWindowRefresh(WeakReference(observer), preDraw, scroll)
        observer.addOnPreDrawListener(preDraw)
        observer.addOnScrollChangedListener(scroll)
    }

    private fun removeRefreshWindow(state: LiquidWindowRefresh) {
        state.observer.get()?.takeIf { it.isAlive }?.let {
            it.removeOnPreDrawListener(state.preDraw)
            it.removeOnScrollChangedListener(state.scroll)
        }
    }

    private fun queueSurfaceRefresh(contentChanged: Boolean) {
        if (closed) return
        val iterator = refreshWindows.entries.iterator()
        while (iterator.hasNext()) {
            val (root, state) = iterator.next()
            if (!root.isAttachedToWindow) {
                removeRefreshWindow(state)
                iterator.remove()
            } else if (state.batch.mark(contentChanged) && contentChanged && root.isShown) {
                // New source pixels need a draw. Position owners already schedule their own frame.
                triggerSurfaceFrame(root)
            }
        }
    }

    /**
     * 最小损伤域的遍历触发：失效窗口内任一可见表面即可调度一帧，而它本就因
     * contentChanged 需要重录——损伤域只有一个表面的矩形。preDraw 的
     * [flushSurfaceRefresh] 再按 `shouldRefresh` 规则精确补齐其余表面。
     *
     * 旧实现用 `root.invalidate()`：整窗损伤会把页面里每个 View 的 display list 都
     * 标脏重录。滚动期每次 PixelCopy 完成、回弹期每次 stretch 强度步进都会走到这里，
     * 每秒数十次整窗重录就是"高级材质滑动掉帧"的主要链路。
     *
     * 找不到可见表面则本窗口无需这次绘制：CONTENT 标记留在 batch 里，窗口下一次遍历的
     * preDraw 仍会完整 flush（隐藏表面经 `skipped` 在重新可见时补偿刷新）。
     */
    private fun triggerSurfaceFrame(windowRoot: View) {
        val iterator = surfaceViews.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val view = entry.key
            if (!view.isAttachedToWindow) {
                iterator.remove()
                continue
            }
            if (view.rootView !== windowRoot || !view.isShown) continue
            view.invalidate()
            return
        }
    }

    /** Flush after all this window's animation callbacks, never on the first partial transform. */
    private fun flushSurfaceRefresh(windowRoot: View) {
        if (closed) return
        val changes = refreshWindows[windowRoot]?.batch?.take() ?: return
        if (changes == 0) return
        val contentChanged = changes and LiquidRefreshBatch.CONTENT != 0
        // 位移门控：只有某个表面真的改了屏幕原点才算"内容位移"。点击、按键或
        // 零位移的滚动回调同样会走这条链路，若在此刻切底图，所有玻璃会在一次
        // 无事发生的回调里 real→stable 闪一下。
        var surfaceMoved = false
        try {
            val surfaceIterator = surfaceViews.entries.iterator()
            while (surfaceIterator.hasNext()) {
                val entry = surfaceIterator.next()
                val view = entry.key
                if (!view.isAttachedToWindow) { surfaceIterator.remove(); continue }
                if (view.rootView !== windowRoot) continue
                val visible = isSurfacePotentiallyVisible(view)
                // 不可见表面的 movedSurfaceLocation 还是上一个表面的残留坐标，
                // 原值比对结果无意义；shouldRefresh 对 !visible 本就会忽略该参数。
                val originChanged = visible &&
                    !entry.value.matchesOrigin(movedSurfaceLocation[0], movedSurfaceLocation[1])
                if (originChanged) surfaceMoved = true
                if (entry.value.refreshState.shouldRefresh(visible,
                        originChanged = originChanged,
                        contentChanged = contentChanged)) view.invalidate()
            }
        } finally { refreshWindowRoot = null }
        // 抑制在 preDraw 内、draw 前生效：本帧被失效的位移表面重录时已经读到
        // motionLite + 稳定底图，不会产生"先按旧底图录一帧再切"的中间态。
        if (surfaceMoved && !contentChanged) suppressRealtimeSamplingWhileScrolling()
    }

    /**
     * Conservative window culling, not ancestor clipping. Keep the optical margin and all uncertain
     * transform/stretch frames. Each Dialog uses its own root; do not compare it to the Activity root.
     * Populates movedSurfaceLocation for the origin check without a second location query.
     */
    private fun isSurfacePotentiallyVisible(view: View): Boolean {
        if (!view.isShown) return false
        view.getLocationOnScreen(movedSurfaceLocation)
        if (stretchOpticalIntensity > 1f) return true
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor.animation != null || ancestor is LiquidMotionSurfaceFrameProvider) return true
            if (!ancestor.matrix.isIdentity) {
                ancestor.matrix.getValues(visibilityMatrix)
                if (!LiquidRefreshVisibilityPolicy.isTranslationOnly(visibilityMatrix)) return true
            }
            ancestor = ancestor.parent as? View
        }
        val windowRoot = view.rootView
        if (refreshWindowRoot !== windowRoot) {
            windowRoot.getLocationOnScreen(refreshWindowLocation)
            val left = refreshWindowLocation[0].toFloat()
            val top = refreshWindowLocation[1].toFloat()
            refreshWindowBounds.set(left, top, left + windowRoot.width, top + windowRoot.height)
            refreshWindowRoot = windowRoot
        }
        val left = movedSurfaceLocation[0].toFloat()
        val top = movedSurfaceLocation[1].toFloat()
        return LiquidRefreshVisibilityPolicy.intersectsWindow(left, top, left + view.width, top + view.height,
            refreshWindowBounds.left, refreshWindowBounds.top, refreshWindowBounds.right, refreshWindowBounds.bottom,
            parameters.effectPaddingDp * density)
    }

    /** 根据 display mode 与热状态请求窗口刷新率，并同步 ADPF 目标周期。 */
    @Suppress("DEPRECATION")
    private fun configureRealtimeRefreshRate(
        root: View,
        thermalStatus: Int = performanceController?.currentThermalStatus
            ?: LiquidPerformancePolicy.THERMAL_STATUS_NONE
    ) {
        if (effectProfile != LiquidEffectProfile.REALTIME_CAPTURE) return
        val display = root.display ?: return
        val currentMode = display.mode
        val matchingModes = display.supportedModes.asSequence()
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            .toList()
        val supportedRates = matchingModes.asSequence()
            .map { it.refreshRate }
            .toList()
        val requestedRefreshRate = LiquidRealtimeCapturePolicy.targetRefreshRate(
            currentRefreshRate = display.refreshRate,
            supportedRefreshRates = supportedRates
        )
        val thermalLimited = LiquidPerformancePolicy.targetRefreshRate(
            requestedRefreshRate = requestedRefreshRate,
            thermalStatus = thermalStatus
        )
        // 吞吐上限与热上限取更严格的一方；两者都只收紧、不放宽设备原始能力。
        realtimeTargetRefreshRate = throughputRefreshRateCap
            ?.let { minOf(thermalLimited, it) }
            ?: thermalLimited
        realtimeFrameIntervalNanos = LiquidRealtimeCapturePolicy.frameIntervalNanos(
            realtimeTargetRefreshRate
        )
        performanceController?.updateTargetWorkDuration(realtimeFrameIntervalNanos)
        val attributes = activity.window.attributes
        if (originalPreferredRefreshRate == null) {
            originalPreferredRefreshRate = attributes.preferredRefreshRate
            originalPreferredDisplayModeId = attributes.preferredDisplayModeId
        }
        val targetMode = matchingModes
            .filter { abs(it.refreshRate - realtimeTargetRefreshRate) <= 0.5f }
            .maxByOrNull { it.refreshRate }
        val targetModeId = targetMode?.modeId ?: 0
        if (abs(attributes.preferredRefreshRate - realtimeTargetRefreshRate) >= 0.01f ||
            attributes.preferredDisplayModeId != targetModeId
        ) {
            attributes.preferredRefreshRate = realtimeTargetRefreshRate
            attributes.preferredDisplayModeId = targetModeId
            activity.window.attributes = attributes
        }
        appliedPreferredRefreshRate = realtimeTargetRefreshRate
        appliedPreferredDisplayModeId = targetModeId
    }

    private fun onThermalStatusChanged(status: Int) {
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE
        ) {
            return
        }
        val root = boundRoot ?: return
        captureThroughput.reset()
        configureRealtimeRefreshRate(root, status)
        // 只降帧率仅减少"做几次"；同时降采样分辨率才能压住每次的回读与纹理上传量。
        val budget = LiquidPerformancePolicy.samplePixelBudget(thermalStatus = status)
        if (budget != realtimeSamplePixelBudget) {
            realtimeSamplePixelBudget = budget
            releaseRealtimeCaptureSources(rebindStableBackdrop = true)
        }
        realtimeNextCaptureNanos = System.nanoTime() + realtimeFrameIntervalNanos
    }

    /** 由 VSync 驱动目标最高 120Hz；PixelCopy 始终单飞，慢设备自然按完成速度降频。 */
    private fun scheduleRealtimeCapture(delayMs: Long) {
        if (boundRoot == null) return
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE
        ) {
            return
        }
        realtimeNextCaptureNanos = System.nanoTime() +
            delayMs.coerceAtLeast(0L) * NANOS_PER_MILLISECOND
        postRealtimeFrameCallback()
    }

    private fun postRealtimeFrameCallback() {
        if (realtimeFrameCallbackPosted || closed || !activityVisible ||
            realtimeCaptureSuspended || effectProfile != LiquidEffectProfile.REALTIME_CAPTURE
        ) {
            return
        }
        realtimeFrameCallbackPosted = true
        choreographer.postFrameCallback(realtimeFrameCallback)
    }

    private fun removeRealtimeFrameCallback() {
        if (!realtimeFrameCallbackPosted) return
        realtimeFrameCallbackPosted = false
        choreographer.removeFrameCallback(realtimeFrameCallback)
    }

    private fun onRealtimeFrame(frameTimeNanos: Long) {
        realtimeFrameCallbackPosted = false
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE
        ) {
            return
        }
        if (realtimeCaptureInFlight == null &&
            LiquidRealtimeCapturePolicy.isFrameDue(frameTimeNanos, realtimeNextCaptureNanos)
        ) {
            requestRealtimeCapture(frameTimeNanos)
        }
        postRealtimeFrameCallback()
    }

    private fun requestRealtimeCapture(frameTimeNanos: Long) {
        val root = boundRoot ?: return
        if (closed || !activityVisible || realtimeCaptureSuspended ||
            effectProfile != LiquidEffectProfile.REALTIME_CAPTURE ||
            realtimeCaptureInFlight != null || realtimeSamplingSuppressed
        ) {
            return
        }
        if (!root.isAttachedToWindow || !root.isShown ||
            root.windowVisibility != View.VISIBLE ||
            surfaceViews.isEmpty()
        ) {
            realtimeNextCaptureNanos = frameTimeNanos +
                LiquidRealtimeCapturePolicy.RETRY_DELAY_MS * NANOS_PER_MILLISECOND
            return
        }
        val captureSources = ensureRealtimeCaptureSources(root) ?: return
        val stable = backdropSource?.takeIf { !it.isClosed } ?: return
        val sourceIndex = realtimeCaptureNextIndex.mod(captureSources.size)
        val captureSource = captureSources[sourceIndex]
        realtimeCaptureNextIndex = (sourceIndex + 1).mod(captureSources.size)

        root.getLocationInWindow(realtimeRootLocation)
        realtimeCaptureSourceRect.set(
            realtimeRootLocation[0],
            realtimeRootLocation[1],
            realtimeRootLocation[0] + root.width,
            realtimeRootLocation[1] + root.height
        )
        // 必须在发起截图前构建：此刻 footprint 里保存的是最近一次绘制的位置，正是 PixelCopy
        // 即将读到的那一帧的几何。放到回调里构建会与截图内容错位。
        realtimeMaskReady = buildSuppressionMask(root, captureSource)
        val ticket = captureRequests.begin() ?: return
        val request = LiquidCaptureRequest(ticket, captureSource, stable, WeakReference(root), root.width, root.height,
            realtimeCaptureMask, realtimeMaskReady)
        realtimeCaptureInFlight = request
        realtimeNextCaptureNanos = frameTimeNanos + realtimeFrameIntervalNanos
        val recipient = WeakReference(this)
        val pixelCopyFinishedListener = PixelCopy.OnPixelCopyFinishedListener { result ->
            recipient.get()?.handleRealtimeCaptureResult(request, result)
        }
        val requested = runCatching {
            PixelCopy.request(
                activity.window,
                realtimeCaptureSourceRect,
                captureSource.bitmap,
                pixelCopyFinishedListener,
                mainHandler
            )
        }.isSuccess
        if (!requested) handleRealtimeCaptureResult(request, PixelCopy.ERROR_SOURCE_INVALID)
    }

    private fun handleRealtimeCaptureResult(request: LiquidCaptureRequest, result: Int) {
        if (realtimeCaptureInFlight !== request) return
        realtimeCaptureInFlight = null
        val completion = captureRequests.complete(request.ticket)
        val root = request.root.get()
        if (completion != LiquidCaptureRequestState.Completion.CURRENT || closed || !activityVisible ||
            realtimeCaptureSuspended || effectProfile != LiquidEffectProfile.REALTIME_CAPTURE ||
            root == null || boundRoot !== root || root.width != request.width || root.height != request.height ||
            request.source.isClosed || request.stableBackdrop !== backdropSource) return
        val captureSource = request.source
        val workStartedNanos = System.nanoTime()
        try {
            if (result == PixelCopy.SUCCESS) {
                val outcome = sanitizeRealtimeCapture(request)
                if (outcome != LiquidCaptureOutcome.FAILED) {
                    // 位图刚被改写，立刻提示 HWUI 预上传纹理；否则上传会推迟到下一帧 draw 中间，
                    // 变成 RenderThread 上的一次同步停顿。每帧一张约 3.81 MiB 的实时缓冲。
                    runCatching { captureSource.bitmap.prepareToDraw() }
                    applyCaptureThroughputSample(workStartedNanos)
                    // NO_GLASS_VISIBLE 说明本帧压根没画玻璃，截图里也就不含自身反馈，可直接
                    // 采用；把它计入熔断计数会让长列表滚动 33ms 就永久关掉整个实时效果。
                    realtimeCaptureFailureCount = 0
                    realtimeBackdropSource = captureSource
                    // 截图发起后开始的滚动会把这帧变成过期采样：保留缓冲但暂不绑定，
                    // 等位移静默后的下一帧采集再切回实时。
                    if (!realtimeSamplingSuppressed) {
                        bindPreparedBackendsToBackdrop(captureSource)
                    }
                    invalidateRegisteredSurfaces()
                    return
                }
            }

            // 失败会拉长下一次完成间隔，不能算进稳态吞吐。
            captureThroughput.reset()
            if (result != PixelCopy.ERROR_SOURCE_NO_DATA) realtimeCaptureFailureCount += 1
            if (LiquidRealtimeCapturePolicy.shouldSuspend(realtimeCaptureFailureCount)) {
                suspendRealtimeCapture(releaseBuffers = true)
            } else {
                realtimeNextCaptureNanos = System.nanoTime() +
                    LiquidRealtimeCapturePolicy.RETRY_DELAY_MS * NANOS_PER_MILLISECOND
            }
        } finally {
            performanceController?.reportActualWorkDuration(
                System.nanoTime() - workStartedNanos
            )
        }
    }

    /**
     * 记录一次成功完成，必要时按实测吞吐降一档刷新率。
     *
     * 只降不升：升档需要先请求更高刷新率才能观察可行性，"试探→失败→降回"会在相邻档位之间反复
     * 切换且肉眼可见。会话重建、热状态变化与缓冲重建都会重置统计，届时重新从设备最高档开始。
     */
    private fun applyCaptureThroughputSample(completionNanos: Long) {
        val shouldStepDown = captureThroughput.onCaptureCompleted(
            nowNanos = completionNanos,
            currentTargetFps = realtimeTargetRefreshRate
        )
        if (!shouldStepDown) return
        val root = boundRoot ?: return
        val display = root.display ?: return
        val currentMode = display.mode
        val supported = display.supportedModes.asSequence()
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            .map { it.refreshRate }
            .toList()
        val next = LiquidCaptureThroughputPolicy.stepDownTarget(
            currentTargetFps = realtimeTargetRefreshRate,
            measuredFps = captureThroughput.measuredFramesPerSecond,
            supportedRefreshRates = supported
        )
        captureThroughput.reset()
        if (next >= realtimeTargetRefreshRate - 0.5f) return
        throughputRefreshRateCap = next
        configureRealtimeRefreshRate(root)
    }

    /**
     * 准备与当前截图尺寸 1:1 的抑制底图；尺寸或稳定底图变化时重建。
     *
     * @return 可用时返回 true；分配失败按"本次不做抑制"处理，由调用方回退。
     */
    private fun ensureSuppressionUnderlay(
        stableBackdrop: LiquidBackdropSource,
        width: Int,
        height: Int
    ): Boolean {
        val cached = suppressionUnderlay
        if (cached != null && !cached.isRecycled &&
            cached.width == width && cached.height == height &&
            suppressionUnderlaySource === stableBackdrop && !stableBackdrop.isClosed
        ) {
            return true
        }
        releaseSuppressionUnderlay()
        if (width <= 0 || height <= 0 || stableBackdrop.isClosed) return false
        return runCatching {
            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            suppressionScaleBounds.set(0, 0, width, height)
            // 这一次放大与原逐帧填充使用同一滤波与同一源，输出内容一致。
            stableBackdrop.drawOpticalBackdrop(canvas, suppressionScaleBounds, 255)
            bitmap.prepareToDraw()
            val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            suppressionUnderlay = bitmap
            suppressionUnderlayShader = shader
            suppressionUnderlaySource = stableBackdrop
            suppressionPaint.shader = shader
            true
        }.getOrElse {
            releaseSuppressionUnderlay()
            false
        }
    }

    private fun releaseSuppressionUnderlay() {
        suppressionPaint.shader = null
        suppressionUnderlayShader = null
        suppressionUnderlaySource = null
        suppressionUnderlay = null
    }

    /**
     * 按**发起截图那一刻**的已绘制几何构建抑制遮罩。
     *
     * 位置一律取 footprint 在最近一次 draw 时记录的屏幕原点，而不是实时
     * `getLocationOnScreen`：`PixelCopy` 读的是最近一次已合成的帧，用当前坐标会在快速滑动时
     * 与截图内容错开几十像素。工作量与放在回调里构建完全相同。
     */
    private fun buildSuppressionMask(
        root: View,
        captureSource: LiquidBackdropSource
    ): Boolean {
        if (captureSource.isClosed || root.width <= 0 || root.height <= 0) return false
        val bitmap = captureSource.bitmap
        val scaleX = bitmap.width.toFloat() / root.width.toFloat()
        val scaleY = bitmap.height.toFloat() / root.height.toFloat()
        val rootOriginX = rootScreenLocation[0]
        val rootOriginY = rootScreenLocation[1]
        realtimeCaptureMask.rewind()
        realtimeCaptureMask.fillType = Path.FillType.WINDING
        var hasMask = false
        val paddingPx = parameters.effectPaddingDp * density
        val iterator = surfaceViews.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val surface = entry.key
            val footprint = entry.value
            if (!surface.isAttachedToWindow) {
                iterator.remove()
                continue
            }
            if (!surface.isShown || surface.alpha <= 0f || surface.rootView !== root.rootView) continue
            if (!footprint.hasOrigin) continue
            val rawLeft = footprint.originX - rootOriginX + footprint.left - paddingPx
            val rawTop = footprint.originY - rootOriginY + footprint.top - paddingPx
            val rawRight = footprint.originX - rootOriginX + footprint.right + paddingPx
            val rawBottom = footprint.originY - rootOriginY + footprint.bottom + paddingPx
            if (rawRight <= 0f || rawBottom <= 0f || rawLeft >= root.width ||
                rawTop >= root.height
            ) {
                continue
            }
            val left = (rawLeft * scaleX).coerceIn(0f, bitmap.width.toFloat())
            val top = (rawTop * scaleY).coerceIn(0f, bitmap.height.toFloat())
            val right = (rawRight * scaleX).coerceIn(0f, bitmap.width.toFloat())
            val bottom = (rawBottom * scaleY).coerceIn(0f, bitmap.height.toFloat())
            if (right > left && bottom > top) {
                realtimeCaptureMask.addRoundRect(
                    left,
                    top,
                    right,
                    bottom,
                    (footprint.radiusPx + paddingPx) * scaleX,
                    (footprint.radiusPx + paddingPx) * scaleY,
                    Path.Direction.CW
                )
                hasMask = true
            }
        }
        return hasMask
    }

    /**
     * Replace owned optical output with the clean underlay. Leaving any composite fraction would
     * recursively feed the module's own text and previous glass back into the next optical input.
     * Pixels outside the owned-output mask keep the live PixelCopy content.
     *
     * 遮罩几何在发起截图时就已按当帧绘制位置构建（[buildSuppressionMask]），这里只负责应用。
     * 返回值区分"没有可见玻璃"与"真的失败"，调用方只对后者累计熔断计数。
     */
    private fun sanitizeRealtimeCapture(
        request: LiquidCaptureRequest
    ): LiquidCaptureOutcome {
        val captureSource = request.source
        val stableBackdrop = request.stableBackdrop
        if (captureSource.isClosed || stableBackdrop.isClosed) return LiquidCaptureOutcome.FAILED
        if (!request.maskReady) return LiquidCaptureOutcome.NO_GLASS_VISIBLE

        val bitmap = captureSource.bitmap
        realtimeCaptureBounds.set(0, 0, bitmap.width, bitmap.height)
        realtimeCaptureCanvas.setBitmap(bitmap)
        return try {
            if (ensureSuppressionUnderlay(stableBackdrop, bitmap.width, bitmap.height)) {
                // Cached opaque underlay is copied 1:1; no recursive composite fraction or per-frame resampling.
                suppressionPaint.alpha = LiquidRealtimeCapturePolicy.BASE_SUPPRESSION_ALPHA
                realtimeCaptureCanvas.drawPath(request.mask, suppressionPaint)
            } else {
                // 预缩放位图分配失败时回退到原路径，抑制强度与几何完全一致。
                stableBackdrop.drawRootMasked(
                    realtimeCaptureCanvas,
                    request.mask,
                    realtimeCaptureBounds,
                    LiquidRealtimeCapturePolicy.BASE_SUPPRESSION_ALPHA
                )
            }
            LiquidCaptureOutcome.SUPPRESSED
        } finally {
            realtimeCaptureCanvas.setBitmap(null)
        }
    }

    private fun ensureRealtimeCaptureSources(root: View): List<LiquidBackdropSource>? {
        val width = root.width
        val height = root.height
        if (width <= 0 || height <= 0) {
            scheduleRealtimeCapture(LiquidRealtimeCapturePolicy.RETRY_DELAY_MS)
            return null
        }
        val target = LiquidRealtimeCapturePolicy.resolveSize(
            fullWidth = width,
            fullHeight = height,
            pixelBudget = realtimeSamplePixelBudget
        )
        val existing = realtimeCaptureSources
        if (existing.size == LiquidRealtimeCapturePolicy.BUFFER_COUNT &&
            existing.all {
                !it.isClosed && it.fullWidth == width && it.fullHeight == height &&
                    it.bitmap.width == target.width && it.bitmap.height == target.height
            }
        ) {
            return existing
        }

        releaseRealtimeCaptureSources(rebindStableBackdrop = true)
        val created = ArrayList<LiquidBackdropSource>(LiquidRealtimeCapturePolicy.BUFFER_COUNT)
        val result = runCatching {
            repeat(LiquidRealtimeCapturePolicy.BUFFER_COUNT) {
                val bitmap = createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(palette.background)
                created += LiquidBackdropSource.fromRealtimeBitmap(bitmap, width, height)
            }
            created.toList()
        }.getOrElse {
            created.forEach { source ->
                source.close()
                if (!source.bitmap.isRecycled) source.bitmap.recycle()
            }
            realtimeCaptureSuspended = true
            return null
        }
        realtimeCaptureSources = result
        realtimeCaptureNextIndex = 0
        return result
    }

    private fun suspendRealtimeCapture(releaseBuffers: Boolean) {
        realtimeCaptureSuspended = true
        clearScrollSuppression()
        removeRealtimeFrameCallback()
        performanceController?.stop()
        restorePreferredRefreshRate()
        if (releaseBuffers) releaseRealtimeCaptureSources(rebindStableBackdrop = true)
    }

    private fun releaseRealtimeCaptureSources(rebindStableBackdrop: Boolean) {
        captureRequests.invalidate()
        clearScrollSuppression()
        val stableBackdrop = backdropSource
        realtimeBackdropSource = null
        driverBoundSources.clear()
        // 缓冲尺寸变化会改变单次回读成本，旧吞吐样本不再代表当前配置。
        captureThroughput.reset()
        releaseSuppressionUnderlay()
        if (rebindStableBackdrop && stableBackdrop != null && !stableBackdrop.isClosed &&
            backendDriver?.backend != LiquidRenderBackend.TRANSLUCENT
        ) {
            bindPreparedBackendsToBackdrop(stableBackdrop)
        }
        // 抑制期录制的表面走的是光学直采路径；缓冲释放/挂起后必须重录回折射路径，
        // 否则它们会一直重放旧 display list（玻璃停在磨砂观感直到下次自然失效）。
        invalidateRegisteredSurfaces()
        realtimeCaptureSources.forEach(LiquidBackdropSource::close)
        realtimeCaptureSources = emptyList()
        realtimeCaptureNextIndex = 0
    }

    /** 只选择 bind 阶段已准备的实例；该方法允许从 draw 调用，但绝不创建图形资源。 */
    private fun selectCurrentPreparedBackend(): Boolean {
        while (!closed) {
            val candidate = fallbackPlan.current ?: return false
            val prepared = preparedDrivers[candidate]
            if (prepared == null) {
                fallbackPlan.advanceAfterFailure(candidate)
                continue
            }
            backendDriver = prepared
            return true
        }
        return false
    }

    /**
     * 只把新 backdrop 绑定到当前后端；其余后备驱动在真正被选中时再补绑。
     *
     * 逐帧全量绑定是纯浪费：`LiquidBlurBackendApi31.bindBackdrop` 每次都会丢弃并重录一个引用
     * 整张实时截图的 RenderNode display list，而 API 33 设备上它永远不会被绘制。
     */
    private fun bindPreparedBackendsToBackdrop(source: LiquidBackdropSource) {
        driverBoundSources.clear()
        if (!ensureCurrentDriverBound(source)) dispatchFatalFailure()
    }

    /** 绑定失败按后端失败处理并降级；成功后记录已绑定的 source，避免重复绑定。 */
    private fun ensureCurrentDriverBound(source: LiquidBackdropSource): Boolean {
        while (!closed) {
            val driver = backendDriver ?: if (selectCurrentPreparedBackend()) {
                backendDriver
            } else null
            if (driver == null) return false
            if (!driver.requiresBackdrop) return true
            if (driverBoundSources[driver.backend] === source) return true
            if (runCatching { driver.bindBackdrop(source) }.isSuccess) {
                driverBoundSources[driver.backend] = source
                return true
            }
            driverBoundSources.remove(driver.backend)
            preparedDrivers.remove(driver.backend)?.close()
            backendDriver = null
            if (fallbackPlan.advanceAfterFailure(driver.backend) == null) return false
        }
        return false
    }

    private fun advanceAfterFailure(failed: LiquidRenderBackend): Boolean {
        backendFailures.getOrPut(failed) { "runtime-draw-failed" }
        driverBoundSources.remove(failed)
        preparedDrivers.remove(failed)?.close()
        if (backendDriver?.backend == failed) backendDriver = null
        fallbackPlan.advanceAfterFailure(failed) ?: return false
        val activated = selectCurrentPreparedBackend()
        val source = realtimeBackdropSource ?: backdropSource
        if (activated && source != null && !source.isClosed && !ensureCurrentDriverBound(source)) {
            return false
        }
        invalidateRegisteredSurfaces()
        return activated
    }

    private fun advanceToTranslucent() {
        while (fallbackPlan.current != null &&
            fallbackPlan.current != LiquidRenderBackend.TRANSLUCENT
        ) {
            val failed = requireNotNull(fallbackPlan.current)
            preparedDrivers.remove(failed)?.close()
            if (backendDriver?.backend == failed) backendDriver = null
            fallbackPlan.advanceAfterFailure(failed)
        }
        if (backendDriver?.backend != LiquidRenderBackend.TRANSLUCENT) {
            backendDriver = null
            selectCurrentPreparedBackend()
        }
    }

    /** 直接 SDK guard 让 Android Lint 能静态证明下面两个 @RequiresApi 构造调用。 */
    @SuppressLint("ReplaceWithAndroidVersion")
    private fun createBackend(backend: LiquidRenderBackend): LiquidBackendDriver = when (backend) {
        LiquidRenderBackend.REFRACTION -> if (Build.VERSION.SDK_INT >= 33) {
            LiquidRefractionBackendApi33(parameters, density)
        } else error("RuntimeShader requires API 33")
        LiquidRenderBackend.BLUR -> if (Build.VERSION.SDK_INT >= 31) {
            LiquidBlurBackendApi31(parameters.blurRadiusDp * density)
        } else error("RenderEffect requires API 31")
        LiquidRenderBackend.TRANSLUCENT -> LiquidTranslucentBackend()
    }

    private fun dispatchFatalFailure() {
        if (closed || fatalPosted) return
        fatalPosted = true
        val root = boundRoot
        val callback = onFatalFailure
        if (root != null) root.post { if (!closed) callback?.invoke() }
        else callback?.invoke()
    }

    /** Drawable.draw 已真实成功返回后才排队确认，避免 OnDrawListener 的绘制前时序。 */
    private fun scheduleHealthConfirmationAfterDraw() {
        val root = boundRoot ?: return
        if (closed || fatalPosted || healthPosted || !successfulDraw || !root.isShown) return
        healthPosted = true
        root.post {
            if (!closed && !fatalPosted) onFirstVisibleDraw?.invoke()
        }
    }

    @MainThread
    override fun close() {
        if (closed) return
        closed = true
        activityVisible = false
        realtimeCaptureSuspended = true
        clearScrollSuppression()
        removeRealtimeFrameCallback()
        performanceController?.close()
        captureRequests.invalidate()
        customBackdropLoadGeneration += 1L
        customBackdropFuture?.cancel(true)
        customBackdropFuture = null
        customBackdropRequest = null
        backgroundWorker.shutdownNow()
        val root = boundRoot
        rootLayoutListener?.let { listener -> root?.removeOnLayoutChangeListener(listener) }
        rootLayoutListener = null
        rootScrollListener?.let { listener ->
            root?.viewTreeObserver?.takeIf { it.isAlive }
                ?.removeOnScrollChangedListener(listener)
        }
        rootScrollListener = null
        surfaceViews.clear()
        refreshWindows.values.forEach(::removeRefreshWindow)
        refreshWindows.clear()
        onFirstVisibleDraw = null
        onFatalFailure = null
        releaseSuppressionUnderlay()
        preparedDrivers.values.forEach(LiquidBackendDriver::close)
        preparedDrivers.clear()
        backendDriver = null
        releaseRealtimeCaptureSources(rebindStableBackdrop = false)
        realtimeCaptureCanvas.setBitmap(null)
        backdropSource?.close()
        backdropSource = null
        retiredBackdropSources.forEach(LiquidBackdropSource::close)
        retiredBackdropSources.clear()
        restorePreferredRefreshRate()
        boundRoot = null
        rootDrawable = null
    }

    @Suppress("DEPRECATION")
    private fun restorePreferredRefreshRate() {
        val applied = appliedPreferredRefreshRate ?: return
        val original = originalPreferredRefreshRate ?: return
        val attributes = activity.window.attributes
        val appliedModeId = appliedPreferredDisplayModeId
        val originalModeId = originalPreferredDisplayModeId
        var changed = false
        if (abs(attributes.preferredRefreshRate - applied) < 0.01f) {
            attributes.preferredRefreshRate = original
            changed = true
        }
        if (appliedModeId != null && originalModeId != null &&
            attributes.preferredDisplayModeId == appliedModeId
        ) {
            attributes.preferredDisplayModeId = originalModeId
            changed = true
        }
        if (changed) {
            activity.window.attributes = attributes
        }
        appliedPreferredRefreshRate = null
        originalPreferredRefreshRate = null
        appliedPreferredDisplayModeId = null
        originalPreferredDisplayModeId = null
    }
}

private const val NANOS_PER_MILLISECOND = 1_000_000L

/** 降级原因只保留有界长度，避免把驱动的长堆栈文本带进界面。 */
private const val MAX_BACKEND_FAILURE_MESSAGE = 160

/** 模态边框高光的竖向渐隐行程（dp）：顶部提亮只在面板最上方一段可见。 */
private const val MODAL_EDGE_FADE_DP = 64f

/** 顶沿提亮相对基础描边亮度的倍数；与 BASE_RATIO 相乘约等于 1，底端落回原亮度。 */
private const val MODAL_EDGE_TOP_BOOST = 2.2f
private const val MODAL_EDGE_BASE_RATIO = 0.45f

/**
 * 廉价路径光晕带与提亮：折射 rim 实测只有 Fresnel≈0.025 / specular≈0.06 的
 * 白度提升，光晕带按同一量级取极淡单层（10dp × 0.35×edgeAlpha）；普通角色
 * 的顶沿提亮也收敛到 1.6×——过强会读成描边环而不是光。
 */
private const val OPTICAL_EDGE_TOP_BOOST = 1.6f
private const val OPTICAL_EDGE_BAND_DP = 10f
private const val OPTICAL_EDGE_BAND_ALPHA = 0.35f

/**
 * 浮动条常驻的折射强度下限（驱动会钳到 1..1.85）：stretchDirY==0 时 shader 把
 * 增益按全向处理，整圈边缘的焦散/菲涅尔/镜面随之下调增量点亮，静止也有凝光；
 * 回弹方向出现后同一增益收拢到对应边缘。
 */
private const val FLOATING_OPTICAL_FLOOR = 1.15f

private class LiquidRootDrawable(
    private val renderer: LiquidActivityRenderer,
    private val fallbackColor: Int
) : Drawable() {
    private val location = IntArray(2)
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val view = callback as? View
        if (view != null) view.getLocationOnScreen(location)
        else {
            location[0] = 0
            location[1] = 0
        }
        renderer.drawRoot(
            canvas, bounds, drawableAlpha, location[0], location[1], fallbackColor
        )
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

private class LiquidSurfaceDrawable(
    private val renderer: LiquidActivityRenderer,
    private val fallbackColor: Int,
    private val radiusPx: Float,
    private val role: SurfaceRole
) : Drawable() {
    private val location = IntArray(2)
    private val motionBoundsF = RectF()
    private val motionBounds = Rect()
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val view = callback as? View
        if (view != null) {
            view.getLocationOnScreen(location)
        }
        else {
            location[0] = 0
            location[1] = 0
        }
        var drawBounds = bounds
        var drawRadiusPx = radiusPx
        var drawFallbackColor = fallbackColor
        var drawX = location[0]
        var drawY = location[1]
        val motionProvider = view as? LiquidMotionSurfaceFrameProvider
        if (motionProvider != null) {
            motionProvider.copyLiquidMotionBounds(motionBoundsF)
            if (motionBoundsF.width() > 0f && motionBoundsF.height() > 0f) {
                motionBounds.set(
                    floor(motionBoundsF.left).toInt(),
                    floor(motionBoundsF.top).toInt(),
                    ceil(motionBoundsF.right).toInt(),
                    ceil(motionBoundsF.bottom).toInt()
                )
                drawBounds = motionBounds
                drawRadiusPx = motionProvider.liquidMotionCornerRadiusPx()
                drawFallbackColor = motionProvider.liquidMotionFallbackColor()
                // drawX/drawY 保持 View 原点：两条采样链（drawOpticalRegion 的逆矩阵与折射
                // shader 的 backdropOrigin）都把画布坐标当作"原点+局部坐标"解算根坐标，
                // motionBounds 本身已是承载层画布内的绝对矩形，再叠 left/top 会让采样窗
                // 二次偏移到卡片右下方——形变全程显示的是偏离真实位置的底图区域，落定
                // 换回卡片 0 基 drawable 时采样区瞬移（"通透背景跳变加载"的来源）。
            }
        }
        if (view != null) {
            renderer.registerSurfaceView(view, drawBounds, drawRadiusPx, location[0], location[1])
        }
        renderer.drawSurface(
            canvas,
            drawBounds,
            drawRadiusPx,
            drawableAlpha,
            drawX,
            drawY,
            drawFallbackColor,
            role,
            host = view
        )
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** 静态几何报告真实圆角；缺省实现是无半径矩形，会让弹性长按高光按方形裁剪。 */
    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) outline.setEmpty()
        else outline.setRoundRect(bounds, radiusPx)
    }
}

private fun AppViewsActivity.isHardwareAccelerationRequested(): Boolean {
    val windowFlag = window.attributes.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    val appFlag = applicationInfo.flags and ApplicationInfo.FLAG_HARDWARE_ACCELERATED
    return windowFlag != 0 || appFlag != 0
}
