package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 手风琴分节（「兼容」「外观」「净化/增强进阶」）展开/收起的运动学策略层。
 *
 * 全部状态只有一个进度标量 p ∈ [0,1]：0 = 收起视觉，1 = 展开视觉。
 * 动画期间布局始终保持展开态，兄弟控件与卡片的"收起位置"由 translationY/clipBounds
 * 视觉补偿表达；p→0 收尾时 GONE 触发的重布局与同帧 offsets 复位互相抵消——
 * 布局跳变被完全吸收，任意时刻反转都从当前 p 与速度续跑，无 ghost、无瞬移。
 *
 * 与 [AdaptiveGlowPolicy]/[ElasticMotionPolicy] 同风格：纯 Kotlin、不碰 android 类型，
 * 可在 JVM 单测里直接跑。
 */
internal object ExpansionMotionPolicy {

    /** 弹簧刚度（p/s² 单位制）。~300 → ω≈17 rad/s，整段 settle ≈0.3s。 */
    const val STIFFNESS = 300f

    /**
     * 阻尼比。略低于 1 让展开尾端有极轻的过冲（兄弟控件跟随微沉回弹），
     * 符合 M3 Expressive 空间弹簧取向；不够克制时调高到 1。
     */
    const val DAMPING_RATIO = 0.94f

    /**
     * 静止判定：|p-target| 与 |v| 同时低于阈值即收敛。
     * 阈值不能太紧——近零段弹簧以亚像素速度爬行，卡片会顶着 clip 边"冻结"
     * 几百毫秒后才触发收尾 GONE，读作"截断态滞留后跳变"（2026-09-22 真机实测
     * f58→f73 冻结 ~450ms）。0.003 对应 ~7px 残差（2400px 内容），同帧收尾吸收。
     */
    const val REST_P = 0.003f
    const val REST_V = 0.06f

    /** 文字行显影羽化带宽度（dp）：揭示沿扫过行顶后，该行在此行程内完成显现。 */
    const val FEATHER_DP = 22f

    /** 行显现时的上浮归位幅度（dp）。 */
    const val ROW_SETTLE_DP = 6f

    /** 内容整体在展开过程中的轻微上浮（dp）。 */
    const val CONTENT_RISE_DP = 8f

    /** 箭头全程转角。 */
    const val CHEVRON_DEGREES = 180f

    /** 单帧最大步长（秒）：掉帧/后台恢复时钳制 dt，防止弹簧爆发。 */
    const val MAX_STEP_SECONDS = 0.05f

    /**
     * 归一化进度上的阻尼弹簧。p 为位置、v 为速度（p/秒）、target 为目标值。
     * 半隐式欧拉积分：先更新速度再更新位置，帧率不稳时也保持能量形态。
     * [step] 返回 true 表示已静止并精确落在目标上。
     */
    internal class Spring(
        var p: Float = 0f,
        var v: Float = 0f,
        var target: Float = 0f
    ) {
        fun step(dtSeconds: Float): Boolean {
            val dt = dtSeconds.coerceIn(0f, MAX_STEP_SECONDS)
            val damping = 2f * DAMPING_RATIO * sqrt(STIFFNESS)
            v += (-STIFFNESS * (p - target) - damping * v) * dt
            p += v * dt
            if (abs(p - target) < REST_P && abs(v) < REST_V) {
                p = target
                v = 0f
                return true
            }
            return false
        }
    }

    /** 线性空间进度：弹簧输出本身即空间曲线，不做二次缓动以免双重软化。 */
    fun spatial(p: Float): Float = p

    /**
     * 内容整体淡入曲线：前 ~55% 行程完成显现，尾段留给位移归位，
     * 避免"p 快结束时文字还在淡入"的拖尾感。
     */
    fun contentAlpha(p: Float): Float = (p / 0.55f).coerceIn(0f, 1f)

    /**
     * 行级联显影：揭示沿 clipY（content 坐标）扫过行顶 rowTop 后，
     * 该行在 featherPx 行程内线性显现；沿以下/以上为 1/0。
     * 纯几何——行高不一、收起反向、中途打断全部自动正确。
     */
    fun rowReveal(clipY: Float, rowTop: Float, featherPx: Float): Float {
        if (featherPx <= 0f) return if (clipY >= rowTop) 1f else 0f
        return ((clipY - rowTop) / featherPx).coerceIn(0f, 1f)
    }

    /** 行归位位移：显现度越低越靠下（待归位），显现完成时归 0。 */
    fun rowSettleOffset(reveal: Float, settlePx: Float): Float = settlePx * (1f - reveal)
}
