package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import java.util.ArrayDeque
import java.util.concurrent.Executor

/** At most one submitted task, so submission order survives caller/Activity replacement. */
internal class SettingsFavoritesTaskQueue(private val executor: Executor) : Executor {
    private val pending = ArrayDeque<Runnable>()
    private var active: Runnable? = null

    @Synchronized
    override fun execute(command: Runnable) {
        pending.addLast(Runnable {
            try { command.run() } finally { scheduleNext() }
        })
        if (active == null) scheduleNext()
    }

    @Synchronized
    private fun scheduleNext() {
        active = pending.pollFirst()
        active?.let(executor::execute)
    }
}
