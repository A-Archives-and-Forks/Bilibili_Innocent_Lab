package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住"谁有资格把哔哩哔哩拉起来"。
 *
 * 保底唤起是**用户看得见的副作用**（后台多出一个宿主进程），只有用户主动点开某个
 * 需要宿主扫描结果的面板时才有正当性。而同一条 `ReceiptQueryTransport` 还服务着
 * 诊断通道，它的调用方里有 `TelemetryCoordinator`（自动 24h 一次 + 15 分钟本地重试）
 * 和启动时的激活卡检查——**这两条绝不能顺手启动宿主**：既违背"遥测不在 B 站进程里做
 * 任何事"的口径，也是用户没要求过的后台行为。
 *
 * `allowWake` 默认 false，所以退化方向是"有人手滑加了 true"。这里按源码文本钉住
 * 允许名单：唯一准许打开的是扫描快照客户端。
 */
class HostProcessWakeCallSiteTest {

    private val runtimeDir: File by lazy {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/runtime"
        sequenceOf(File(path), File("app/$path")).firstOrNull(File::isDirectory)
            ?: error("找不到 runtime 源码目录")
    }

    private fun sources(): List<File> =
        runtimeDir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    @Test fun theScanActuallySeesTheRuntimeSources() {
        val names = sources().map(File::getName)
        assertTrue("没扫到 runtime 源码，护栏会静默通过", names.size >= 20)
        listOf("ReceiptQueryTransport.kt", "MineComponentSnapshotQueryClient.kt",
            "HostRuntimeDiagnosticsQueryClient.kt").forEach {
            assertTrue("$it 不在扫描范围内", it in names)
        }
    }

    @Test fun onlyTheScanSnapshotClientMayWakeTheHost() {
        val offenders = sources().filter { it.readText().contains("allowWake = true") }
            .map(File::getName)
            .filterNot { it == "MineComponentSnapshotQueryClient.kt" }
        assertEquals("唤起宿主只准由用户主动打开的扫描面板触发", emptyList<String>(), offenders)
    }

    /** 遥测与诊断通道必须保持默认值；显式写 false 也行，写 true 不行。 */
    @Test fun theDiagnosticsChannelStaysOnTheDefault() {
        val diagnostics = File(runtimeDir, "HostRuntimeDiagnosticsQueryClient.kt").readText()
        assertTrue("诊断通道仍应调用同一条 transport", diagnostics.contains("ReceiptQueryTransport.query("))
        assertEquals(false, diagnostics.contains("allowWake = true"))
    }
}
