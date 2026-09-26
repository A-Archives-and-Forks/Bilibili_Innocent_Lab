package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class AiDeclaredVideoPolicyTest {

    private val policy = AiDeclaredVideoPolicy

    @Test fun declarationTextMatchesTheCapturedAiStatementAndItsVariants() {
        // 2026-09-25 抓包里的原文。
        assertTrue(policy.isAiDeclaration("含AI生成内容"))
        assertTrue(policy.isAiDeclaration("含 AI 生成内容"))
        assertTrue(policy.isAiDeclaration("该视频使用人工智能合成技术"))
        assertTrue(policy.isAiDeclaration("作者声明：AI辅助生成"))
        assertTrue(policy.isAiDeclaration("AIGC 内容"))
    }

    @Test fun otherStatementsAndBlankTitlesAreNeverTreatedAsAiDeclarations() {
        assertFalse(policy.isAiDeclaration(null))
        assertFalse(policy.isAiDeclaration(""))
        assertFalse(policy.isAiDeclaration("个人观点，仅供参考"))
        assertFalse(policy.isAiDeclaration("该视频含有危险行为，请勿模仿"))
        // 只是话题里有 AI，没有"生成/合成"这类动词。
        assertFalse(policy.isAiDeclaration("AI 话题讨论"))
    }

    @Test fun creationTagsMatchOnlyAWholeAigcSegment() {
        assertTrue(policy.creationTagsDeclareAigc("人工智能-aigc,音乐-aigc"))
        assertTrue(policy.creationTagsDeclareAigc("人工智能-aigc"))
        assertTrue(policy.creationTagsDeclareAigc(" 音乐 - AIGC "))
        assertFalse(policy.creationTagsDeclareAigc("知识-解读评析,科技数码-杂谈"))
        assertFalse(policy.creationTagsDeclareAigc("人工智能-杂谈"))
        assertFalse(policy.creationTagsDeclareAigc("人工智能-aigc音乐"))
        assertFalse(policy.creationTagsDeclareAigc(""))
        assertFalse(policy.creationTagsDeclareAigc(null))
    }

    @Test fun feedUriIsDecodedOnlyThroughPlayerPreloadAndQnFeatureCreationTags() {
        assertTrue(policy.feedUriDeclaresAigc(feedUri(creationTags = "人工智能-aigc,音乐-aigc")))
        assertFalse(policy.feedUriDeclaresAigc(feedUri(creationTags = "知识-教学/教程")))
        // 抓包里的反例：ai_tags 是分类器输出，AI 话题视频也有；creation_tags 缺失就不算。
        assertFalse(policy.feedUriDeclaresAigc(feedUri(creationTags = null, aiTags = "人工智能-aigc-其他ai生成内容")))
        // aigc 只出现在标题里，不在特征串的 creation_tags 上。
        assertFalse(policy.feedUriDeclaresAigc(feedUri(creationTags = "", title = "aigc 教程")))
    }

    @Test fun feedUriFailsOpenOnAnythingMalformedOrMissing() {
        assertFalse(policy.feedUriDeclaresAigc(null))
        assertFalse(policy.feedUriDeclaresAigc(""))
        assertFalse(policy.feedUriDeclaresAigc("bilibili://video/1?cid=2"))
        assertFalse(policy.feedUriDeclaresAigc("bilibili://video/1?player_preload=%7Baigc"))
        assertFalse(policy.feedUriDeclaresAigc("bilibili://video/1?other=aigc"))
        val broken = "bilibili://video/1?player_preload=" +
            URLEncoder.encode("{\"qn_feature\":\"{not json aigc\"}", "UTF-8")
        assertFalse(policy.feedUriDeclaresAigc(broken))
    }

    @Test fun preGateOnlyPassesWhenAigcFollowsTheCreationTagsKey() {
        // AI 话题卡：aigc 只在 ai_tags（排在 creation_tags 前面）→ 预判即挡，不做整段解码。
        assertFalse(policy.mayDeclareAigc(feedUri(creationTags = "知识-杂谈", aiTags = "人工智能-aigc-其他")))
        assertTrue(policy.mayDeclareAigc(feedUri(creationTags = "人工智能-aigc")))
        assertTrue(policy.mayDeclareAigc(feedUri(creationTags = "人工智能-AIGC")))
        assertTrue(policy.feedUriDeclaresAigc(feedUri(creationTags = "人工智能-AIGC")))
        assertFalse(policy.mayDeclareAigc("bilibili://video/1?x=aigc"))
        // 键后窗口之外的 aigc 不算。
        val far = "creation_tags" + "x".repeat(2000) + "aigc"
        assertFalse(policy.mayDeclareAigc(far))
    }

    @Test fun repeatedJudgementsOfTheSameUriAreStable() {
        val uri = feedUri(creationTags = "人工智能-aigc")
        repeat(3) { assertTrue(policy.feedUriDeclaresAigc(uri)) }
        val miss = feedUri(creationTags = "音乐-aigc音乐")
        repeat(3) { assertFalse(policy.feedUriDeclaresAigc(miss)) }
    }

    @Test fun onlyTheHistoryPageSpmidIsPassive() {
        assertTrue(policy.isPassiveRequest("main.my-history.recommend.0"))
        assertFalse(policy.isPassiveRequest("main.ugc-video-detail.0.0"))
        assertFalse(policy.isPassiveRequest(""))
        assertFalse(policy.isPassiveRequest(null))
    }

    @Test fun queryParameterReadsTheExactNameWithoutDecoding() {
        val uri = "bilibili://video/1?cid=2&player_preload=%7B%7D&player_preload_x=9"
        assertEquals("%7B%7D", policy.queryParameter(uri, "player_preload"))
        assertEquals("2", policy.queryParameter(uri, "cid"))
        assertNull(policy.queryParameter(uri, "missing"))
        assertNull(policy.queryParameter("bilibili://video/1", "cid"))
    }

    @Test fun aidFromParamAcceptsOnlyPositiveDecimals() {
        assertEquals(117184995006501L, policy.aidFromParam("117184995006501"))
        assertNull(policy.aidFromParam("0"))
        assertNull(policy.aidFromParam("-3"))
        assertNull(policy.aidFromParam("ep123"))
        assertNull(policy.aidFromParam(null))
    }

    @Test fun replacementSkipsSelfKnownAiSameUploaderBlockedUploaderAndNonVideoRoutes() {
        fun card(aid: Long, uri: String? = "bilibili://video/$aid", mid: Long = 7, name: String = "up", video: Boolean = true) =
            AiDeclaredVideoPolicy.RelateCandidate(video, aid, uri, name, mid)
        fun ok(candidate: AiDeclaredVideoPolicy.RelateCandidate, known: Set<Long> = emptySet(), blocked: Set<String> = emptySet()) =
            policy.isReplacementCandidate(candidate, currentAid = 100, currentOwnerMid = 1,
                isKnownAi = { it in known }, isBlockedAuthor = { name, mid -> name in blocked || mid in blocked })

        assertTrue(ok(card(200)))
        assertFalse(ok(card(100)))
        assertFalse(ok(card(0)))
        assertFalse(ok(card(200, video = false)))
        assertFalse(ok(card(200, uri = "https://www.bilibili.com/bangumi/play/ep1")))
        assertFalse(ok(card(200, uri = null)))
        assertFalse(ok(card(200), known = setOf(200)))
        assertFalse(ok(card(200, mid = 1)))
        assertFalse(ok(card(200, name = "blocked"), blocked = setOf("blocked")))
        assertFalse(ok(card(200, mid = 9), blocked = setOf("9")))
    }

    /** 按抓包形状拼一条首页卡片 uri：`player_preload` 是 URL 编码的 JSON，`qn_feature` 是其中的 JSON 字符串。 */
    private fun feedUri(creationTags: String?, aiTags: String = "音乐-内容看点-演唱翻唱", title: String = "t"): String {
        // 手工拼串：org.json 不保证键序，而服务端下发的顺序是 title → ai_tags → creation_tags →
        // general_tags（2026-09-25 抓包），预判正是依赖 ai_tags 排在 creation_tags 之前。
        val feature = buildString {
            append("{\"title\":").append(JSONObject.quote(title))
            append(",\"ai_tags\":").append(JSONObject.quote(aiTags))
            creationTags?.let { append(",\"creation_tags\":").append(JSONObject.quote(it)) }
            append(",\"general_tags\":\"\",\"ip_tags\":\"\"}")
        }
        val preload = JSONObject().apply {
            put("expire_time", 1790346275)
            put("cid", 41597143842L)
            put("qn_feature", feature.toString())
        }
        return "bilibili://video/117184995006501?cid=41597143842&player_height=1080" +
            "&player_preload=" + URLEncoder.encode(preload.toString(), "UTF-8") + "&trackid=x"
    }
}
