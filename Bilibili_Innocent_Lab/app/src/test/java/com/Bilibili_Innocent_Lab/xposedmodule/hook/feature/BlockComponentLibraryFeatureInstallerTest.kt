package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.app.resource.v1.ListReply
import com.bapis.bilibili.app.resource.v1.ModuleMoss
import com.bapis.bilibili.app.resource.v1.ModuleReply
import com.bapis.bilibili.app.resource.v1.PoolReply
import com.bilibili.lib.moss.api.MossResponseHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockComponentLibraryFeatureInstallerTest {
    private val loader = javaClass.classLoader!!

    private fun environment(
        registrar: PlayerPortTestRegistrar,
        stages: MutableList<FeatureRuntimeStage> = mutableListOf(),
        statuses: MutableList<Pair<String, String>> = mutableListOf()
    ) = HookEnvironment(
        processName = "tv.danmaku.bili:download",
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { id, status -> statuses += id to status },
        runtimeEvidence = { id, stage, _ ->
            assertEquals(BlockComponentLibraryFeatureInstaller.ID, id)
            stages += stage
        }
    )

    @Test
    fun `real response hook clears only the recognized component pool`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        assertEquals(
            FeatureInstallResult.Installed(2),
            BlockComponentLibraryFeatureInstaller(true, setOf("基础组件库"))
                .install(environment(registrar, stages))
        )
        val ordinary = PoolReply("video", listOf(ModuleReply("video-core")))
        val component = PoolReply("基础组件库", listOf(ModuleReply("module-a")))
        val source = ListReply(listOf(ordinary, component))
        val filtered = registrar.invoke("${BlockComponentLibraryFeatureInstaller.ID}.sync", ModuleMoss()) { source }
            as ListReply

        assertFalse(filtered === source)
        assertEquals(2, filtered.getPoolsList().size)
        assertEquals(1, filtered.getPoolsList()[0].getModulesList().size)
        assertTrue(filtered.getPoolsList()[1].getModulesList().isEmpty())
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), stages)
        assertEquals(1, source.getPoolsList()[1].getModulesList().size)
    }

    @Test
    fun `module name hit removes only that module and reuses untouched pools`() {
        val registrar = PlayerPortTestRegistrar()
        val sourcePool = PoolReply(
            "ordinary",
            listOf(ModuleReply("module-a"), ModuleReply("video-core"))
        )
        val untouchedPool = PoolReply("other", listOf(ModuleReply("other-core")))
        val source = ListReply(listOf(sourcePool, untouchedPool))
        BlockComponentLibraryFeatureInstaller(true, setOf("module-a"))
            .install(environment(registrar))

        val filtered = registrar.invoke("${BlockComponentLibraryFeatureInstaller.ID}.sync", ModuleMoss()) { source }
            as ListReply

        assertNotSame(source, filtered)
        assertNotSame(sourcePool, filtered.getPoolsList()[0])
        assertSame(untouchedPool, filtered.getPoolsList()[1])
        assertEquals(
            listOf("video-core"),
            filtered.getPoolsList()[0].getModulesList().map { it.getModuleName() }
        )
        assertEquals(listOf("module-a", "video-core"), sourcePool.getModulesList().map { it.getModuleName() })
    }

    @Test
    fun `async response hook transforms only the delegated onNext reply`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        BlockComponentLibraryFeatureInstaller(true, setOf("基础组件库"))
            .install(environment(registrar, stages))
        val ordinary = PoolReply("video", listOf(ModuleReply("video-core")))
        val component = PoolReply("基础组件库", listOf(ModuleReply("module-a")))
        val source = ListReply(listOf(ordinary, component))
        var delivered: ListReply? = null
        val delegate = object : MossResponseHandler {
            override fun onNext(reply: Any?) { delivered = reply as? ListReply }
            override fun onError(error: Throwable) = Unit
            override fun onCompleted() = Unit
        }

        registrar.invoke(BlockComponentLibraryFeatureInstaller.ID, ModuleMoss(), arrayOf(null, delegate)) { args ->
            (args[1] as MossResponseHandler).onNext(source)
            null
        }

        assertEquals(0, delivered?.getPoolsList()?.get(1)?.getModulesList()?.size)
        assertEquals(1, delivered?.getPoolsList()?.get(0)?.getModulesList()?.size)
        assertEquals(1, source.getPoolsList()[1].getModulesList().size)
        assertEquals(
            listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED),
            stages
        )
    }

    @Test
    fun `unrecognized response is preserved by identity`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        val statuses = mutableListOf<Pair<String, String>>()
        val result = BlockComponentLibraryFeatureInstaller(true).install(environment(registrar, stages, statuses))
        val source = ListReply(listOf(PoolReply("video", listOf(ModuleReply("video-core")))))

        assertEquals(FeatureInstallResult.Installed(2, false), result)
        assertEquals(
            listOf("block_component_library_status" to "observing-target-unconfirmed"),
            statuses
        )
        assertSame(
            source,
            registrar.invoke("${BlockComponentLibraryFeatureInstaller.ID}.sync", ModuleMoss()) { source }
        )
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED), stages)
    }

    @Test
    fun `disabled feature never resolves or registers the host hook`() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(
            FeatureInstallResult.Skipped("disabled"),
            BlockComponentLibraryFeatureInstaller(false).install(environment(registrar))
        )
        assertTrue(registrar.hooks.isEmpty())
    }
}
