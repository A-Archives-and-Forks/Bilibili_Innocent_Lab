package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

/** Only reject definitely off-window surfaces; uncertain geometry fails open. */
internal object LiquidRefreshVisibilityPolicy {
    /** Perspective, rotation and scaling stay conservative. Translation does not change AABB extent. */
    fun isTranslationOnly(matrix: FloatArray): Boolean {
        if (matrix.size < 9) return false
        for (index in 0..8) if (!matrix[index].isFinite()) return false
        return matrix[0] == 1f && matrix[1] == 0f && matrix[3] == 0f && matrix[4] == 1f &&
            matrix[6] == 0f && matrix[7] == 0f && matrix[8] == 1f
    }

    fun intersectsWindow(left: Float, top: Float, right: Float, bottom: Float,
        windowLeft: Float, windowTop: Float, windowRight: Float, windowBottom: Float,
        padding: Float): Boolean {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
            !windowLeft.isFinite() || !windowTop.isFinite() || !windowRight.isFinite() ||
            !windowBottom.isFinite() || !padding.isFinite() || padding < 0f ||
            right <= left || bottom <= top || windowRight <= windowLeft || windowBottom <= windowTop) return true
        return right + padding >= windowLeft && bottom + padding >= windowTop &&
            left - padding <= windowRight && top - padding <= windowBottom
    }
}

/** Changed properties are already scheduled by their owners; this never posts an idle frame. */
internal class LiquidRefreshBatch {
    private var pending = 0
    fun mark(contentChanged: Boolean): Boolean {
        val flag = if (contentChanged) CONTENT else POSITION
        val added = pending and flag == 0
        pending = pending or flag
        return added
    }
    fun take(): Int = pending.also { pending = 0 }
    companion object {
        const val POSITION = 1
        const val CONTENT = 2
    }
}

/** Off-window surfaces stay registered, and re-entry refreshes even at the original recording origin. */
internal class LiquidSurfaceRefreshState {
    private var skipped = false

    fun shouldRefresh(visible: Boolean, originChanged: Boolean, contentChanged: Boolean): Boolean {
        if (!visible) {
            skipped = true
            return false
        }
        val refresh = skipped || originChanged || contentChanged
        skipped = false
        return refresh
    }
}
