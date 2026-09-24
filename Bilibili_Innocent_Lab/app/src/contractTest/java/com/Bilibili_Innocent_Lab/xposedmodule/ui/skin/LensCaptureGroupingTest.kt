package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.LensCaptureGrouping
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 柔光透镜采集的录制分组（2026-09-23）：顶栏胶囊与它上面的三个圆按钮采样区重叠，原来是
 * 4 次完整的视图树遍历，合并后一次；底栏单独一组，走与合并前逐字节相同的路径。
 */
class LensCaptureGroupingTest {

    private fun groups(count: Int, pairs: Set<Pair<Int, Int>>) =
        LensCaptureGrouping.group(count) { a, b -> (a to b) in pairs || (b to a) in pairs }
            .map(IntArray::toList)

    @Test fun overlappingSurfacesShareOneRecordingAndDisjointOnesStayAlone() {
        // 0 = 顶栏，1..3 = 圆按钮（都叠在顶栏上），4 = 底栏。
        val result = groups(5, setOf(0 to 1, 0 to 2, 0 to 3))
        assertEquals(listOf(listOf(0, 1, 2, 3), listOf(4)), result)
    }

    @Test fun overlapIsTransitive() {
        // A 叠 B、B 叠 C，A 与 C 不直接相交：仍是一组，否则 C 的录制区会漏掉与 B 共享的内容。
        assertEquals(listOf(listOf(0, 1, 2)), groups(3, setOf(0 to 1, 1 to 2)))
    }

    @Test fun orderIsStableSoPooledPicturesKeepTheirGroup() {
        val first = groups(4, setOf(2 to 3))
        val second = groups(4, setOf(3 to 2))
        assertEquals(first, second)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3)), first)
    }

    @Test fun degenerateInputs() {
        assertEquals(emptyList<List<Int>>(), groups(0, emptySet()))
        assertEquals(listOf(listOf(0)), groups(1, emptySet()))
    }

    private fun source(relative: String): String {
        val path = "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/skin/$relative"
        return sequenceOf(File(path), File("app/$path")).first(File::isFile).readText()
    }

    /**
     * 录制期间的祖先备忘只能包住"视图树静止"的区间：本次 pre-draw 的采集。放到别处（比如跨帧
     * 或包住动画回调）会把移动前的矩阵带进移动后的绘制。
     */
    @Test fun ancestorMemoIsScopedToOneCaptureBatchOnASharedMatrixTool() {
        val sampler = source("material/LiveBackdropSampler.kt")
        assertTrue("采集批次必须包在祖先备忘里",
            sampler.contains("samplingMatrices.withAncestorMemo { collectJobs(content) }"))
        assertTrue("采样器必须用渲染器的矩阵工具，录制里的静态磨砂映射才吃得到备忘",
            !sampler.contains("private val samplingMatrices = ViewSamplingMatrix()"))
        val renderer = source("material/FrostedMaterialRenderer.kt")
        assertTrue(renderer.contains("LiveBackdropSampler(density, samplingMatrices)"))
        val matrices = source("geometry/ViewSamplingMatrix.kt")
        val scope = matrices.substringAfter("fun <T> withAncestorMemo(").substringBefore("fun localToScreen(")
        assertTrue("作用域结束必须清空备忘，不跨帧保留任何 View",
            scope.contains("finally") && scope.contains("memo.clear()") && scope.contains("memoActive = false"))
        val grouped = sampler.substringAfter("private fun capture(").substringBefore("private fun job(")
        assertTrue("单成员组必须保持带完整变换录制（与合并前逐字节相同）",
            grouped.contains("if (members.size == 1)") && grouped.contains("recording.concat(entry.sourceToTarget)"))
        assertTrue("多成员组的回放变换必须与单成员录制同一次序，最后平移回组原点",
            grouped.indexOf("entry.replay.setScale(") < grouped.indexOf("entry.replay.preConcat(entry.sourceToTarget)") &&
                grouped.indexOf("entry.replay.preConcat(entry.sourceToTarget)") <
                grouped.indexOf("entry.replay.preTranslate(originX.toFloat(), originY.toFloat())"))
    }
}
