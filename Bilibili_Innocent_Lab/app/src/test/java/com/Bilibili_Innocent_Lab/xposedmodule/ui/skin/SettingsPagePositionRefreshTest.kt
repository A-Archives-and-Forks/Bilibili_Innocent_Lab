package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidSurfaceRefreshState
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring guards plus the existing refresh state; not a RenderThread timing test. */
class SettingsPagePositionRefreshTest {
    private fun source(relative: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/$relative.kt"
        return sequenceOf(File(path), File("app/$path")).first(File::isFile).readText()
    }

    private fun function(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing $signature" }
        val body = source.indexOf('{', start)
        var depth = 1
        var end = body + 1
        while (depth > 0 && end < source.length) {
            when (source[end++]) { '{' -> depth++; '}' -> depth-- }
        }
        check(depth == 0) { "Unclosed $signature" }
        return source.substring(body, end)
    }

    @Test fun pagerNotifiesAfterApplyingBothTransformsAndVisibilityOnlyWhenSomethingChanged() {
        val pager = source("activity/SettingsPagePager")
        val apply = function(pager, "private fun applyPosition()")
        assertTrue(apply.contains("if (child.translationX != translation)"))
        assertTrue(apply.contains("if (child.visibility != visibility)"))
        assertEquals(2, Regex("positionChanged = true").findAll(apply).count())
        val notify = apply.indexOf("if (positionChanged) onPositionChanged()")
        assertTrue(notify > apply.indexOf("child.translationX = translation"))
        assertTrue(notify > apply.indexOf("child.visibility = visibility"))
        assertFalse(apply.contains("requestLayout("))
        assertFalse(apply.contains("postInvalidateOnAnimation("))
    }

    @Test fun activityAndSessionKeepMaterialAndClosedLifecyclesOutOfTheRenderer() {
        val activity = function(source("skin/activity/SkinnedActivity"),
            "protected fun notifyPreparedSkinPositionChanged()")
        assertTrue(activity.contains("if (!lifecycleEnded) skinSessionOrNull?.notifyPositionChanged()"))
        val session = function(source("skin/runtime/ActivitySkinSession"), "fun notifyPositionChanged()")
        assertTrue(session.contains("if (!isClosed && effectiveSkin == SkinId.LIQUID)"))
        assertTrue(session.contains("liquidRenderer?.notifyPositionChanged()"))
    }

    @Test fun positionBridgeReusesOriginComparisonWithoutRequestingPixelCopyOrRebuildingBackgrounds() {
        val renderer = source("skin/liquid/LiquidActivityRenderer")
        val notify = function(renderer, "fun notifyPositionChanged()")
        assertEquals("{\n        invalidateMovedSurfaces()\n    }", notify.replace("\r\n", "\n"))
        val moved = function(renderer, "private fun invalidateMovedSurfaces()")
        assertTrue(moved.contains("queueSurfaceRefresh(contentChanged = false)"))
        val flush = function(renderer, "private fun flushSurfaceRefresh(windowRoot: View)")
        assertTrue(flush.contains("if (closed) return"))
        assertTrue(flush.contains("isSurfacePotentiallyVisible(view)"))
        assertTrue(flush.contains("originChanged = !entry.value.matchesOrigin("))
        // 2026-09-20：回弹边界环移除，flush 不再刷新 stretch viewport 的边界记录。
        assertFalse(flush.contains("stretchViewports"))
        assertFalse(moved.contains("invalidateRegisteredSurfaces("))
        assertFalse(moved.contains("PixelCopy"))
        assertFalse(moved.contains("rebuildBackdrop("))
        // Ordinary scrolling retains the same origin-refresh path.
        assertTrue(renderer.contains("addOnScrollChangedListener(scrollListener)"))
    }

    @Test fun contentRefreshTriggersTraversalThroughOneVisibleSurfaceNotTheWholeWindow() {
        val renderer = source("skin/liquid/LiquidActivityRenderer")
        val queue = function(renderer, "private fun queueSurfaceRefresh(contentChanged: Boolean)")
        // 整窗 root.invalidate() 会把每个 View 的 display list 标脏重录；
        // 滚动期 PixelCopy 完成与回弹期强度步进每秒数十次走到这里。
        assertFalse(queue.contains("root.invalidate()"))
        assertTrue(queue.contains("triggerSurfaceFrame(root)"))
        val trigger = function(renderer, "private fun triggerSurfaceFrame(windowRoot: View)")
        assertTrue(trigger.contains("view.isShown"))
        assertTrue(trigger.contains("view.invalidate()"))
        // 损伤域必须收缩到表面矩形：flush 内的逐表面 invalidate 仍是唯一扩散点。
        val flush = function(renderer, "private fun flushSurfaceRefresh(windowRoot: View)")
        assertTrue(flush.contains("view.invalidate()"))
    }

    @Test fun originRefreshKeepsStationaryControlsIdleAndRefreshesHiddenPagesOnlyOnReentry() {
        val movingPage = LiquidSurfaceRefreshState()
        val stationaryToolbar = LiquidSurfaceRefreshState()
        val hiddenPage = LiquidSurfaceRefreshState()
        repeat(120) {
            assertTrue(movingPage.shouldRefresh(visible = true, originChanged = true, contentChanged = false))
            assertFalse(stationaryToolbar.shouldRefresh(visible = true, originChanged = false, contentChanged = false))
            assertFalse(hiddenPage.shouldRefresh(visible = false, originChanged = true, contentChanged = false))
        }
        assertTrue(hiddenPage.shouldRefresh(visible = true, originChanged = false, contentChanged = false))
        assertFalse(hiddenPage.shouldRefresh(visible = true, originChanged = false, contentChanged = false))
    }
}
