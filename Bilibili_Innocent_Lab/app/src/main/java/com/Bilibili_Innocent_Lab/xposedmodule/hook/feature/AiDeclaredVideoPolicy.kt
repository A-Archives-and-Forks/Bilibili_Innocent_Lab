package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONObject
import java.net.URLDecoder

/**
 * 「屏蔽 AI 生成声明视频」的全部判据。纯函数，不碰宿主类。
 *
 * ### 两个信号，强度不同（2026-09-25 抓包 + 27 版宿主静态核对）
 *
 * **① 详情页声明（权威）**：`viewunite.v1.View/View` 响应里
 * `ViewReply.tab → TabModule.introduction → IntroductionTab.modules →
 * Module.ugc_introduction(3) → UgcIntroduction.neutral(9) → Neutral{icon, title}`，
 * 实测 `title="含AI生成内容"`、`icon="warning-report-circle-line@500"`。
 * `Neutral` 是通用声明位（图标也不是 AI 专用），协议里没有"AI"类型字段，
 * 所以只能按声明文案判——见 [isAiDeclaration]。`UgcIntroduction.desc` 里的
 * "AI生成，仅供娱乐" 是 UP 自己写的简介，**不是**声明，不参与判定。
 *
 * **② 首页卡片标签（启发式）**：`feed/index` 的卡片上没有声明本体，只有
 * `uri` 的 `player_preload` 查询参数里、自动画质模型用的 `qn_feature` 特征串，
 * 其 `creation_tags` 形如 `人工智能-aigc,音乐-aigc`。抓包里 5 个同时有详情页的视频：
 * 带声明的 2 个 `creation_tags` 都含 `aigc`；不带声明的 3 个（含两个 AI 话题视频）都不含。
 * 同一特征串里的 `ai_tags` 是分类器输出（AI 话题视频也会被打上），**不作判据**。
 */
internal object AiDeclaredVideoPolicy {

    const val ID = "ai_declared_video_block"

    /** 详情页命中后改写成跳转补位视频。 */
    const val CAPABILITY_DETAIL = "ai_declared_detail_redirect"

    /** 强力模式：把发布者记进「屏蔽 UP」点选观测面。 */
    const val CAPABILITY_AUTHOR = "ai_declared_author_block"

    /** 首页推荐按 `creation_tags` 与已确认 aid 删卡；挂在首页推荐过滤安装器下。 */
    const val CAPABILITY_HOME = "home_recommend_ai_declared_removed"

    /**
     * 强力模式记下的 UP 在观测快照里的来源标记，放在 [MineComponentScanEntry.uri]。
     *
     * `author` 条目的 key 只由 id（UP 名）推出，`uri` 不参与 key、也没有任何消费方把它当路由，
     * 所以借这一格标来源不会改变去重语义；模块 App 据此决定"不打开自动确认也并入"。
     */
    const val AUTHOR_PICK_ORIGIN_URI = "innocent-lab://ai-declared"

    /** 详情页补位候选的 `RelateCardType.AV_VALUE` 常量名。 */
    const val RELATE_AV_TYPE_CONSTANT = "AV_VALUE"

    /** 补位跳转只接受这一种路由，其余（番剧、直播、网页）一律不当候选。 */
    private const val VIDEO_ROUTE_PREFIX = "bilibili://video/"

    private val AI_VERB = Regex("ai.{0,4}(生成|合成|创作|制作)")

    /**
     * 声明文案是否在说"AI 生成"。
     *
     * 归一：去空白、转小写。命中任一即可：`ai…生成/合成/创作/制作`（中间允许 4 个字，
     * 覆盖"AI辅助生成"这类写法）、`aigc`、`人工智能`。宁可漏也不误伤：
     * 别的声明（如"个人观点，仅供参考"）一律不命中。
     */
    fun isAiDeclaration(title: String?): Boolean {
        if (title.isNullOrBlank()) return false
        val normalized = title.filterNot(Char::isWhitespace).lowercase()
        return "aigc" in normalized || "人工智能" in normalized || AI_VERB.containsMatchIn(normalized)
    }

    /** `creation_tags` 形如 `一级-二级,一级-二级`；任一段恰为 `aigc` 即命中。 */
    fun creationTagsDeclareAigc(tags: String?): Boolean {
        if (tags.isNullOrBlank()) return false
        return tags.split(',').any { tag ->
            tag.split('-').any { it.trim().equals("aigc", ignoreCase = true) }
        }
    }

    /**
     * 首页卡片 `uri` 是否带 `creation_tags` 含 `aigc` 的特征串。
     *
     * 热路径纪律：`player_preload` 编码后可达 34 KB（长期文档 2026-09-02 条目），宿主每调一次
     * `getItems` 整张列表都要重判，所以预判只用原生 `String.indexOf`（**不许**用
     * `contains(ignoreCase = true)`：Kotlin 那条走逐字符的通用比较，34 KB 上是毫秒级）：
     * 先找 `creation_tags` 键，再要求 `aigc` 出现在键后 [CREATION_TAGS_WINDOW] 个字符内。
     * `ai_tags` 排在 `creation_tags` 前面，所以只在 `ai_tags` 里带 aigc 的 AI 话题卡在这一步就被挡掉，
     * 不会去做整段解码。通过预判的才截取参数、URL 解码、解两层 JSON，结论按 uri 串缓存。
     * 任何一步失败都按"不是"放行。
     */
    fun feedUriDeclaresAigc(uri: String?): Boolean {
        if (uri.isNullOrEmpty() || !mayDeclareAigc(uri)) return false
        decodedResults.get(uri)?.let { return it }
        val result = runCatching {
            val encoded = queryParameter(uri, "player_preload") ?: return@runCatching false
            val preload = JSONObject(URLDecoder.decode(encoded, "UTF-8"))
            val feature = preload.optString("qn_feature").takeIf(String::isNotBlank)
                ?: return@runCatching false
            creationTagsDeclareAigc(JSONObject(feature).optString("creation_tags"))
        }.getOrDefault(false)
        decodedResults.put(uri, result)
        return result
    }

    /** 只用原生查找，不分配。 */
    internal fun mayDeclareAigc(uri: String): Boolean {
        val key = uri.indexOf(CREATION_TAGS_KEY)
        if (key < 0) return false
        val limit = key + CREATION_TAGS_KEY.length + CREATION_TAGS_WINDOW
        return uri.indexOf("aigc", key).let { it in 0 until limit } ||
            uri.indexOf("AIGC", key).let { it in 0 until limit }
    }

    private const val CREATION_TAGS_KEY = "creation_tags"

    /** 编码后的 `creation_tags` 值：抓包最长约 60 个汉字 × 9 字节，留足余量。 */
    private const val CREATION_TAGS_WINDOW = 1024

    /** 通过预判的卡片才进缓存；有界，按访问序淘汰。 */
    private val decodedResults = object {
        private val map = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 64
        }
        @Synchronized fun get(key: String): Boolean? = map[key]
        @Synchronized fun put(key: String, value: Boolean) { map[key] = value }
    }

    /**
     * 这次 View 请求是不是详情页发的。
     *
     * 9.13.0 起历史记录页（`HistoryContentViewModel$applyRecommend`）也在后台调 `executeView`，
     * `spmid` 为 `main.my-history.recommend.0`，只读 `viewBase`/`arc`/`supplement`、不看 `ecode`。
     * 对它只记已知 aid，不改写、不提示、不记发布者、不占连锁保险。
     */
    fun isPassiveRequest(spmid: String?): Boolean = spmid != null && spmid.startsWith("main.my-history")

    /** 首页卡片的 `param` 对视频卡就是 aid 的十进制串；其他卡型读不出正数时返回 null。 */
    fun aidFromParam(param: String?): Long? = param?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** 补位只认站内视频路由，且目标 aid 必须是正数。 */
    fun isVideoRoute(uri: String?): Boolean = uri != null && uri.startsWith(VIDEO_ROUTE_PREFIX)

    /**
     * 相关推荐里的一张卡能不能当补位。
     *
     * 排除：非视频卡、自己、已知 AI 视频、同一发布者的其他视频（AI 系列往往成串出现，
     * 抓包里《朋友的酒》那页的第一张视频卡本身就带声明）、本进程已屏蔽的 UP。
     */
    fun isReplacementCandidate(
        candidate: RelateCandidate,
        currentAid: Long,
        currentOwnerMid: Long,
        isKnownAi: (Long) -> Boolean,
        isBlockedAuthor: (String?, String?) -> Boolean
    ): Boolean {
        if (!candidate.isVideo || candidate.aid <= 0 || candidate.aid == currentAid) return false
        if (!isVideoRoute(candidate.uri)) return false
        if (isKnownAi(candidate.aid)) return false
        if (currentOwnerMid > 0 && candidate.authorMid == currentOwnerMid) return false
        val mid = candidate.authorMid.takeIf { it > 0 }?.toString()
        return !isBlockedAuthor(candidate.authorName, mid)
    }

    /** 从相关推荐里抽出来的最小事实；宿主消息不越过这一层。 */
    data class RelateCandidate(
        val isVideo: Boolean,
        val aid: Long,
        val uri: String?,
        val authorName: String?,
        val authorMid: Long
    )

    /** 不依赖 android.net.Uri：值里的 `&` 已被编码成 `%26`，按原始分隔符切就够。 */
    internal fun queryParameter(uri: String, name: String): String? {
        val query = uri.substringAfter('?', "").takeIf(String::isNotEmpty) ?: return null
        val prefix = "$name="
        return query.split('&').firstOrNull { it.startsWith(prefix) }?.substring(prefix.length)
    }
}
