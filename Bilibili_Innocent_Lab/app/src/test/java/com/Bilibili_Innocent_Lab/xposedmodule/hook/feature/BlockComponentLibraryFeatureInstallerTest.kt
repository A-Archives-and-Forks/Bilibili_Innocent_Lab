package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.bapis.bilibili.app.resource.v1.ListReply
import com.bapis.bilibili.app.resource.v1.ModuleMoss
import com.bapis.bilibili.app.resource.v1.ModuleReply
import com.bapis.bilibili.app.resource.v1.PoolReply
import com.bilibili.lib.moss.api.MossResponseHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockComponentLibraryFeatureInstallerTest {
    private val loader = javaClass.classLoader!!

    private fun environment(
        registrar: PlayerPortTestRegistrar,
        stages: MutableList<FeatureRuntimeStage> = mutableListOf()
    ) = HookEnvironment(
        processName = "tv.danmaku.bili:download",
        classLoader = loader,
        hookPoints = HookPointRegistry(loader),
        registrar = registrar,
        logInfo = { _, _ -> },
        logError = { _, _ -> },
        reportStatus = { _, _ -> },
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
    fun `async response hook transforms only the delegated onNext reply`() {
        val registrar = PlayerPortTestRegistrar()
        BlockComponentLibraryFeatureInstaller(true, setOf("基础组件库"))
            .install(environment(registrar))
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
    }

    @Test
    fun `unrecognized response is preserved by identity`() {
        val registrar = PlayerPortTestRegistrar()
        val stages = mutableListOf<FeatureRuntimeStage>()
        BlockComponentLibraryFeatureInstaller(true).install(environment(registrar, stages))
        val source = ListReply(listOf(PoolReply("video", listOf(ModuleReply("video-core")))))

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
