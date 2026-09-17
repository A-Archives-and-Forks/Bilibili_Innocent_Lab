package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source layout contracts; device interaction and rendering remain separate acceptance steps. */
class TelemetryMenuStructureTest {
    private val source by lazy {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/MainActivity.kt"
        sequenceOf(File(path), File("app/$path"))
            .first(File::isFile).readText()
    }

    @Test
    fun `github telemetry entry navigates before any switch is constructed`() {
        val row = SettingsUiSource.function("createTelemetryMenuRow")
        assertTrue(row.contains("showControl: Boolean = false"))
        val entry = row.substringAfter("if (!showControl) {")
            .substringBefore("var programmaticChange")
        assertTrue(entry.contains("return NativeLinearLayout(this)"))
        assertFalse(entry.contains("setOnClickListener"))
        assertTrue(entry.contains("isClickable = false"))
        assertFalse(entry.contains("writeConsentChoice"))
        assertFalse(entry.contains("telemetrySwitch"))
    }

    @Test
    fun `new bubble shifts without moving GitHub and retains an outside hit target`() {
        val badge = source.substringAfter("// 只占原图标的空间")
            .substringBefore("activationCardView = this")
        assertTrue(badge.contains("LayoutParams(27.dp, 27.dp) { marginEnd = 5.dp }"))
        assertTrue(badge.contains("LayoutParams(22.dp, 15.dp)"))
        assertTrue(badge.contains("marginEnd = -5.dp"))
        assertTrue(badge.contains("topMargin = -5.dp"))
        assertTrue(badge.contains("GithubUpdateBadgeDrawable"))
        assertTrue(badge.contains("toolbar.touchDelegate"))
        assertTrue(badge.contains("badge.visibility == View.VISIBLE"))
    }

    @Test
    fun `the info panel grows from the exclamation mark instead of the screen centre`() {
        val row = SettingsUiSource.function("createTelemetryMenuRow")
        val click = row.substringAfter("contentDescription = getString(R.string.telemetry_info_button)")
            .substringBefore("// GitHub 二级页只导航")
        // 顺序是这条用例的全部意义：GitHub 面板一收起，ⓘ 就从窗口上摘掉了，
        // 之后再取位置只会拿到 null，面板会静默退回居中缩放入场。
        val captured = click.indexOf("val origin = modalAnchorBounds(source)")
        val dismissed = click.indexOf("dismissWithAnimation(dialog, dialogContainer)")
        assertTrue(captured >= 0)
        assertTrue(dismissed >= 0)
        assertTrue("来源矩形必须在收起之前抓", captured < dismissed)
        assertTrue(click.contains("showTelemetryInfoDialog(origin)"))

        val detail = SettingsUiSource.function("showTelemetryInfoDialog")
        assertTrue(detail.contains("origin: SettingsBackupMotionRect? = null"))
        assertTrue(detail.contains("presentModalDialog(dialog, container, morphAnchorBounds = origin)"))
    }

    @Test
    fun `a captured rect never hijacks the bubble path or the live anchor`() {
        val present = SettingsUiSource.function("presentSizedModalDialog")
        // 气泡要拿来源 ImageView 做图案交接（见 09-10 的图案守恒铁律），静态矩形顶不了，
        // 只准服务居中卡片这一条；实时 View 永远优先，既有 30 个调用点行为不变。
        assertTrue(present.contains("morphAnchor == null && anchorStyle == AnchorStyle.CONTAINER"))
        assertTrue(present.contains("morphAnchor?.let(::modalAnchorBounds) ?: capturedAnchorBounds"))
        // 关掉系统动画时静态矩形同样不得启用形变。
        assertTrue(present.contains("capturedAnchorBounds?.takeIf { ValueAnimator.areAnimatorsEnabled() }"))
        // 退场与旋转要走同一条解析，不能一边用实时 View 一边用陈旧矩形。
        assertTrue(present.contains("resolveAnchorOnScreen()?.let { currentAnchor ->"))
    }

    @Test
    fun `detail owns the switch and retains disclosure and save failure checks`() {
        val detail = SettingsUiSource.function("showTelemetryInfoDialog")
        assertTrue(detail.contains("createTelemetryMenuRow(dialog, container, showControl = true)"))
        val row = SettingsUiSource.function("createTelemetryMenuRow")
        assertTrue(row.contains("enabled && !TelemetryStore.hasCurrentDisclosure(applicationContext)"))
        assertTrue(row.contains("if (!saved)"))
        assertTrue(row.contains("telemetry_choice_save_failed"))
        assertTrue(row.contains("animateTelemetrySummary(summary, getString("))
        assertTrue(row.contains("android.text.StaticLayout.Builder.obtain"))
    }
}
