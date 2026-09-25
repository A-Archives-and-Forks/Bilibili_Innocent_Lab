package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import aistub.common.CardBasicInfo
import aistub.common.Module
import aistub.common.Neutral
import aistub.common.Owner
import aistub.common.RelateCard
import aistub.common.RelateCardType
import aistub.common.Relates
import aistub.common.UgcIntroduction
import aistub.v1.Arc
import aistub.v1.ECode
import aistub.v1.IntroductionTab
import aistub.v1.Tab
import aistub.v1.TabModule
import aistub.v1.ViewReply
import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.InjectedUiLocale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiDeclaredReplyInterceptorTest {

    private val errors = mutableListOf<String>()
    private val evidence = mutableListOf<Pair<String, FeatureRuntimeStage>>()
    private val environment = HookEnvironment(
        processName = "tv.danmaku.bili",
        classLoader = javaClass.classLoader,
        hookPoints = HookPointRegistry(javaClass.classLoader),
        registrar = TestHookRegistrar,
        logInfo = { _, _ -> },
        logError = { key, _ -> errors += key },
        reportStatus = { _, _ -> },
        runtimeEvidence = { id, stage, _ -> evidence += id to stage }
    )
    private val interceptor = requireNotNull(
        AiDeclaredReplyInterceptor.resolve(javaClass.classLoader!!, "aistub.v1.", "aistub.common.")
    )

    @Before @After fun reset() {
        AiDeclaredVideoRegistry.resetForTest()
        AuthorPickSession.resetForTest()
    }

    @Test fun everyReaderAndBothWriteChainsResolveAgainstBapisShapedClasses() {
        assertTrue(interceptor.relateStripReady)
    }

    @Test fun declaredVideoRedirectsToTheFirstUsableRelatedVideo() {
        val authors = mutableListOf<Pair<String?, Long>>()
        val original = reply(
            aid = 100, ownerMid = 1, declaration = "含AI生成内容",
            cards = listOf(
                live(300),
                video(101, mid = 1),                  // 同一发布者：AI 系列常成串推荐
                video(102, mid = 5).also { AiDeclaredVideoRegistry.add(102) },
                video(103, mid = 6)
            )
        )
        val updated = process(original) { name, mid -> authors += name to mid } as ViewReply

        assertNotSame(original, updated)
        assertEquals(ECode.CODE_404_VALUE, updated.ecodeValue)
        assertEquals("bilibili://video/103?cid=1", updated.ecodeConfig.redirectUrl)
        assertEquals("", updated.ecodeConfig.msg)
        // 原响应不被改动（副本改写）。
        assertEquals(0, original.ecodeValue)
        assertTrue(AiDeclaredVideoRegistry.contains(100))
        assertEquals(listOf<Pair<String?, Long>>("owner" to 1L), authors)
        assertTrue(AiDeclaredVideoPolicy.CAPABILITY_DETAIL to FeatureRuntimeStage.APPLIED in evidence)
    }

    @Test fun declaredVideoWithoutAUsableReplacementShowsTheHostPrivacyPageWithOurHint() {
        val original = reply(aid = 100, ownerMid = 1, declaration = "含AI生成内容", cards = listOf(video(101, mid = 1)))
        val updated = process(original) as ViewReply
        assertEquals(ECode.CODE_ARC_PRIVACY_VALUE, updated.ecodeValue)
        assertEquals("", updated.ecodeConfig.redirectUrl)
        assertEquals(InjectedUiLocale.messages().aiDeclaredBlockedHint, updated.ecodeConfig.msg)
    }

    @Test fun exhaustedRedirectGuardFallsBackToThePrivacyPage() {
        val guard = AiRedirectGuard(maxRedirects = 1, windowMillis = 60_000L)
        val first = process(reply(100, 1, "含AI生成内容", listOf(video(103, mid = 6))), guard) as ViewReply
        val second = process(reply(103, 6, "含AI生成内容", listOf(video(104, mid = 7))), guard) as ViewReply
        assertEquals(ECode.CODE_404_VALUE, first.ecodeValue)
        assertEquals(ECode.CODE_ARC_PRIVACY_VALUE, second.ecodeValue)
    }

    @Test fun undeclaredVideoIsReturnedAsTheSameInstance() {
        val original = reply(100, 1, "个人观点，仅供参考", listOf(video(103, mid = 6)))
        assertSame(original, process(original))
        val plain = reply(100, 1, null, listOf(video(103, mid = 6)))
        assertSame(plain, process(plain))
        assertTrue(errors.isEmpty())
    }

    @Test fun undeclaredVideoDropsOnlyKnownAiRelatedCards() {
        AiDeclaredVideoRegistry.add(102)
        val original = reply(100, 1, null, listOf(video(101, mid = 2), video(102, mid = 3), live(102)))
        val updated = process(original) as ViewReply
        assertNotSame(original, updated)
        assertEquals(0, updated.ecodeValue)
        val cards = relatesOf(updated).cardsList
        // 直播卡碰巧同号也不删：只认视频卡。
        assertEquals(listOf(101L, 102L), cards.map { it.basicInfo.id })
        assertEquals(RelateCardType.LIVE_VALUE, cards[1].relateCardTypeValue)
        assertEquals(3, relatesOf(original).cardsList.size)
    }

    @Test fun defaultInstanceAndForeignObjectsAreNeverTouched() {
        assertSame(ViewReply.getDefaultInstance(), process(ViewReply.getDefaultInstance()))
        val foreign = Any()
        assertSame(foreign, process(foreign))
    }

    private fun process(
        reply: Any,
        guard: AiRedirectGuard = AiRedirectGuard(),
        onAuthor: (String?, Long) -> Unit = { _, _ -> }
    ): Any = interceptor.process(reply, environment, guard, onAuthor)

    private fun reply(aid: Long, ownerMid: Long, declaration: String?, cards: List<RelateCard>): ViewReply {
        val neutral = declaration?.let { Neutral("warning-report-circle-line@500", it) }
        val modules = listOf(
            Module(UgcIntroduction(neutral), null),
            Module(null, Relates(cards))
        )
        return ViewReply(Arc(aid), Owner("owner", ownerMid), Tab(listOf(TabModule(IntroductionTab(modules)))))
    }

    private fun video(aid: Long, mid: Long) =
        RelateCard(RelateCardType.AV_VALUE, CardBasicInfo(aid, "bilibili://video/$aid?cid=1", Owner("up$mid", mid)))

    private fun live(id: Long) =
        RelateCard(RelateCardType.LIVE_VALUE, CardBasicInfo(id, "https://live.bilibili.com/$id", null))

    private fun relatesOf(reply: ViewReply): Relates =
        reply.tab.tabModuleList.single().introduction.modulesList.single { it.hasRelates() }.relates
}
