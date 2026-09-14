package com.bapis.bilibili.app.playurl.v1

import com.bapis.bilibili.app.playurl.v1.PlayArcConf
import com.bilibili.lib.moss.api.MossResponseHandler

class PlayViewReply(private var configuration: PlayArcConf = PlayArcConf.getDefaultInstance(),
    private val video: Boolean = true, val videoPayload: Any = Any()) {
    fun hasVideoInfo() = video
    fun getPlayArc() = configuration
    private fun setPlayArc(value: PlayArcConf) { configuration = value }
    companion object {
        private val DEFAULT = PlayViewReply(video = false)
        @JvmStatic fun getDefaultInstance() = DEFAULT
    }
}
enum class CodeType {
    CODE_UNKNOWN,
    CODE264,
    CODE265,
    CODEAV1
}

class PlayViewReq(
    private val preferCodecType: CodeType = CodeType.CODE_UNKNOWN,
    private val fnval: Int = 16
) {
    fun getPreferCodecType() = preferCodecType
    fun getFnval() = fnval

    companion object {
        @JvmStatic
        fun newBuilder(source: PlayViewReq) = Builder(source)
    }

    class Builder(source: PlayViewReq) {
        private var preferCodecType = source.preferCodecType
        private var fnval = source.fnval

        fun setPreferCodecType(value: CodeType): Builder {
            preferCodecType = value
            return this
        }

        fun setFnval(value: Int): Builder {
            fnval = value
            return this
        }

        fun build() = PlayViewReq(preferCodecType, fnval)
    }
}
@Suppress("UNUSED_PARAMETER")
class PlayURLMoss {
    fun executePlayView(req: PlayViewReq) = PlayViewReply()
    fun playView(req: PlayViewReq, handler: MossResponseHandler) = Unit
}
