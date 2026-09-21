package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

internal enum class SettingsFavoritesStatus { READY, CORRUPT, UNSUPPORTED, UNAVAILABLE }

internal data class SettingsFavoritesState(
    val ids: List<String> = emptyList(),
    val status: SettingsFavoritesStatus = SettingsFavoritesStatus.READY
) {
    val canWrite: Boolean get() = status == SettingsFavoritesStatus.READY
}

/** Stable IDs only. Availability is a display concern and never repairs the stored list. */
internal object SettingsFavoritesPolicy {
    const val SCHEMA = 1
    const val MAX_ITEMS = 64
    const val MAX_ID_LENGTH = 160
    internal const val SCHEMA_KEY = "schema"
    internal const val IDS_KEY = "ids"
    private const val MAX_ENCODED_LENGTH = MAX_ITEMS * (MAX_ID_LENGTH + 1)
    private val validId = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]*")

    fun decode(values: Map<String, *>): SettingsFavoritesState {
        if (values.isEmpty()) return SettingsFavoritesState()
        val schema = values[SCHEMA_KEY]
        if (schema !is Int) return SettingsFavoritesState(status = SettingsFavoritesStatus.CORRUPT)
        if (schema != SCHEMA) return SettingsFavoritesState(status = SettingsFavoritesStatus.UNSUPPORTED)
        val encoded = values[IDS_KEY] as? String
            ?: return SettingsFavoritesState(status = SettingsFavoritesStatus.CORRUPT)
        if (encoded.length > MAX_ENCODED_LENGTH) return SettingsFavoritesState(status = SettingsFavoritesStatus.CORRUPT)
        val ids = if (encoded.isEmpty()) emptyList() else encoded.split('\n')
        if (ids.size > MAX_ITEMS || ids.any { !isValidId(it) }) {
            return SettingsFavoritesState(status = SettingsFavoritesStatus.CORRUPT)
        }
        return SettingsFavoritesState(ids.distinct())
    }

    fun encode(state: SettingsFavoritesState): String? {
        if (!state.canWrite || state.ids.size > MAX_ITEMS || state.ids.any { !isValidId(it) }) return null
        return state.ids.distinct().joinToString("\n")
    }

    fun add(state: SettingsFavoritesState, id: String): SettingsFavoritesState? {
        if (encode(state) == null || !isValidId(id)) return null
        if (id in state.ids) return state
        if (state.ids.size >= MAX_ITEMS) return null
        return state.copy(ids = state.ids + id)
    }

    fun remove(state: SettingsFavoritesState, id: String): SettingsFavoritesState? {
        if (encode(state) == null || !isValidId(id)) return null
        return state.copy(ids = state.ids.filterNot { it == id })
    }

    /** The destination is an index in the complete stored list, including unavailable IDs. */
    fun move(state: SettingsFavoritesState, id: String, toIndex: Int): SettingsFavoritesState? {
        if (encode(state) == null || id !in state.ids || toIndex !in state.ids.indices) return null
        val ids = state.ids.toMutableList()
        ids.remove(id)
        ids.add(toIndex, id)
        return state.copy(ids = ids)
    }

    fun visibleIds(state: SettingsFavoritesState, availableIds: Set<String>): List<String> =
        state.ids.filter { it in availableIds }

    private fun isValidId(id: String): Boolean = id.length in 1..MAX_ID_LENGTH && validId.matches(id)
}
