package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/** Module-only preferences: excluded from host configuration, telemetry and settings backup. */
internal object SettingsFavoritesStore {
    const val PREF_FILE = "settings_favorites_state"
    private val lock = Any()

    fun read(context: Context): SettingsFavoritesState = synchronized(lock) {
        runCatching { SettingsFavoritesPolicy.decode(preferences(context).all) }
            .getOrElse { SettingsFavoritesState(status = SettingsFavoritesStatus.UNAVAILABLE) }
    }

    fun add(context: Context, id: String): Boolean = update(context) { SettingsFavoritesPolicy.add(it, id) }

    fun remove(context: Context, id: String): Boolean = update(context) { SettingsFavoritesPolicy.remove(it, id) }

    fun move(context: Context, id: String, toIndex: Int): Boolean =
        update(context) { SettingsFavoritesPolicy.move(it, id, toIndex) }

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    @SuppressLint("UseKtx", "ApplySharedPref") // Both commit results are needed to preserve failed-save semantics.
    private fun update(
        context: Context,
        transform: (SettingsFavoritesState) -> SettingsFavoritesState?
    ): Boolean = synchronized(lock) {
        runCatching {
            val prefs = preferences(context)
            val previous = prefs.all
            val state = SettingsFavoritesPolicy.decode(previous)
            if (!state.canWrite) return@runCatching false
            val next = transform(state) ?: return@runCatching false
            val encoded = SettingsFavoritesPolicy.encode(next) ?: return@runCatching false
            if (next == state) return@runCatching true
            val saved = prefs.edit()
                .putInt(SettingsFavoritesPolicy.SCHEMA_KEY, SettingsFavoritesPolicy.SCHEMA)
                .putString(SettingsFavoritesPolicy.IDS_KEY, encoded)
                .commit()
            if (!saved) {
                // commit(false) can still change SharedPreferences' memory. Restore the old values.
                // Only our two keys are touched; unknown fields remain intact.
                val rollback = prefs.edit()
                val schema = previous[SettingsFavoritesPolicy.SCHEMA_KEY] as? Int
                val ids = previous[SettingsFavoritesPolicy.IDS_KEY] as? String
                if (schema == null) rollback.remove(SettingsFavoritesPolicy.SCHEMA_KEY)
                else rollback.putInt(SettingsFavoritesPolicy.SCHEMA_KEY, schema)
                if (ids == null) rollback.remove(SettingsFavoritesPolicy.IDS_KEY)
                else rollback.putString(SettingsFavoritesPolicy.IDS_KEY, ids)
                rollback.commit()
            }
            saved
        }.getOrDefault(false)
    }
}
