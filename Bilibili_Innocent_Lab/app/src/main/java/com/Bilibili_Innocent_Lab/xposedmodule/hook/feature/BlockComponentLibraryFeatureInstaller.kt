package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 阻止资源组件库清单继续下发。
 *
 * 这个功能故意只在能从返回模型中识别到目标池时改写 builder；池名/模块名尚未被真机
 * 观测确认时保持原响应，避免把基础组件库误扩大为全部资源池。默认关闭，不读宿主文件，
 * 不拉起宿主界面，也不主动发起 IPC。
 */
internal class BlockComponentLibraryFeatureInstaller(
    private val enabled: Boolean,
    private val targetKeywords: Set<String> = ComponentLibraryPoolMatcher.DEFAULT_KEYWORDS
) : FeatureInstaller {
    override val id: String = ID
    override val capabilityIds: List<String> = listOf(CAPABILITY)

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return skipped(environment, "disabled")
        val loader = environment.classLoader ?: return skipped(environment, "missing-class-loader")
        val resolved = MOSS_CLASSES.asSequence().flatMap { mossName ->
            REQUEST_CLASSES.asSequence().mapNotNull { requestName ->
                val moss = KavaMemberLookup.classOrNull(loader, mossName) ?: return@mapNotNull null
                val request = KavaMemberLookup.classOrNull(loader, requestName) ?: return@mapNotNull null
                val sync = METHOD_NAMES.firstNotNullOfOrNull { name ->
                    KavaMemberLookup.methodOrNull(moss, name, request)?.takeIf {
                        !Modifier.isStatic(it.modifiers) && !it.returnType.isPrimitive
                    }
                } ?: return@mapNotNull null
                Triple(moss, request, sync)
            }
        }.firstOrNull()
            ?: return skipped(environment, "not-applicable-host")
        val moss = resolved.first
        val request = resolved.second
        val method = resolved.third
        val strategy = ComponentLibraryReplyStrategy.resolve(method.returnType, targetKeywords)
            ?: return skipped(environment, "missing-host-structure")
        val observationLogged = AtomicBoolean(false)

        val observe: (Any) -> Unit = { reply ->
            if (targetKeywords.isEmpty() && observationLogged.compareAndSet(false, true)) {
                strategy.describe(reply)?.let { description ->
                    environment.logInfo(
                        "$ID.observation",
                        "[BIL] 组件库清单观察(仅名称/数量): $description"
                    )
                }
            }
        }
        var installed = 0
        var available = 0
        runCatching {
            environment.registrar.exact("$ID.sync", moss, method.name, request) {
                after {
                    if (hasThrowable) return@after
                    val reply = result ?: return@after
                    environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                    observe(reply)
                    strategy.clearMatchedPools(reply)?.let { changed ->
                        result = changed
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
            }
            installed++
        }.onFailure { environment.logError("$ID.sync", "[BIL] 组件库同步清单 Hook 注册失败") }
        available++

        val handlerClass = KavaMemberLookup.classOrNull(loader, MOSS_HANDLER)?.takeIf { it.isInterface }
        if (handlerClass != null) {
            val async = KavaMemberLookup.methods(moss, includeSuperclasses = true, makeAccessible = true) {
                it.name in METHOD_NAMES && it.parameterTypes.contentEquals(arrayOf(request, handlerClass)) &&
                    it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers)
            }.singleOrNull()
            if (async != null) {
                available++
                if (runCatching {
                    environment.registrar.exact(ID, moss, async.name, request, handlerClass) {
                        before {
                            val delegate = argOrNull(1) ?: return@before
                            val proxy = MossResponseHandlerProxy.wrapTransform(handlerClass, delegate) { reply ->
                                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                                observe(reply)
                                strategy.clearMatchedPools(reply) ?: reply
                            } ?: return@before
                            args[1] = proxy
                        }
                    }
                }.isSuccess) {
                    installed++
                } else {
                    environment.logError("$ID.async", "[BIL] 组件库异步清单 Hook 注册失败")
                }
            }
        }
        if (installed == 0) return skipped(environment, "registration-failed")
        return runCatching {
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
            val status = if (installed == available) "success" else "partial:$installed/$available"
            environment.reportStatus(STATUS, status)
            FeatureInstallResult.Installed(installed, complete = installed == available)
        }.getOrElse {
            environment.logError("$ID.diagnostics", "[BIL] 组件库清单诊断上报失败")
            skipped(environment, "registration-failed")
        }
    }

    private fun skipped(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        environment.reportStatus(STATUS, reason)
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "block_component_library_download"
        const val CAPABILITY = "block_component_library_download"
        private const val STATUS = "block_component_library_status"
        private const val MOSS_HANDLER = "com.bilibili.lib.moss.api.MossResponseHandler"
        private val MOSS_CLASSES = listOf(
            "com.bapis.bilibili.app.resource.v1.ModuleMoss",
            "com.bapis.bilibili.app.resource.v1.KModuleMoss",
            "com.bapis.bilibili.p4218app.resource.p4240v1.ModuleMoss",
            "com.bapis.bilibili.p4218app.resource.p4240v1.KModuleMoss"
        )
        private val REQUEST_CLASSES = listOf(
            "com.bapis.bilibili.app.resource.v1.ListReq",
            "com.bapis.bilibili.app.resource.v1.KListReq",
            "com.bapis.bilibili.p4218app.resource.p4240v1.ListReq",
            "com.bapis.bilibili.p4218app.resource.p4240v1.KListReq"
        )
        private val METHOD_NAMES = listOf("executeList", "list")
    }
}

/** 组件库池的保守识别规则；未知/空名称永不命中。 */
internal object ComponentLibraryPoolMatcher {
    fun matches(
        poolName: String?,
        moduleNames: List<String>,
        keywords: Set<String> = DEFAULT_KEYWORDS
    ): Boolean {
        val names = (listOfNotNull(poolName) + moduleNames)
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter(String::isNotEmpty)
            .toSet()
        return names.isNotEmpty() && keywords.any { keyword ->
            keyword.trim().lowercase(Locale.ROOT).takeIf(String::isNotEmpty) in names
        }
    }

    /** 9.12.0 载荷中的真实池名尚未有运行时观测，默认不猜名称，保持原响应。 */
    val DEFAULT_KEYWORDS: Set<String> = emptySet()
}

/** 安装期解析 protobuf builder；热路径只调用已解析的 Method。 */
private class ComponentLibraryReplyStrategy private constructor(
    private val toBuilder: Method,
    private val build: Method,
    private val getPools: Method,
    private val clearPools: Method,
    private val addPool: Method,
    private val poolToBuilder: Method,
    private val poolBuild: Method,
    private val poolClearModules: Method,
    private val poolName: Method?,
    private val poolModules: Method?,
    private val moduleName: Method?,
    private val targetKeywords: Set<String>
) {
    fun clearMatchedPools(reply: Any): Any? = runCatching {
        if (targetKeywords.isEmpty()) return@runCatching null
        val pools = (getPools.invoke(reply) as? List<*>)?.filterNotNull().orEmpty()
        if (pools.isEmpty()) return@runCatching null
        val matched = pools.filter { pool ->
            val poolText = poolName?.invoke(pool) as? String
            val moduleText = (poolModules?.invoke(pool) as? List<*>)
                .orEmpty()
                .filterNotNull()
                .mapNotNull { module -> runCatching { moduleName?.invoke(module) as? String }.getOrNull() }
            ComponentLibraryPoolMatcher.matches(poolText, moduleText, targetKeywords)
        }
        if (matched.isEmpty()) return@runCatching null
        val replyBuilder = toBuilder.invoke(reply)
        clearPools.invoke(replyBuilder)
        pools.forEach { pool ->
            val poolBuilder = poolToBuilder.invoke(pool)
            if (pool in matched) poolClearModules.invoke(poolBuilder)
            addPool.invoke(replyBuilder, poolBuild.invoke(poolBuilder))
        }
        build.invoke(replyBuilder)
    }.getOrNull()

    fun describe(reply: Any): String? = runCatching {
        val pools = (getPools.invoke(reply) as? List<*>)?.filterNotNull().orEmpty()
        pools.take(8).map { pool ->
            val name = (poolName?.invoke(pool) as? String).orEmpty().take(64)
            val moduleList = (poolModules?.invoke(pool) as? List<*>).orEmpty().filterNotNull()
            val modules = moduleList
                .take(5)
                .mapNotNull { module -> runCatching { moduleName?.invoke(module) as? String }.getOrNull() }
                .map { it.take(64) }
            "$name:${modules.size}/${moduleList.size}[${modules.joinToString(",")}]"
        }.joinToString(" | ")
            .takeIf(String::isNotEmpty)
    }.getOrNull()

    companion object {
        fun resolve(
            replyClass: Class<*>,
            targetKeywords: Set<String>
        ): ComponentLibraryReplyStrategy? = runCatching {
            val toBuilder = replyClass.methods.firstOrNull { method ->
                method.name == "toBuilder" && method.parameterCount == 0 &&
                    !Modifier.isStatic(method.modifiers)
            } ?: return null
            val builderClass = toBuilder.returnType
            val build = builderClass.methods.firstOrNull { method ->
                method.name == "build" && method.parameterCount == 0 &&
                    !Modifier.isStatic(method.modifiers) && method.returnType == replyClass
            } ?: return null
            val getPools = replyClass.methods.firstOrNull { method ->
                method.name == "getPoolsList" && method.parameterCount == 0 &&
                    List::class.java.isAssignableFrom(method.returnType)
            } ?: return null
            val clearPools = builderClass.methods.firstOrNull { method ->
                method.name == "clearPools" && method.parameterCount == 0 &&
                    !Modifier.isStatic(method.modifiers)
            } ?: return null
            val poolClass = (getPools.genericReturnType as? ParameterizedType)
                ?.actualTypeArguments
                ?.singleOrNull()
                ?.let { it as? Class<*> }
            val addPool = builderClass.methods.firstOrNull { method ->
                method.name == "addPools" && method.parameterCount == 1 &&
                    !Modifier.isStatic(method.modifiers) &&
                    (poolClass == null || method.parameterTypes[0] == poolClass)
            } ?: return null
            val resolvedPoolClass = poolClass ?: addPool.parameterTypes[0]
            val poolToBuilder = resolvedPoolClass.methods.firstOrNull { method ->
                method.name == "toBuilder" && method.parameterCount == 0 &&
                    !Modifier.isStatic(method.modifiers)
            } ?: return null
            val poolBuilder = poolToBuilder.returnType
            val poolBuild = poolBuilder.methods.firstOrNull { method ->
                method.name == "build" && method.parameterCount == 0 &&
                    !Modifier.isStatic(method.modifiers) && method.returnType == resolvedPoolClass
            } ?: return null
            val poolClearModules = poolBuilder.methods.firstOrNull { method ->
                method.name == "clearModules" && method.parameterCount == 0 &&
                !Modifier.isStatic(method.modifiers)
            } ?: return null
            val poolName = resolvedPoolClass.methods.firstOrNull { method ->
                method.name in setOf("getName", "getPoolName") && method.parameterCount == 0 &&
                    method.returnType == String::class.java
            }
            val poolModules = resolvedPoolClass.methods.firstOrNull { method ->
                method.name == "getModulesList" && method.parameterCount == 0 &&
                    List::class.java.isAssignableFrom(method.returnType)
            }
            val moduleClass = (poolModules?.genericReturnType as? ParameterizedType)
                ?.actualTypeArguments
                ?.singleOrNull()
                ?.let { it as? Class<*> }
            val moduleName = moduleClass?.methods?.firstOrNull { method ->
                method.name in setOf("getName", "getModuleName", "getFilename", "getFileName") &&
                    method.parameterCount == 0 && method.returnType == String::class.java
            }
            ComponentLibraryReplyStrategy(
                toBuilder, build, getPools, clearPools, addPool,
                poolToBuilder, poolBuild, poolClearModules, poolName, poolModules, moduleName,
                targetKeywords
            )
        }.getOrNull()
    }
}
