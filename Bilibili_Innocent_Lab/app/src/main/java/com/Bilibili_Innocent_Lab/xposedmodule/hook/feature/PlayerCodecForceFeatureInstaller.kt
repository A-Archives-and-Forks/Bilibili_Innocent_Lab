package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.os.Bundle
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 播放器编码偏好与硬/软解覆盖。请求改写和 ijk option 改写均为 copy-on-write/原地键覆盖，
 * 不做 DEX 扫描、I/O、IPC 或逐帧 Hook；所有定位失败都只影响本功能。
 */
internal class PlayerCodecForceFeatureInstaller(
    codecPreferenceValue: Int,
    decodeModeValue: Int
) : FeatureInstaller {
    override val id: String = ID
    override val capabilityIds: List<String> = listOf(CAPABILITY)
    private val preference = PlayerCodecPreference.fromValue(codecPreferenceValue)
    private val decodeMode = PlayerDecodeMode.fromValue(decodeModeValue)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (preference == PlayerCodecPreference.FOLLOW_HOST && decodeMode == PlayerDecodeMode.FOLLOW_HOST) {
            environment.reportStatus(STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        val loader = environment.classLoader ?: return skipped(environment, "missing-class-loader")
        var installed = 0
        var expected = 0
        if (preference != PlayerCodecPreference.FOLLOW_HOST) {
            val requestResults = listOf(
                installRequestFamily(
                    environment, loader,
                    MOSS_CLASSES_UNITE, REQUEST_CLASSES_UNITE,
                    setOf("executePlayViewUnite", "playViewUnite", "executePlayView", "playView"),
                    nestedVod = true
                ),
                installRequestFamily(
                    environment, loader,
                    MOSS_CLASSES_LEGACY, REQUEST_CLASSES_LEGACY,
                    setOf("executePlayView", "playView"),
                    nestedVod = false
                )
            )
            installed += requestResults.count { it }
            expected += requestResults.size
        }
        if (decodeMode != PlayerDecodeMode.FOLLOW_HOST) {
            val decodeInstalled = installDecodePaths(environment, loader)
            installed += decodeInstalled
            expected += 3
        }
        if (installed == 0) return skipped(environment, "missing-host-structure")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        val status = if (installed == expected) "success" else "partial:$installed/$expected"
        environment.reportStatus(STATUS, status)
        return FeatureInstallResult.Installed(installed, complete = installed == expected)
    }

    private fun installRequestFamily(
        environment: HookEnvironment,
        loader: ClassLoader,
        mossCandidates: List<String>,
        requestCandidates: List<String>,
        methodNames: Set<String>,
        nestedVod: Boolean
    ): Boolean = runCatching {
        val resolved = mossCandidates.asSequence().flatMap { mossName ->
            requestCandidates.asSequence().mapNotNull { requestName ->
                val owner = KavaMemberLookup.classOrNull(loader, mossName) ?: return@mapNotNull null
                val request = KavaMemberLookup.classOrNull(loader, requestName) ?: return@mapNotNull null
                val method = KavaMemberLookup.methods(owner, includeSuperclasses = true, makeAccessible = true) {
                    it.name in methodNames && it.parameterTypes.contentEquals(arrayOf(request)) &&
                        !Modifier.isStatic(it.modifiers) && !it.returnType.isPrimitive
                }.singleOrNull() ?: return@mapNotNull null
                Triple(owner, request, method)
            }
        }.firstOrNull() ?: return false
        val owner = resolved.first
        val request = resolved.second
        val method = resolved.third
        val access = RequestAccess.resolve(request, nestedVod, preference) ?: return false
        environment.registrar.exact("$ID.request.${request.simpleName}", owner, method.name, request) {
            before {
                val original = argOrNull(0) ?: return@before
                val updated = access.rewrite(original, preference) ?: return@before
                args[0] = updated
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
            }
        }
        true
    }.getOrElse {
        environment.logError("$ID.request", "[BIL] 编码偏好入口解析/注册失败")
        false
    }

    private fun installDecodePaths(environment: HookEnvironment, loader: ClassLoader): Int {
        val owner = IJK_CLIENT_CLASSES.firstNotNullOfOrNull { KavaMemberLookup.classOrNull(loader, it) }
            ?: return 0
        var installed = 0
        val bundleMethod = KavaMemberLookup.methods(owner, includeSuperclasses = true, makeAccessible = true) {
            it.name == "setOptionBundle" && it.parameterTypes.contentEquals(
                arrayOf(Int::class.javaPrimitiveType, Bundle::class.java)
            ) && !Modifier.isStatic(it.modifiers)
        }.singleOrNull()
        if (bundleMethod != null && runCatching {
                environment.registrar.exact("$ID.bundle", owner, bundleMethod.name, *bundleMethod.parameterTypes) {
                    before {
                        if ((argOrNull(0) as? Number)?.toInt() != 4) return@before
                        val bundle = argOrNull(1) as? Bundle ?: return@before
                        val rewrite = PlayerCodecForcePolicy.rewriteBundle(bundle, decodeMode)
                            ?: return@before
                        args[1] = rewrite.bundle
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, rewrite.changed)
                    }
                }
            }.isSuccess) installed++

        val optionMethods = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) {
            it.name == "_setOption" && it.parameterCount == 3 && !Modifier.isStatic(it.modifiers) &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == String::class.java &&
                (it.parameterTypes[2] == Long::class.javaPrimitiveType || it.parameterTypes[2] == String::class.java)
        }.distinctBy(Method::toGenericString)
        optionMethods.forEachIndexed { index, method ->
            if (runCatching {
                    environment.registrar.exact("$ID.option.$index", owner, method.name, *method.parameterTypes) {
                        before {
                            if ((argOrNull(0) as? Number)?.toInt() != 4) return@before
                            val key = argOrNull(1) as? String ?: return@before
                            val target = PlayerCodecForcePolicy.optionValue(key, decodeMode) ?: return@before
                            val current = argOrNull(2)
                            if (current is Long) {
                                if (current != target) {
                                    args[2] = target
                                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                                }
                            } else if (current is String && current != target.toString()) {
                                args[2] = target.toString()
                                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                            }
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        }
                    }
                }.isSuccess) installed++
        }
        return installed
    }

    private fun skipped(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        environment.reportStatus(STATUS, reason)
        return FeatureInstallResult.Skipped(reason)
    }

    private data class RequestAccess(
        val requestPlan: ProtobufBuilderPlan,
        val target: Class<*>,
        val targetGetter: Method?,
        val targetSetter: Method?,
        val targetPlan: ProtobufBuilderPlan,
        val preferGetter: Method,
        val preferSetter: Method,
        val fnvalGetter: Method,
        val fnvalSetter: Method,
        val preferredCode: Any
    ) {
        fun rewrite(original: Any, preference: PlayerCodecPreference): Any? = runCatching {
            val targetValue = targetGetter?.invoke(original) ?: original
            val fnval = (fnvalGetter.invoke(targetValue) as? Number)?.toLong() ?: return null
            val expectedFnval = PlayerCodecForcePolicy.expectedFnval(fnval, preference) ?: return null
            val currentCode = preferGetter.invoke(targetValue)
            if (currentCode == preferredCode && fnval == expectedFnval) return null
            val updatedTarget = targetPlan.edit(targetValue) { builder ->
                preferSetter.invoke(builder, preferredCode)
                fnvalSetter.invoke(builder, adaptNumber(expectedFnval, fnvalSetter.parameterTypes[0]))
            }
            if (targetSetter == null) updatedTarget else requestPlan.edit(original) { builder ->
                targetSetter.invoke(builder, updatedTarget)
            }
        }.getOrNull()

        companion object {
            fun resolve(
                request: Class<*>,
                nestedVod: Boolean,
                preference: PlayerCodecPreference
            ): RequestAccess? = runCatching {
                val requestPlan = ProtobufBuilderPlan.resolve(request) ?: return null
                val targetGetter = if (nestedVod) request.methods.firstOrNull { method ->
                    method.name == "getVod" && method.parameterCount == 0 && !Modifier.isStatic(method.modifiers) &&
                        !method.returnType.isPrimitive
                } else null
                val target = targetGetter?.returnType ?: request
                val targetPlan = ProtobufBuilderPlan.resolve(target) ?: return null
                val preferGetter = target.methods.firstOrNull { method ->
                    method.name == "getPreferCodecType" && method.parameterCount == 0 &&
                        !Modifier.isStatic(method.modifiers)
                }
                val codeType = preferGetter?.returnType ?: return null
                val preferSetter = targetPlan.method("setPreferCodecType", codeType) ?: return null
                val preferredCode = codeValue(codeType, preference) ?: return null
                val fnvalGetter = target.methods.firstOrNull { method ->
                    method.name == "getFnval" && method.parameterCount == 0 &&
                        Number::class.java.isAssignableFrom(box(method.returnType))
                } ?: return null
                val fnvalSetter = targetPlan.method("setFnval", fnvalGetter.returnType) ?: return null
                val targetSetter = targetGetter?.let { getter ->
                    requestPlan.method("setVod", getter.returnType)
                }
                if (nestedVod && targetSetter == null) return null
                RequestAccess(requestPlan, target, targetGetter, targetSetter, targetPlan,
                    preferGetter, preferSetter, fnvalGetter, fnvalSetter, preferredCode)
            }.getOrNull()

            private fun box(type: Class<*>): Class<*> = when (type) {
                Long::class.javaPrimitiveType -> Long::class.javaObjectType
                Int::class.javaPrimitiveType -> Int::class.javaObjectType
                else -> type
            }
        }
    }

    companion object {
        const val ID = "player_codec_force"
        private const val CAPABILITY = ID
        private const val STATUS = "player_codec_force_status"
        private val MOSS_CLASSES_UNITE = listOf(
            "com.bapis.bilibili.app.playerunite.v1.PlayerMoss",
            "com.bapis.bilibili.app.playerunite.v1.KPlayerMoss"
        )
        private val REQUEST_CLASSES_UNITE = listOf(
            "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq",
            "com.bapis.bilibili.app.playerunite.v1.KPlayViewUniteReq"
        )
        private val MOSS_CLASSES_LEGACY = listOf(
            "com.bapis.bilibili.app.playurl.v1.PlayURLMoss",
            "com.bapis.bilibili.app.playurl.v1.KPlayURLMoss"
        )
        private val REQUEST_CLASSES_LEGACY = listOf(
            "com.bapis.bilibili.app.playurl.v1.PlayViewReq",
            "com.bapis.bilibili.app.playurl.v1.KPlayViewReq"
        )
        private val IJK_CLIENT_CLASSES = listOf(
            "tv.danmaku.ijk.media.player.services.IjkMediaPlayerItemClient"
        )

        private fun codeValue(type: Class<*>, preference: PlayerCodecPreference): Any? {
            val names = when (preference) {
                PlayerCodecPreference.H264 -> listOf("CODE264", "CODE_264")
                PlayerCodecPreference.H265 -> listOf("CODE265", "CODE_265")
                PlayerCodecPreference.AV1 -> listOf("CODEAV1", "CODE_AV1")
                PlayerCodecPreference.FOLLOW_HOST -> emptyList()
            }
            return names.firstNotNullOfOrNull { name ->
                runCatching {
                    type.getDeclaredField(name).apply { isAccessible = true }.get(null)
                        .takeIf { value -> type.isEnum && type.isInstance(value) }
                }.getOrNull()
            }
        }

        private fun adaptNumber(value: Long, target: Class<*>): Any =
            if (target == Int::class.javaPrimitiveType || target == Int::class.java) value.toInt() else value
    }
}
