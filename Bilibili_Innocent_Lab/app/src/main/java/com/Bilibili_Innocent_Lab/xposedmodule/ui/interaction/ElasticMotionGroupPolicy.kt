package com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction

/**
 * Groups an elastic press into the visual unit that must move together,
 * clamps its travel inside the parent layer, and caps stretch in pixels.
 */

internal data class ElasticGroupNode(
    val hasSurface: Boolean,
    val fillsWindow: Boolean,
    val containerOnly: Boolean,
    val childCount: Int
)

internal object ElasticMotionGroupPolicy {
    const val MAX_PROMOTION_STEPS = 6
    const val STRETCH_CAP_DP = 2.5f

    /** path[0] = hit view, path[i] = i-th ancestor. Returns promotion depth. */
    fun promotionDepth(path: List<ElasticGroupNode>): Int {
        if (path.isEmpty()) return 0
        var depth = 0
        while (depth < path.size - 1 && depth < MAX_PROMOTION_STEPS) {
            val current = path[depth]
            val parent = path[depth + 1]
            if (parent.fillsWindow || parent.containerOnly) break
            val parentUnifies = parent.hasSurface && parent.childCount > 1
            if (current.hasSurface && !parentUnifies) break
            depth++
        }
        return depth
    }

    /**
     * True when the child hugs its parent's inner box on all four sides within [slack],
     * i.e. the parent adds no travel room for a clamped drag (the badge frame around
     * the GitHub icon wraps its 48dp child with zero gap on every side).
     */
    fun isTightWrap(gapLeft: Float, gapTop: Float, gapRight: Float, gapBottom: Float, slack: Float): Boolean =
        slack.isFinite() && slack >= 0f &&
            gapLeft <= slack && gapTop <= slack && gapRight <= slack && gapBottom <= slack

    /**
     * Clamp a drag offset inside the parent inner edges (gaps in px).
     *
     * [minTravel] 是每个方向的最低行程预算。WRAP_CONTENT 父容器里**最后一个子元素**的
     * 尾边间隙恒为 0（父高正好包住内容：`parent.height - paddingBottom - child.bottom == 0`），
     * 不补预算会把该方向的拖动整体钳死——真机实证：「兼容」卡只能上/左/右形变、
     * 向下纹丝不动，而它上面的「外观」卡（后面还有兄弟）四向都正常。弹窗与卡片的真实
     * 内边距（24dp/18dp 起）都大于典型 limit，补预算对它们不生效。
     */
    fun clampToParent(dx: Float, dy: Float, gapLeft: Float, gapTop: Float,
                      gapRight: Float, gapBottom: Float, out: ElasticVector,
                      minTravel: Float = 0f) {
        val budget = if (minTravel.isFinite()) minTravel.coerceAtLeast(0f) else 0f
        fun edge(gap: Float) = maxOf(gap.coerceAtLeast(0f), budget)
        val minX = -edge(gapLeft)
        val maxX = edge(gapRight)
        val minY = -edge(gapTop)
        val maxY = edge(gapBottom)
        out.x = if (dx.isFinite()) dx.coerceIn(minX, maxX) else 0f
        out.y = if (dy.isFinite()) dy.coerceIn(minY, maxY) else 0f
    }

    /** Cap proportional stretch to capPx absolute growth per axis. */
    fun cappedScale(base: Float, sizePx: Int, capPx: Float): Float {
        if (!base.isFinite() || sizePx <= 0 || !capPx.isFinite() || capPx < 0f) return 1f
        val maxScale = 1f + capPx / sizePx
        return if (base > maxScale) maxScale else base
    }
}
