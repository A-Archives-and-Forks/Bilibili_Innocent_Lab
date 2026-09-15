package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import androidx.annotation.StringRes
import com.highcapable.betterandroid.ui.extension.view.toast
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SkinId
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime.SkinRepository
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundImportFailure
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundMode
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundStore

/**
 * 外观相关的摘要文案与失败回退：应用语言、皮肤、Liquid 背景。
 *
 * 皮肤/背景的**核心侧回退**由 renderer 自己完成，这里只负责事后提示与重建界面，
 * 不做降级决策——所以摘要要显示"实际请求的皮肤"与"当前降级后的后端"两项，
 * 两者可能不一致，那不是 bug。
 *
 * 文件名以 `Presenter.kt` 结尾 = 落进 `SettingsUiSource.VOLUME_SUFFIXES` 的扫描集。
 */

internal fun MainActivity.currentAppLanguageSummary(): String {
    val language = currentAppLanguage()
    return getString(R.string.app_language_current, getString(language.labelRes))
}

/** 实验性功能区显示实际请求的皮肤；Liquid 同时公开当前降级后端。 */
internal fun MainActivity.currentSkinSummary(): String {
    if (!isLiquidSkinRequested) return getString(R.string.skin_current_material_you)
    val backendLabel = liquidBackendLabelRes(liquidBackendName)?.let { getString(it) }
        ?: getString(R.string.skin_backend_initializing)
    return getString(R.string.skin_current_liquid, backendLabel)
}

internal fun MainActivity.currentLiquidBackgroundSummary(): String {
    val state = LiquidBackgroundStore.read(applicationContext)
    if (state.config.mode == LiquidBackgroundMode.AUTOMATIC) {
        return getString(R.string.liquid_background_summary_automatic)
    }
    if (!state.assetPresent) {
        return getString(R.string.liquid_background_summary_unavailable)
    }
    return getString(
        if (isLiquidSkinRequested) R.string.liquid_background_summary_active
        else R.string.liquid_background_summary_saved
    )
}

@StringRes
internal fun MainActivity.liquidBackgroundFailureText(reason: LiquidBackgroundImportFailure): Int =
    when (reason) {
        LiquidBackgroundImportFailure.READ_FAILED -> R.string.liquid_background_read_failed
        LiquidBackgroundImportFailure.FILE_TOO_LARGE -> R.string.liquid_background_file_too_large
        LiquidBackgroundImportFailure.UNSUPPORTED_IMAGE -> R.string.liquid_background_unsupported
        LiquidBackgroundImportFailure.DIMENSIONS_TOO_LARGE ->
            R.string.liquid_background_dimensions_too_large
        LiquidBackgroundImportFailure.ENCODE_FAILED -> R.string.liquid_background_encode_failed
        LiquidBackgroundImportFailure.STORAGE_FAILED -> R.string.liquid_background_storage_failed
    }

@StringRes
internal fun MainActivity.liquidBackendLabelRes(backendName: String?): Int? = when (backendName) {
    "REFRACTION" -> R.string.skin_backend_refraction
    "BLUR" -> R.string.skin_backend_blur
    "TRANSLUCENT" -> R.string.skin_backend_translucent
    else -> null
}

/** renderer 已完成核心侧回退后，当前 Activity 只负责提示并重建 Material 界面。 */
internal fun MainActivity.handleSkinRendererFailure() {
    runOnUiThread {
        if (skinFailureHandled || isFinishing || isDestroyed) return@runOnUiThread
        skinFailureHandled = true
        if (SkinRepository.resolveRequestedSkin(applicationContext) == SkinId.MATERIAL_YOU) {
            toast(getString(R.string.skin_start_failed))
            recreate()
        } else {
            toast(getString(R.string.skin_recovery_save_failed))
            skinSummaryView?.text = currentSkinSummary()
        }
    }
}

internal fun MainActivity.finishLiquidBackgroundChange() {
    liquidBackgroundSummaryView?.text = currentLiquidBackgroundSummary()
    val dialog = liquidBackgroundDialog
    val container = liquidBackgroundDialogContainer
    if (dialog != null && container != null && dialog.isShowing) {
        dismissWithAnimation(dialog, container) {
            if (!isFinishing && !isDestroyed) recreate()
        }
    } else if (!isFinishing && !isDestroyed) recreate()
}
