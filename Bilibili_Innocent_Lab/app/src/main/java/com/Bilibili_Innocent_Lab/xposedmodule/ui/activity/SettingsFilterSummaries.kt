package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import androidx.annotation.StringRes
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ExactRuleSetCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.TidBlocklistCodec

/**
 * 各筛选面板的取值与摘要文案。
 *
 * 全部是纯 getter：读 MainActivity 上的状态字段，拼一行给设置项显示，
 * 不碰视图、不改状态、不发起 IPC。放在这里只是为了让 MainActivity 回到
 * "Activity 骨架 + 界面构建"，摘要口径的改动不必再在近万行里翻。
 *
 * 文件名以 `Summaries.kt` 结尾 = 落进 `SettingsUiSource.VOLUME_SUFFIXES` 的扫描集，
 * 于是 `SettingsDialogExtractionTest` 的四条结构约束继续管着它：
 * 不许顶层 `var`、不许注册生命周期回调、顶层函数必须挂在 MainActivity 上。
 * **不要**把文件改成不在扫描集里的名字——那等于让这些代码脱管。
 */

internal fun MainActivity.homeRecommendFilterValues(): Map<String, Boolean> = mapOf(
    FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS to removeHomeRecommendAds,
    FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES to removeHomeRecommendPictures,
    FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS to removeHomeRecommendGamePromotions,
    FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE to removeHomeRecommendLive,
    FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC to removeHomeRecommendPgc,
    FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS to removeHomeRecommendSpecialCards,
    FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES to removeHomeRecommendCourses,
    FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE to removeHomeRecommendLarge
)

internal fun MainActivity.homeRecommendFilterSummary(): String {
    val selected = homeRecommendFilterValues().values.count { it }
    return if (selected == 0) getString(R.string.home_recommend_filter_summary_none) else {
        getString(R.string.home_recommend_filter_summary_selected,
            selected, HomeRecommendFilterCatalog.preferenceKeys.size)
    }
}

@StringRes
internal fun MainActivity.homeRecommendFilterLabel(preferenceKey: String): Int = when (preferenceKey) {
    FeaturePreferences.REMOVE_HOME_RECOMMEND_ADS -> R.string.remove_home_recommend_ads
    FeaturePreferences.REMOVE_HOME_RECOMMEND_PICTURES -> R.string.remove_home_recommend_pictures
    FeaturePreferences.REMOVE_HOME_RECOMMEND_GAME_PROMOTIONS ->
        R.string.remove_home_recommend_game_promotions
    FeaturePreferences.REMOVE_HOME_RECOMMEND_LIVE -> R.string.remove_home_recommend_live
    FeaturePreferences.REMOVE_HOME_RECOMMEND_PGC -> R.string.remove_home_recommend_pgc
    FeaturePreferences.REMOVE_HOME_RECOMMEND_SPECIAL_CARDS -> R.string.remove_home_recommend_special_cards
    FeaturePreferences.REMOVE_HOME_RECOMMEND_COURSES -> R.string.remove_home_recommend_courses
    FeaturePreferences.REMOVE_HOME_RECOMMEND_LARGE -> R.string.remove_home_recommend_large
    else -> error("Unknown home recommendation filter key: $preferenceKey")
}

internal fun MainActivity.portraitContentFilterValues(): Map<String, Boolean> = mapOf(
    FeaturePreferences.REMOVE_HOME_RECOMMEND_VERTICAL to removeHomeRecommendVertical,
    FeaturePreferences.REMOVE_STORY_ADS to removeStoryAds,
    FeaturePreferences.REMOVE_STORY_LIVE to removeStoryLive,
    FeaturePreferences.REMOVE_STORY_GAMES to removeStoryGames,
    FeaturePreferences.REMOVE_STORY_COURSES to removeStoryCourses,
    FeaturePreferences.REMOVE_STORY_SHORT_DRAMA to removeStoryShortDrama,
    FeaturePreferences.REMOVE_STORY_SHOPPING to removeStoryShopping,
    FeaturePreferences.REMOVE_STORY_MUSIC to removeStoryMusic,
    FeaturePreferences.REMOVE_STORY_BANGUMI to removeStoryBangumi,
    FeaturePreferences.REMOVE_STORY_MOVIES to removeStoryMovies,
    FeaturePreferences.REMOVE_STORY_DOCUMENTARIES to removeStoryDocumentaries,
    FeaturePreferences.REMOVE_STORY_TV to removeStoryTv,
    FeaturePreferences.REMOVE_STORY_VARIETY to removeStoryVariety
)

internal fun MainActivity.portraitContentFilterSummary(): String {
    val selected = portraitContentFilterValues().values.count { it }
    return if (selected == 0) {
        getString(R.string.portrait_content_filter_summary_none)
    } else {
        getString(
            R.string.portrait_content_filter_summary_selected,
            selected,
            PortraitContentFilterCatalog.options.size
        )
    }
}

internal fun MainActivity.detailModuleFilterValues(): Map<String, Boolean> = mapOf(
    FeaturePreferences.REMOVE_DETAIL_HONOR to removeDetailHonor,
    FeaturePreferences.REMOVE_DETAIL_LIVE_ORDER to removeDetailLiveOrder,
    FeaturePreferences.REMOVE_DETAIL_UGC_SEASON to removeDetailUgcSeason,
    FeaturePreferences.REMOVE_DETAIL_UP_VIP_LABEL to removeDetailUpVipLabel,
    FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS to removeDetailTopicTags,
    FeaturePreferences.REMOVE_DETAIL_STAFF_FOLLOW to removeDetailStaffFollow,
    FeaturePreferences.REMOVE_DETAIL_HOT_BANNER to removeDetailHotBanner
)

internal fun MainActivity.detailModuleFilterSummary(): String {
    val selected = detailModuleFilterValues().values.count { it }
    return if (selected == 0) {
        getString(R.string.detail_module_purify_summary_none)
    } else {
        getString(
            R.string.detail_module_purify_summary_selected,
            selected,
            DetailComponentPanelCatalog.preferenceKeys.size
        )
    }
}

@StringRes
internal fun MainActivity.detailModuleFilterLabel(preferenceKey: String): Int = when (preferenceKey) {
    FeaturePreferences.REMOVE_DETAIL_HONOR -> R.string.remove_detail_honor
    FeaturePreferences.REMOVE_DETAIL_LIVE_ORDER -> R.string.remove_detail_live_order
    FeaturePreferences.REMOVE_DETAIL_UGC_SEASON -> R.string.remove_detail_ugc_season
    FeaturePreferences.REMOVE_DETAIL_UP_VIP_LABEL -> R.string.remove_detail_up_vip_label
    FeaturePreferences.REMOVE_DETAIL_TOPIC_TAGS -> R.string.remove_detail_topic_tags
    FeaturePreferences.REMOVE_DETAIL_STAFF_FOLLOW -> R.string.remove_detail_staff_follow
    FeaturePreferences.REMOVE_DETAIL_HOT_BANNER -> R.string.remove_detail_hot_banner
    else -> error("Unknown detail module filter key: ")
}

internal fun MainActivity.videoRelateFilterValues(): Map<String, Boolean> = mapOf(
    FeaturePreferences.REMOVE_RELATE_COMMERCIAL to removeRelateCommercial,
    FeaturePreferences.REMOVE_RELATE_GAME to removeRelateGame,
    FeaturePreferences.REMOVE_RELATE_LIVE to removeRelateLive,
    FeaturePreferences.REMOVE_RELATE_COURSE to removeRelateCourse,
    FeaturePreferences.REMOVE_RELATE_SPECIAL to removeRelateSpecial,
    FeaturePreferences.VIDEO_RELATE_MATCHING_ENHANCEMENT_ENABLED to
        videoRelateMatchingEnhancementEnabled,
    FeaturePreferences.VIDEO_RELATE_STRONG_MODE_ENABLED to
        videoRelateStrongModeEnabled,
    FeaturePreferences.VIDEO_RELATE_REASON_FILTER_ENABLED to
        videoRelateReasonFilterEnabled
)

internal fun MainActivity.videoRelateFilterSummary(): String {
    val selected = VideoRelateFilterCatalog.contentOptions.count {
        videoRelateFilterValues()[it.preferenceKey] == true
    }
    return when {
        videoRelateMatchingEnhancementEnabled && videoRelateStrongModeEnabled -> getString(
            R.string.video_relate_filter_summary_strong,
            selected,
            VideoRelateFilterCatalog.contentOptions.size
        )
        videoRelateMatchingEnhancementEnabled -> getString(
            R.string.video_relate_filter_summary_enhanced,
            selected,
            VideoRelateFilterCatalog.contentOptions.size
        )
        selected == 0 -> getString(R.string.video_relate_filter_summary_none)
        else -> getString(
            R.string.video_relate_filter_summary_selected,
            selected,
            VideoRelateFilterCatalog.contentOptions.size
        )
    }
}

internal fun MainActivity.recommendationRuleCount(value: String, tags: Boolean): String {
    val count = if (tags) TidBlocklistCodec.parse(value).size + TidBlocklistCodec.parseNames(value).size
        else ExactRuleSetCodec.parse(value).size
    return if (count == 0) "" else count.toString()
}

internal fun MainActivity.ruleEntryText(
    @StringRes titleRes: Int,
    @StringRes emptyRes: Int,
    @StringRes currentRes: Int,
    value: String
): String = getString(titleRes) + "\n" +
    if (value.isBlank()) getString(emptyRes) else getString(currentRes, value)

/** 评论发布者规则入口文案；与评论关键词入口保持同一行结构。 */
internal fun MainActivity.commentUserFilterSummaryText(value: String): String =
    getString(R.string.comment_user_filter_rules) + "\n" +
        if (value.isBlank()) {
            getString(R.string.comment_user_filter_rules_empty)
        } else {
            getString(R.string.comment_user_filter_rules_current, value)
        }

internal fun MainActivity.ruleSummary(value: String): String = if (value.isBlank()) {
    getString(R.string.custom_hide_rules_empty)
} else {
    getString(R.string.custom_hide_rules_current, value)
}

internal fun MainActivity.recommendVideoDurationSummary(): String = when {
    recommendVideoMinDurationSeconds <= 0 && recommendVideoMaxDurationSeconds <= 0 ->
        getString(R.string.recommend_video_duration_range_empty)
    recommendVideoMaxDurationSeconds <= 0 -> getString(
        R.string.recommend_video_duration_min_only,
        formatDurationSeconds(recommendVideoMinDurationSeconds)
    )
    recommendVideoMinDurationSeconds <= 0 -> getString(
        R.string.recommend_video_duration_max_only,
        formatDurationSeconds(recommendVideoMaxDurationSeconds)
    )
    else -> getString(
        R.string.recommend_video_duration_both,
        formatDurationSeconds(recommendVideoMinDurationSeconds),
        formatDurationSeconds(recommendVideoMaxDurationSeconds)
    )
}
