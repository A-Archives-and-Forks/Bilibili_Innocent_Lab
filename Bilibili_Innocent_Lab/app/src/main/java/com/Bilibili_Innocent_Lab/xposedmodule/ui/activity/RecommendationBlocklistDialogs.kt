package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.MineComponentSnapshotCodec
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotQueryClient
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.MineComponentSnapshotStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.highcapable.betterandroid.ui.extension.view.toast

/** 反馈面板点选落在宿主进程的观测快照里，打开管理面板时必须**主动拉取**这两个面。 */
private val RECOMMENDATION_PICK_SURFACES = listOf(
    MineComponentSnapshotCodec.SURFACE_SECTION_PICKS,
    MineComponentSnapshotCodec.SURFACE_AUTHOR_PICKS
)

/**
 * 先向宿主拉取两个点选观测面，再打开管理面板。
 *
 * **不能只 `MineComponentSnapshotStore.read`**：那份存储只有
 * `MineComponentSnapshotQueryClient.query` 校验成功后才会被写入，而 `query` 的唯一调用点
 * 原本是四个列表面的勾选面板（`queryComponentSnapshotAndOpenPicker`）。
 * 这两个面从来没有人查过，于是宿主侧 `payload_section_picks` 有值、模块侧对应的键根本不存在，
 * 表现就是"在反馈面板里点了，管理推荐屏蔽里找不到"（2026-09-15 真机实证）。
 *
 * 查询失败不挡住面板：退回本地已存快照并提示一次，用户仍然能管理已保存的名单。
 */
internal fun MainActivity.showRecommendationBlocklistDialog(anchor: View? = null, onSaved: () -> Unit) {
    if (recommendationPickQueryInFlight) return
    recommendationPickQueryInFlight = true
    toast(getString(R.string.recommendation_blocklist_syncing))
    val collected = linkedMapOf<String, MineComponentSnapshot>()
    val answered = mutableSetOf<String>()
    var degraded = false
    // 回调统一 post 回主线程（见 MineComponentSnapshotQueryClient.query），无需加锁。
    // 按面记账而不是纯计数：同一个面被回两次（重试竞态、或下面的同步兜底）也只算一次，
    // 不会出现"面板弹两个"或"计数漏一次、开关永远卡住"。
    fun settle(surface: String) {
        if (!answered.add(surface) || answered.size < RECOMMENDATION_PICK_SURFACES.size) return
        recommendationPickQueryInFlight = false
        if (isFinishing || isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) return
        if (degraded) toast(getString(R.string.recommendation_blocklist_sync_unavailable))
        presentRecommendationBlocklistDialog(
            anchor, RECOMMENDATION_PICK_SURFACES.mapNotNull(collected::get), onSaved
        )
    }
    RECOMMENDATION_PICK_SURFACES.forEach { surface ->
        // 查询本身抛出时回调不会再来；不兜住就会把 in-flight 开关永久锁死。
        runCatching {
            MineComponentSnapshotQueryClient.query(this, surface) { result ->
                // STORE_FAILED 也带着已校验的快照：这次显示得出来，只是没能落盘。
                val snapshot = result.snapshot?.takeIf { it.surface == surface }
                    ?: MineComponentSnapshotStore.read(this, surface)
                if (snapshot != null) collected[surface] = snapshot
                // WAITING_PAGE 是"宿主在线但这个面还没有内容"，不是故障，不提示。
                if (result.status != MineComponentSnapshotQueryClient.Status.READY &&
                    result.status != MineComponentSnapshotQueryClient.Status.WAITING_PAGE
                ) degraded = true
                settle(surface)
            }
        }.onFailure {
            degraded = true
            MineComponentSnapshotStore.read(this, surface)?.let { collected[surface] = it }
            settle(surface)
        }
    }
}

private fun MainActivity.presentRecommendationBlocklistDialog(
    anchor: View?,
    snapshots: List<MineComponentSnapshot>,
    onSaved: () -> Unit
) {
    val preferences = prefs()
    val draft = RecommendationBlocklistDraft(
        preferences.getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_TIDS, "").orEmpty(),
        preferences.getString(FeaturePreferences.HOME_RECOMMEND_BLOCKED_AUTHORS, "").orEmpty(),
        snapshots,
        preferences.getString(RecommendationBlocklistDraft.REVIEWED_EVENTS_KEY, "").orEmpty()
    )
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val dialog = Dialog(this)
    val container = createModalContainer()
    container.addView(TextView(this).apply {
        text = getString(R.string.recommendation_blocklist_manage)
        setTextColor(getColor(R.color.colorTextDark))
        textSize = 19f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    })
    container.addView(TextView(this).apply {
        text = getString(R.string.recommendation_blocklist_description)
        setTextColor(getColor(R.color.colorTextGray))
        textSize = 12f
        setLineSpacing(dp(4).toFloat(), 1f)
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
    })
    val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    if (draft.rows.isEmpty()) {
        rows.addView(TextView(this).apply {
            text = getString(R.string.recommendation_blocklist_empty)
            setTextColor(getColor(R.color.colorTextGray))
            textSize = 14f
            setPadding(0, dp(16), 0, dp(16))
        })
    }
    RecommendationBlockKind.entries.forEach { kind ->
        val group = draft.rows.filter { it.rule.kind == kind }
        if (group.isEmpty()) return@forEach
        rows.addView(TextView(this).apply {
            text = getString(if (kind == RecommendationBlockKind.TAG) R.string.recommendation_blocklist_tags
                else R.string.recommendation_blocklist_authors)
            setTextColor(monetColors.primary)
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(6))
        })
        group.forEach { row ->
            rows.addView(CheckBox(this).apply {
                text = if (row.pending) getString(R.string.recommendation_blocklist_pending, row.label) else row.label
                setTextColor(getColor(R.color.colorTextDark))
                textSize = 14f
                minimumHeight = dp(48)
                isChecked = draft.isSelected(row.rule)
                setOnCheckedChangeListener { _, checked -> draft.setSelected(row.rule, checked) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
    val listHeight = if (draft.rows.isEmpty()) dp(80) else minOf(
        dp(320), (resources.displayMetrics.heightPixels * 0.38f).toInt(),
        dp(draft.rows.size.coerceAtMost(6) * 56 + 80)
    )
    container.addView(ScrollView(this).apply { addView(rows) },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight))
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    buttons.addView(createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    })
    buttons.addView(createTermsActionButton(getString(R.string.dialog_confirm), filled = true) {
        if (!draft.save(preferences)) {
            toast(getString(R.string.recommendation_blocklist_save_failed))
            return@createTermsActionButton
        }
        onSaved()
        toast(getString(R.string.recommendation_blocklist_saved))
        dismissWithAnimation(dialog, container) {}
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(8)
    })
    container.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(12)
    })
    presentModalDialog(dialog, container, anchor)
}
