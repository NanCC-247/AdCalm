package cn.adcalm.guard.core

import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import cn.adcalm.guard.model.Verdict
import org.junit.Assert.*
import org.junit.Test

/** 回归实际误操作边界，规则分和几何分不得掩盖关闭动作语义。 */
class ConfidenceScorerTest {
    private val screen = RectSnapshot(0, 0, 1080, 2400)
    private val corner = RectSnapshot(880, 120, 1040, 200)
    private fun node(
        text: String? = null,
        desc: String? = null,
        id: String? = null,
        bounds: RectSnapshot = corner,
        siblingTexts: List<String> = emptyList(),
    ) = NodeSnapshot(
        text = text, contentDescription = desc, viewId = id, bounds = bounds,
        clickable = true, siblingTexts = siblingTexts,
    )

    @Test fun `广告专用完整中英文文案和带数字文案可以操作`() {
        for (label in listOf("跳过广告", "关闭广告", "关闭广告页", "Skip Ad", "close ad", "跳过广告 3s")) {
            val candidate = ConfidenceScorer.score(node(text = label), screen)
            assertEquals(label, Verdict.CLICK, candidate.verdict)
            assertTrue(label, candidate.hasStrongEvidence)
            assertTrue(label, candidate.hasTextualClose)
        }
    }

    @Test fun `明确广告关闭不需要角落猜测`() {
        val candidate = ConfidenceScorer.score(node(text = "关闭广告", bounds = RectSnapshot(450, 1100, 610, 1180)), screen)
        assertEquals(Verdict.CLICK, candidate.verdict)
    }

    @Test fun `普通文档聊天或注册里的关闭跳过没有广告语境时不能操作`() {
        for (label in listOf("关闭", "跳过", "skip", "close", "关闭弹窗", "点击跳过")) {
            val candidate = ConfidenceScorer.score(node(text = label), screen)
            assertNotEquals(label, Verdict.CLICK, candidate.verdict)
            assertFalse(label, candidate.hasStrongEvidence)
        }
    }

    @Test fun `通用关闭只接受完整同层广告标记`() {
        for (marker in listOf("广告", "AD", "advertisement")) {
            assertEquals(marker, Verdict.CLICK, ConfidenceScorer.score(node(text = "关闭", siblingTexts = listOf(marker)), screen).verdict)
        }
        for (marker in listOf("推广", "不看广告", "广告设置", "赞助会员", "广告正文中的关闭")) {
            assertNotEquals(marker, Verdict.CLICK, ConfidenceScorer.score(node(text = "关闭", siblingTexts = listOf(marker)), screen).verdict)
        }
    }

    @Test fun `ID倒计时位置和任意高分规则不能授权点击`() {
        val candidates = listOf(
            node(id = "com.example:id/notclose"),
            node(id = "com.example:id/btn_skip"),
            node(text = "3", id = "com.example:id/close_timer"),
            node().copy(parentBounds = screen),
            node(text = "×", siblingTexts = listOf("广告")),
        )
        for (snapshot in candidates) {
            val candidate = ConfidenceScorer.score(snapshot, screen, hasCountdown = true, ruleScore = 999, ruleLabel = "宽泛规则")
            assertNotEquals(snapshot.toString(), Verdict.CLICK, candidate.verdict)
            assertFalse(candidate.hasStrongEvidence)
        }
    }

    @Test fun `付费会员安装确认继续不能伪装成关闭`() {
        for (label in listOf("付费关闭广告", "开通会员跳过广告", "关闭并安装", "确认关闭", "关闭后继续", "继续",
            "skip ad and pay", "confirm close", "download", "subscribe")) {
            val candidate = ConfidenceScorer.score(node(text = label, desc = "关闭广告"), screen, ruleScore = 999)
            assertEquals(label, Verdict.IGNORE, candidate.verdict)
            assertFalse(label, candidate.hasStrongEvidence)
        }
    }

    @Test fun `完整描述可以说明关闭图标但不能覆盖其他可见文案`() {
        assertEquals(Verdict.CLICK, ConfidenceScorer.score(node(text = "×", desc = "关闭广告"), screen).verdict)
        assertEquals(Verdict.CLICK, ConfidenceScorer.score(node(desc = "关闭广告"), screen).verdict)
        for (text in listOf("帮助", "新闻", "关闭自动播放", "notclose", "无法关闭", "点击关闭按钮退出")) {
            assertNotEquals(text, Verdict.CLICK, ConfidenceScorer.score(node(text = text, desc = "关闭广告"), screen).verdict)
        }
        assertNotEquals(Verdict.CLICK, ConfidenceScorer.score(node(desc = "关闭"), screen).verdict)
    }

    @Test fun `隐藏禁用和无有效独立热区均不操作`() {
        val base = node(text = "关闭广告")
        for (snapshot in listOf(
            base.copy(visible = false), base.copy(enabled = false), base.copy(bounds = RectSnapshot.EMPTY),
            base.copy(bounds = RectSnapshot(999, 324, 1015, 372)),
            base.copy(bounds = RectSnapshot(0, 60, 1080, 200)),
            base.copy(bounds = RectSnapshot(1000, 120, 1140, 200)),
        )) {
            assertNotEquals(snapshot.toString(), Verdict.CLICK, ConfidenceScorer.score(snapshot, screen).verdict)
        }
    }

    @Test fun `纯几何普通X即使旁边有广告也不构成关闭控件`() {
        val candidate = ConfidenceScorer.score(node(text = "×", siblingTexts = listOf("广告")).copy(parentBounds = screen), screen)
        assertEquals(Verdict.IGNORE, candidate.verdict)
        assertFalse(candidate.hasStrongEvidence)
    }

    @Test fun `不存在有效屏幕时拒绝明确文案`() {
        assertNotEquals(Verdict.CLICK, ConfidenceScorer.score(node(text = "关闭广告"), RectSnapshot.EMPTY).verdict)
    }
}
