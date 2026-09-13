package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 仅 UGC 结束页回复及其合并/渲染后的播放器列表；安装时核对结构，不触碰详情页 ViewReply 或 PGC widget。 */
internal object PlayerEndPageRecommendLocator {
    const val MOSS = "com.bapis.bilibili.app.viewunite.v1.ViewMoss"
    const val REPLY = "com.bapis.bilibili.app.viewunite.v1.ViewEndPageReply"
    const val REQUEST = "com.bapis.bilibili.app.viewunite.v1.ViewEndPageReq"
    const val HANDLER = "com.bilibili.lib.moss.api.MossResponseHandler"
    const val SERVICE = "com.bilibili.ship.theseus.ugc.endpage.relatedrecommand.UGCEndPageRelatedRecommendService"
    const val EXPECTED_PATHS = 5

    data class Access(val reply: Class<*>, val sync: Method?, val async: Method?, val handler: Class<*>?,
        val list: Method?, val count: Method?, val defaultInstance: Method?,
        val plan: ProtobufBuilderPlan?, val clear: Method?, val mergedList: Method?) {
        val canCopy: Boolean get() = list != null && count != null && defaultInstance != null && plan != null && clear != null
    }

    fun resolve(loader: ClassLoader?): Access? {
        val reply = KavaMemberLookup.classOrNull(loader, REPLY) ?: return null
        return resolve(reply, KavaMemberLookup.classOrNull(loader, MOSS),
            KavaMemberLookup.classOrNull(loader, REQUEST), KavaMemberLookup.classOrNull(loader, HANDLER),
            KavaMemberLookup.classOrNull(loader, SERVICE))
    }

    internal fun resolve(reply: Class<*>, moss: Class<*>?, request: Class<*>?, handler: Class<*>?,
        service: Class<*>? = null): Access {
        val list = exact(reply, "getRelatesList", List::class.java)
        val count = exact(reply, "getRelatesCount", Int::class.javaPrimitiveType!!)
        val default = KavaMemberLookup.declaredMethods(reply, makeAccessible = true) {
            it.name == "getDefaultInstance" && Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == reply
        }.singleOrNull()
        val plan = ProtobufBuilderPlan.resolve(reply)
        val clear = plan?.method("clearRelates")
        val mergedList = service?.let(::postMergeList)
        return Access(reply,
            if (moss != null && request != null) exact(moss, "executeViewEndPage", reply, request) else null,
            if (moss != null && request != null && handler?.isInterface == true)
                exact(moss, "viewEndPage", Void.TYPE, request, handler) else null,
            handler, list, count, default, plan, clear, mergedList)
    }

    /**
     * 9.x 先把详情页卡片合并到结束页列表，8.90.x 则直接在三参数渲染函数中
     * 生成 RunningUIComponent。两者都是结束页专属 service 的 List 输出；优先
     * 选择单参数合并入口，旧版没有该入口时再接受明确的 List/容器/String 形状。
     */
    private fun postMergeList(owner: Class<*>): Method? {
        val candidates = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            val params = it.parameterTypes
            val directMerge = params.contentEquals(arrayOf(List::class.java))
            val rendered = params.size == 3 && params[0] == List::class.java &&
                params[2] == String::class.java && !params[1].isPrimitive &&
                params[1] != String::class.java && params[1] != List::class.java
            (directMerge || rendered) && it.returnType == List::class.java &&
                !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers) &&
                !it.isBridge && !it.isSynthetic
        }
        return candidates.firstOrNull { it.parameterTypes.contentEquals(arrayOf(List::class.java)) }
            ?: candidates.singleOrNull()
    }

    private fun exact(owner: Class<*>, name: String, returns: Class<*>, vararg params: Class<*>): Method? =
        KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            it.name == name && it.returnType == returns && it.parameterTypes.contentEquals(params) &&
                !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers)
        }.singleOrNull()
}
