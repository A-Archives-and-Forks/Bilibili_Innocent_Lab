package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.AiDeclaredVideoPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ExactRuleSetCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.TidBlocklistCodec

internal enum class RecommendationBlockKind { TAG, AUTHOR }

internal data class RecommendationBlockRule(val kind: RecommendationBlockKind, val value: String)

internal data class RecommendationBlockRow(
    val rule: RecommendationBlockRule,
    val label: String,
    val pending: Boolean
)

/** 保存前只改草稿。处理标记与名单在同一笔提交中写入，旧观测不会重新引入已撤销项。 */
internal class RecommendationBlocklistDraft(
    private val savedTags: String,
    private val savedAuthors: String,
    snapshots: List<MineComponentSnapshot>,
    reviewed: String
) {
    private val initialTags = TidBlocklistCodec.normalize(savedTags)
    private val initialAuthors = ExactRuleSetCodec.encode(ExactRuleSetCodec.parse(savedAuthors))
    private val initialReviewed = reviewed
    private val reviewedTokens = reviewed.lineSequence().filter(String::isNotBlank).toSet()
    private val observedTokens = linkedSetOf<String>()
    /** 强力模式在宿主侧记下的 UP（来源标记见 [AiDeclaredVideoPolicy.AUTHOR_PICK_ORIGIN_URI]）。 */
    private val aiDeclaredTokens = linkedSetOf<String>()
    private val aiDeclaredRules = linkedSetOf<RecommendationBlockRule>()
    private val selected = linkedSetOf<RecommendationBlockRule>()
    val rows: List<RecommendationBlockRow>

    init {
        val entries = linkedMapOf<RecommendationBlockRule, RecommendationBlockRow>()
        fun addSaved(kind: RecommendationBlockKind, values: Set<String>) {
            values.forEach { value ->
                val rule = RecommendationBlockRule(kind, value)
                entries[rule] = RecommendationBlockRow(rule, value, pending = false)
            }
        }
        addSaved(RecommendationBlockKind.TAG, ExactRuleSetCodec.parse(initialTags))
        addSaved(RecommendationBlockKind.AUTHOR, ExactRuleSetCodec.parse(initialAuthors))
        snapshots.forEach { snapshot ->
            val kind = when (snapshot.surface) {
                MineComponentSnapshotCodec.SURFACE_SECTION_PICKS -> RecommendationBlockKind.TAG
                MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS -> RecommendationBlockKind.AUTHOR
                else -> return@forEach
            }
            snapshot.entries.forEach entryLoop@ { entry ->
                val value = when (kind) {
                    RecommendationBlockKind.TAG -> entry.id?.toLongOrNull()?.takeIf { it > 0 }?.toString()
                    RecommendationBlockKind.AUTHOR -> ExactRuleSetCodec.parse(entry.id).singleOrNull()
                } ?: return@entryLoop
                // 老版本快照没有事件身份：只确认这条旧记录，新版本再次点选会有新 token。
                val token = "${snapshot.surface}:${entry.key}:${entry.selectionToken ?: "legacy"}"
                observedTokens += token
                val rule = RecommendationBlockRule(kind, value)
                val existing = entries[rule]
                if (existing == null && token in reviewedTokens) return@entryLoop
                if (kind == RecommendationBlockKind.AUTHOR &&
                    entry.uri == AiDeclaredVideoPolicy.AUTHOR_PICK_ORIGIN_URI
                ) {
                    aiDeclaredTokens += token
                    aiDeclaredRules += rule
                }
                val name = entry.title?.takeIf(String::isNotBlank) ?: value
                val label = if (kind == RecommendationBlockKind.TAG && name != value) "$name ($value)" else name
                entries[rule] = RecommendationBlockRow(rule, label, pending = existing?.pending ?: true)
            }
        }
        rows = entries.values.toList()
        selected.addAll(entries.keys)
    }

    fun isSelected(rule: RecommendationBlockRule): Boolean = rule in selected

    fun setSelected(rule: RecommendationBlockRule, checked: Boolean) {
        require(rows.any { it.rule == rule })
        if (checked) selected += rule else selected -= rule
    }

    fun selectedValue(kind: RecommendationBlockKind): String = selected
        .filter { it.kind == kind }.joinToString(",") { it.value }

    fun acknowledgedValue(): String = acknowledge(observedTokens)

    private fun acknowledge(tokens: Set<String>): String = ((reviewedTokens - tokens) + tokens)
        .toList().takeLast(512).joinToString("\n")

    /** 还没进名单的强力模式来源 UP。 */
    val hasPendingAiDeclared: Boolean
        get() = rows.any { it.pending && it.rule in aiDeclaredRules }

    /**
     * 只并入强力模式来源的 UP：已保存项原样保留，其余待确认项**不选也不标记已处理**，
     * 仍留在「管理推荐屏蔽」里等用户确认——否则"没开自动确认"会被悄悄绕过。
     */
    fun saveAiDeclaredOnly(prefs: SharedPreferences): Boolean {
        val authors = rows.filter {
            it.rule.kind == RecommendationBlockKind.AUTHOR && (!it.pending || it.rule in aiDeclaredRules)
        }.joinToString(",") { it.rule.value }
        val tags = rows.filter { it.rule.kind == RecommendationBlockKind.TAG && !it.pending }
            .joinToString(",") { it.rule.value }
        return commit(prefs, tags, authors, acknowledge(aiDeclaredTokens))
    }

    /** 发现同一名单已被其他入口修改时保留现值，让用户重新打开，避免覆盖新配置。 */
    fun save(prefs: SharedPreferences): Boolean = commit(
        prefs,
        selectedValue(RecommendationBlockKind.TAG),
        selectedValue(RecommendationBlockKind.AUTHOR),
        acknowledgedValue()
    )

    private fun commit(
        prefs: SharedPreferences,
        tags: String,
        authors: String,
        acknowledged: String
    ): Boolean = runCatching {
        if (TidBlocklistCodec.normalize(prefs.getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS, "")) != initialTags ||
            ExactRuleSetCodec.encode(ExactRuleSetCodec.parse(prefs.getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS, ""))) != initialAuthors ||
            prefs.getString(REVIEWED_EVENTS_KEY, "").orEmpty() != initialReviewed
        ) return@runCatching false
        val committed = runCatching { prefs.edit()
            .putString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS, tags)
            .putString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS, authors)
            .putString(REVIEWED_EVENTS_KEY, acknowledged)
            .commit() }.getOrDefault(false)
        if (!committed) {
            // SharedPreferences 的失败提交也可能已更新内存，恢复本次打开时的原值。
            runCatching { prefs.edit()
                .putString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS, savedTags)
                .putString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS, savedAuthors)
                .putString(REVIEWED_EVENTS_KEY, initialReviewed)
                .commit() }
        }
        committed
    }.getOrDefault(false)

    companion object {
        // 本地观测处理状态，不是 Hook 设置，不进入备份目录或远程配置白名单。
        const val REVIEWED_EVENTS_KEY = "recommendation_feedback_reviewed_events"

        /**
         * 「自动确认新增屏蔽标签」；键定义在 [FeaturePreferences] 里，
         * 因为它已登记进 `SettingsCatalog`（要能被设置备份带走）。
         *
         * 与它同住一张面板的 [REVIEWED_EVENTS_KEY] 刻意留在本地：那是**处理状态**
         * （哪些观测事件已经处理过），不是用户意图，导出它只会让另一台设备
         * 凭空少掉一批待确认项。
         */
        const val AUTO_CONFIRM_KEY = FeaturePreferences.HOME_RECOMMEND_FEEDBACK_AUTO_CONFIRM

        fun isAutoConfirmEnabled(prefs: SharedPreferences): Boolean =
            runCatching { prefs.getBoolean(AUTO_CONFIRM_KEY, false) }.getOrDefault(false)

        /** 强力模式只在总开关也开着时才算数，与宿主侧的安装条件一致。 */
        fun isAiDeclaredStrongModeEnabled(prefs: SharedPreferences): Boolean = runCatching {
            prefs.getBoolean(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS, false) &&
                prefs.getBoolean(FeaturePreferences.BLOCK_AI_DECLARED_VIDEOS_STRONG_MODE, false)
        }.getOrDefault(false)

        /** 前台后台同步要不要跑：两个自动并入来源任一开着即可。 */
        fun needsBackgroundMerge(prefs: SharedPreferences): Boolean =
            isAutoConfirmEnabled(prefs) || isAiDeclaredStrongModeEnabled(prefs)

        fun setAutoConfirmEnabled(prefs: SharedPreferences, enabled: Boolean): Boolean =
            runCatching {
                prefs.edit().putBoolean(AUTO_CONFIRM_KEY, enabled).commit()
            }.getOrDefault(false)

        /** 面板与自动确认共用同一条读取，避免两处各读一遍、日后悄悄漂移。 */
        fun of(prefs: SharedPreferences, snapshots: List<MineComponentSnapshot>) =
            RecommendationBlocklistDraft(
                prefs.getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS, "").orEmpty(),
                prefs.getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS, "").orEmpty(),
                snapshots,
                prefs.getString(REVIEWED_EVENTS_KEY, "").orEmpty()
            )

        /**
         * 自动确认：把观测到的新点选并入名单，**等价于用户打开面板后直接点确定**。
         *
         * 之所以可以完全等价：草稿构造时 `selected` 就是全部条目（已保存 + 待确认），
         * [save] 写回的也正是这一整份，所以已保存项不会因此丢失。
         *
         * 没有待确认项时不写盘——否则模块每次前台都会打一次 `commit()`。
         * 返回 true 仅代表"这次确实提交了新内容"，调用方据此决定要不要刷新摘要。
         */
        fun autoConfirm(prefs: SharedPreferences, snapshots: List<MineComponentSnapshot>): Boolean {
            val general = isAutoConfirmEnabled(prefs)
            val aiStrong = isAiDeclaredStrongModeEnabled(prefs)
            if (!general && !aiStrong) return false
            val draft = of(prefs, snapshots)
            if (general) {
                if (draft.rows.none(RecommendationBlockRow::pending)) return false
                return draft.save(prefs)
            }
            // 只开了强力模式：只并入它自己记下的 UP，别的点选仍要用户确认。
            if (!draft.hasPendingAiDeclared) return false
            return draft.saveAiDeclaredOnly(prefs)
        }
    }
}
