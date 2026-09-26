package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.os.Bundle

/** 请求侧编码偏好；0 必须保持宿主原值。 */
internal enum class PlayerCodecPreference(val value: Int, val codeId: Int) {
    FOLLOW_HOST(0, 0),
    H264(1, 7),
    H265(2, 12),
    AV1(3, 13);

    companion object {
        fun fromValue(value: Int): PlayerCodecPreference = entries.firstOrNull { it.value == value } ?: FOLLOW_HOST
    }
}

internal enum class PlayerDecodeMode(val value: Int) {
    FOLLOW_HOST(0),
    FORCE_HARDWARE(1),
    FORCE_SOFTWARE(2);

    companion object {
        fun fromValue(value: Int): PlayerDecodeMode = entries.firstOrNull { it.value == value } ?: FOLLOW_HOST
    }
}

internal object PlayerCodecForcePolicy {
    const val AV1_FNVAL = 2048L

    internal data class BundleRewrite(val bundle: Bundle, val changed: Int)

    fun expectedFnval(original: Long, preference: PlayerCodecPreference): Long? = when (preference) {
        PlayerCodecPreference.FOLLOW_HOST -> null
        PlayerCodecPreference.AV1 -> original or AV1_FNVAL
        PlayerCodecPreference.H264, PlayerCodecPreference.H265 -> original and AV1_FNVAL.inv()
    }

    /**
     * 请求侧实际生效的编码偏好。
     *
     * 方案硬约束（`docs/player_codec_force_plan.md` 风险 R4）：AV1 × 强制软解不可用组合一律降级 H.264。
     * B 站 ijk fork 的软解对 AV1 覆盖不确定，实测风险是黑屏/无法起播。
     */
    fun effectivePreference(preference: PlayerCodecPreference, mode: PlayerDecodeMode): PlayerCodecPreference =
        if (mode == PlayerDecodeMode.FORCE_SOFTWARE && preference == PlayerCodecPreference.AV1) {
            PlayerCodecPreference.H264
        } else {
            preference
        }

    /** 跟随宿主编码 + 强制软解：不改偏好，只清掉 AV1 能力位，让服务端不下发 AV1 流。 */
    fun stripsAv1Only(preference: PlayerCodecPreference, mode: PlayerDecodeMode): Boolean =
        mode == PlayerDecodeMode.FORCE_SOFTWARE && preference == PlayerCodecPreference.FOLLOW_HOST

    fun withoutAv1(fnval: Long): Long = fnval and AV1_FNVAL.inv()

    fun optionValue(key: String, mode: PlayerDecodeMode): Long? = when (mode) {
        PlayerDecodeMode.FOLLOW_HOST -> null
        PlayerDecodeMode.FORCE_HARDWARE -> when (key) {
            "decoder_type" -> 0L
            "enable_decoder_race" -> 0L
            in MEDIA_CODEC_KEYS -> 1L
            else -> null
        }
        PlayerDecodeMode.FORCE_SOFTWARE -> when (key) {
            "decoder_type" -> 1L
            "enable_decoder_race" -> 0L
            in MEDIA_CODEC_KEYS -> 0L
            else -> null
        }
    }

    /** 两条 ijk 写入路径共用的严格值解释；未知类型、文本和值域均保持原样。 */
    fun recognizedOptionValue(key: String, value: Any?): Long? {
        val parsed = when (value) {
            is Int -> value.toLong()
            is Long -> value
            is String -> value.takeIf { it == "0" || it == "1" }?.toLongOrNull()
            else -> null
        } ?: return null
        return parsed.takeIf { it in 0L..1L && key in OPTION_KEYS }
    }

    fun rewriteBundle(bundle: Bundle, mode: PlayerDecodeMode): BundleRewrite? {
        if (mode == PlayerDecodeMode.FOLLOW_HOST) return null
        val edits = mutableListOf<Pair<String, Long>>()
        for (key in OPTION_KEYS) {
            if (!bundle.containsKey(key)) continue
            val target = optionValue(key, mode) ?: continue
            val currentValue = bundle.get(key)
            val current = recognizedOptionValue(key, currentValue) ?: continue
            if (current == target) continue
            edits += key to target
        }
        if (edits.isEmpty()) return null
        val copy = Bundle(bundle)
        edits.forEach { (key, value) ->
            when (bundle.get(key)) {
                is Int -> copy.putInt(key, value.toInt())
                is String -> copy.putString(key, value.toString())
                is Long -> copy.putLong(key, value)
            }
        }
        return BundleRewrite(copy, edits.size)
    }

    val OPTION_KEYS = setOf(
        "decoder_type", "enable_decoder_race", "mediacodec", "mediacodec-hevc",
        "mediacodec-av1", "mediacodec-vvc", "mediacodec-all-videos"
    )
    private val MEDIA_CODEC_KEYS = OPTION_KEYS - setOf("decoder_type", "enable_decoder_race")
}
