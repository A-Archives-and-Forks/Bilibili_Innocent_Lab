package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

/** One visual navigation owner. Replacement, completion and lifecycle cancellation release its work. */
internal class SettingsRevealRequest {
    private var generation = 0L
    private var release: (() -> Unit)? = null
    val isActive: Boolean get() = release != null

    fun begin(onRelease: () -> Unit): Long {
        cancel()
        release = onRelease
        return generation
    }

    fun owns(token: Long): Boolean = token == generation && release != null

    fun complete(token: Long): Boolean {
        if (!owns(token)) return false
        cancel()
        return true
    }

    fun cancel() {
        generation++
        val previous = release
        release = null
        previous?.invoke()
    }
}
