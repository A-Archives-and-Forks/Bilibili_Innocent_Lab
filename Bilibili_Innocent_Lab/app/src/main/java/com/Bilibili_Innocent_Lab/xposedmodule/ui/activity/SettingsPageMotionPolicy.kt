package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

internal data class SettingsSwitchTouchBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Motion can pause under DOWN or settle before UP; both ownerships must finish before interaction resumes. */
internal class SettingsPageMotionLifecycle {
    private var motionActive = false
    private var gestureActive = false
    val isSettled: Boolean get() = !motionActive && !gestureActive

    fun beginMotion(): Boolean {
        val started = !motionActive
        motionActive = true
        return started
    }

    fun beginUserGesture(): Boolean {
        val started = !gestureActive
        gestureActive = true
        return started
    }

    fun finishMotion() { motionActive = false }
    fun finishUserGesture() { gestureActive = false }
}

/** Keyboard and accessibility navigation cancel earlier user work before changing the visible page. */
internal object SettingsPageUserNavigation {
    fun request(
        current: Int, target: Int, count: Int,
        onUserInteraction: () -> Unit, selectPage: (Int) -> Unit
    ): Boolean {
        if (target == current || target !in 0 until count.coerceIn(0, SettingsPageMotionPolicy.MAX_PAGES)) return false
        onUserInteraction()
        selectPage(target)
        return true
    }
}

/** Page coordinates increase in reading order; pixels are converted only at the View boundary. */
internal object SettingsPageMotionPolicy {
    const val MAX_PAGES = 4
    const val EDGE_LIMIT = .18f
    private const val PAGE_THRESHOLD = .22f
    private const val FLING_THRESHOLD = .5f

    fun lastPage(count: Int): Int = (count.coerceIn(0, MAX_PAGES) - 1).coerceAtLeast(0)
    fun selected(index: Int, count: Int): Int = index.coerceIn(0, lastPage(count))
    fun direction(rtl: Boolean): Float = if (rtl) -1f else 1f

    fun physicalPageTarget(current: Int, physicalDirection: Int, rtl: Boolean): Int {
        val step = when {
            physicalDirection < 0 -> -1
            physicalDirection > 0 -> 1
            else -> 0
        }
        return current + step * direction(rtl).toInt()
    }

    fun isPageVisible(index: Int, position: Float, count: Int): Boolean =
        index in 0 until count.coerceIn(0, MAX_PAGES) && position.isFinite() && abs(index - position) < 1f

    /** Drawable bounds are already in switch-local coordinates, including the RTL placement. */
    fun protectsSwitchTouch(
        x: Float, y: Float, width: Int, height: Int, geometryReady: Boolean,
        track: SettingsSwitchTouchBounds?, thumb: SettingsSwitchTouchBounds?, slop: Int
    ): Boolean {
        fun valid(bounds: SettingsSwitchTouchBounds?) = bounds != null && bounds.left >= 0 && bounds.top >= 0 &&
            bounds.right > bounds.left && bounds.bottom > bounds.top && bounds.right <= width && bounds.bottom <= height
        if (!geometryReady || width <= 0 || height <= 0 || !x.isFinite() || !y.isFinite() || slop < 0 ||
            !valid(track) || !valid(thumb)) return true
        val left = minOf(track!!.left, thumb!!.left).toFloat() - slop
        val top = minOf(track.top, thumb.top).toFloat() - slop
        val right = maxOf(track.right, thumb.right).toFloat() + slop
        val bottom = maxOf(track.bottom, thumb.bottom).toFloat() + slop
        return x >= left && x <= right && y >= top && y <= bottom
    }

    fun logicalDelta(pixels: Float, width: Int, rtl: Boolean): Float =
        if (width <= 0 || !pixels.isFinite()) 0f else -pixels / width / direction(rtl)

    fun resistedPosition(raw: Float, count: Int): Float {
        if (!raw.isFinite() || count <= 1) return 0f
        val last = lastPage(count).toFloat()
        val edge = raw.coerceIn(0f, last)
        val outside = raw - edge
        return edge + outside / (1f + abs(outside) / EDGE_LIMIT)
    }

    /** A touch can interrupt an edge rebound; avoid applying resistance twice to that first frame. */
    fun dragPosition(start: Float, delta: Float, count: Int): Float {
        if (!start.isFinite() || !delta.isFinite() || count <= 1) return 0f
        val edge = start.coerceIn(0f, lastPage(count).toFloat())
        val outside = (start - edge).coerceIn(-EDGE_LIMIT + .0001f, EDGE_LIMIT - .0001f)
        if (delta == 0f) return edge + outside
        val unresisted = edge + outside / (1f - abs(outside) / EDGE_LIMIT)
        return resistedPosition(unresisted + delta, count)
    }

    /** One release advances at most one page. Reversing velocity retracts the current drag. */
    fun releasePage(selectedPage: Int, position: Float, velocity: Float, count: Int): Int {
        val current = selected(selectedPage, count)
        if (!position.isFinite() || !velocity.isFinite()) return current
        val offset = position - current
        val step = when {
            abs(velocity) >= FLING_THRESHOLD && offset * velocity < 0f -> 0
            abs(velocity) >= FLING_THRESHOLD -> if (velocity > 0f) 1 else -1
            offset >= PAGE_THRESHOLD -> 1
            offset <= -PAGE_THRESHOLD -> -1
            else -> 0
        }
        return selected(current + step, count)
    }

    fun duration(start: Float, target: Float): Long =
        if (!start.isFinite() || !target.isFinite()) 180L
        else (340f * sqrt(abs(target - start).coerceIn(0f, 2f))).roundToLong().coerceIn(180L, 420L)
}

/** Interrupted motion retains its current position and bounded velocity, ending at rest. */
internal class SettingsPageMotionContinuation(start: Float, target: Int, velocity: Float, duration: Long, count: Int) {
    private val last = SettingsPageMotionPolicy.lastPage(count).toFloat()
    private val lower = -SettingsPageMotionPolicy.EDGE_LIMIT
    private val upper = last + SettingsPageMotionPolicy.EDGE_LIMIT
    private val from = if (start.isFinite()) start.coerceIn(lower, upper) else 0f
    private val to = SettingsPageMotionPolicy.selected(target, count).toFloat()
    private val delta = to - from
    private val tangent = (if (velocity.isFinite()) velocity.coerceIn(-8f, 8f) else 0f).let { speed ->
        val raw = speed * duration.coerceIn(0L, 420L) / 1000f
        val cap = if (raw * delta >= 0f) 3f * abs(delta)
        else 3f * (if (raw > 0f) upper - from else from - lower).coerceAtLeast(0f)
        raw.coerceIn(-cap, cap)
    }

    fun value(fraction: Float): Float {
        val t = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 1f
        val t2 = t * t
        val t3 = t2 * t
        return ((2f * t3 - 3f * t2 + 1f) * from + (t3 - 2f * t2 + t) * tangent +
            (-2f * t3 + 3f * t2) * to).coerceIn(lower, upper)
    }
}
