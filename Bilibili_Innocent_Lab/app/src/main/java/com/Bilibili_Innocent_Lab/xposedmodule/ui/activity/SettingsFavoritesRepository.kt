package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** Process-wide ordering for all favorite reads and edits, independent of Activity lifetimes. */
internal object SettingsFavoritesRepository {
    sealed interface Edit {
        data class Add(val id: String) : Edit
        data class Remove(val id: String) : Edit
        data class Move(val id: String, val toIndex: Int) : Edit
    }

    data class Result(val state: SettingsFavoritesState, val saved: Boolean? = null)

    fun interface Observer {
        fun onFavoritesResult(result: Result)
    }

    private val queue by lazy {
        SettingsFavoritesTaskQueue(Executors.newSingleThreadExecutor { task ->
            Thread(task, "BIL-Favorites").apply { isDaemon = true }
        })
    }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun read(context: Context, observer: Observer) = submit(context, null, observer)

    fun edit(context: Context, operation: Edit, observer: Observer) = submit(context, operation, observer)

    private fun submit(context: Context, operation: Edit?, observer: Observer) {
        // Neither the queued operation nor the posted result owns a View, Dialog or Activity.
        val application = context.applicationContext
        val recipient = WeakReference(observer)
        queue.execute {
            val saved = operation?.let { edit ->
                runCatching {
                    when (edit) {
                        is Edit.Add -> SettingsFavoritesStore.add(application, edit.id)
                        is Edit.Remove -> SettingsFavoritesStore.remove(application, edit.id)
                        is Edit.Move -> SettingsFavoritesStore.move(application, edit.id, edit.toIndex)
                    }
                }.getOrDefault(false)
            }
            val result = Result(SettingsFavoritesStore.read(application), saved)
            mainHandler.post { recipient.get()?.onFavoritesResult(result) }
        }
    }
}
