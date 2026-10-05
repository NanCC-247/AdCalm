package cn.adcalm.guard.core

import cn.adcalm.guard.core.SelfCheckReport.Check
import cn.adcalm.guard.core.SelfCheckReport.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自检报告的排版。
 *
 * 要守住的两条：**每一项都要带上证据**（否则和"没测"分不清），
 * 以及**小结三项都报**——"0 项失败"本身就是结论，省掉反而让人怀疑没统计。
 */
class SelfCheckReportTest {

    private val header = listOf("AdCalm 能力自检", "构建 0.1.0@123")

    @Test
    fun `每项都带名字、结论和证据`() {
        val text = SelfCheckReport.render(
            header,
            listOf(
                Check("无障碍服务", Outcome.PASS, "服务实例在"),
                Check("手势注入", Outcome.FAIL, "返回 true，界面没反应"),
            ),
        )
        assertTrue(text.contains("[通过] 无障碍服务"))
        assertTrue(text.contains("服务实例在"))
        assertTrue(text.contains("[失败] 手势注入"))
        assertTrue(text.contains("返回 true，界面没反应"))
    }

    @Test
    fun `表头原样输出在结论之前`() {
        val text = SelfCheckReport.render(header, listOf(Check("x", Outcome.PASS, "y")))
        val headerIndex = text.indexOf("构建 0.1.0@123")
        val firstCheckIndex = text.indexOf("[通过] x")
        assertTrue("表头应在结论之前", headerIndex in 0 until firstCheckIndex)
    }

    @Test
    fun `未知单独一档，不会被算成失败或通过`() {
        // 把"没测"混进"失败"会虚报问题，混进"通过"会虚报能力——而这个项目最怕虚报能力。
        val text = SelfCheckReport.render(
            header,
            listOf(
                Check("a", Outcome.PASS, ""),
                Check("b", Outcome.UNKNOWN, "没有 Shizuku，测不了"),
            ),
        )
        assertTrue(text.contains("[未知] b"))
        assertTrue(text.contains("1 项未知"))
        assertTrue(text.contains("0 项失败"))
    }

    @Test
    fun `小结三项都报，包括 0`() {
        assertEquals(
            "小结：2 项通过 / 0 项失败 / 1 项未知",
            SelfCheckReport.summaryOf(
                listOf(
                    Check("a", Outcome.PASS, ""),
                    Check("b", Outcome.PASS, ""),
                    Check("c", Outcome.UNKNOWN, ""),
                ),
            ),
        )
    }

    @Test
    fun `一项都没有时小结仍然是三档齐全`() {
        // 检查还没跑完的时候界面上就是这一档；缺一档会让人以为那一类没统计。
        assertEquals("小结：0 项通过 / 0 项失败 / 0 项未知", SelfCheckReport.summaryOf(emptyList()))
    }

    @Test
    fun `空证据也保留那一行，不把项省掉`() {
        // 证据为空是调用方的问题，但**项本身不能消失**——少一项比空证据更难查。
        val text = SelfCheckReport.render(header, listOf(Check("截图通道", Outcome.PASS, "")))
        assertTrue(text.contains("[通过] 截图通道"))
        assertTrue(text.contains("1 项通过"))
    }
}
