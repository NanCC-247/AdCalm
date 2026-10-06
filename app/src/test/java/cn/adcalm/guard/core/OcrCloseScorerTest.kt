package cn.adcalm.guard.core

import cn.adcalm.guard.model.RectSnapshot
import org.junit.Assert.*
import org.junit.Test

class OcrCloseScorerTest {
    private val screen = RectSnapshot(0, 0, 1080, 2400)
    private fun block(text: String, bounds: RectSnapshot = RectSnapshot(900, 100, 1040, 160)) = OcrBlock(text, bounds)

    @Test fun `OCR默认只接受完整广告专用文案`() {
        for (label in listOf("跳过广告", "关闭广告", "Skip Ad", "close ad", "跳过广告 3s", "(跳过广告", "跳 过 广 告")) {
            val candidate = OcrCloseScorer.evaluate(listOf(block(label)), screen)
            assertNotNull(label, candidate)
            assertEquals(80, candidate!!.score)
        }
    }

    @Test fun `坐标中心来自完整文本块不偏移`() {
        val candidate = OcrCloseScorer.evaluate(listOf(block("关闭广告")), screen)!!
        assertEquals(970, candidate.clickX)
        assertEquals(130, candidate.clickY)
    }

    @Test fun `裸关闭跳过普通X和倒计时不能靠角落放行`() {
        for (label in listOf("关闭", "跳过", "(跳过", "skip", "close", "×", "3", "notclose")) {
            assertNull(label, OcrCloseScorer.evaluate(listOf(block(label)), screen))
        }
    }

    @Test fun `通用文案只有调用方独立核验广告语境后才支持`() {
        assertNotNull(OcrCloseScorer.evaluate(listOf(block("跳过")), screen, hasAdContext = true))
        assertNull(OcrCloseScorer.evaluate(listOf(block("×")), screen, hasAdContext = true))
        assertNull(OcrCloseScorer.evaluate(listOf(block("继续")), screen, hasAdContext = true))
    }

    @Test fun `完整句子付费会员安装确认继续均不变成关闭操作`() {
        for (label in listOf("点击关闭按钮退出", "会员关闭广告", "安装后跳过广告", "确认关闭广告",
            "关闭广告继续", "skip ad and pay", "关闭广告?", "无法关闭广告")) {
            assertNull(label, OcrCloseScorer.evaluate(listOf(block(label)), screen, hasAdContext = true))
        }
    }

    @Test fun `明确文案不需要角落猜测但目标必须足够小`() {
        assertNotNull(OcrCloseScorer.evaluate(listOf(block("关闭广告", RectSnapshot(450, 1100, 590, 1160))), screen))
        for (bounds in listOf(
            RectSnapshot(0, 100, 1080, 160), RectSnapshot(900, 100, 1040, 400),
            RectSnapshot(900, 100, 1040, 110), RectSnapshot(1000, 100, 1140, 160), RectSnapshot.EMPTY,
        )) {
            assertNull(bounds.toString(), OcrCloseScorer.evaluate(listOf(block("关闭广告", bounds)), screen))
        }
    }

    @Test fun `无内容或无屏幕不产生坐标`() {
        assertNull(OcrCloseScorer.evaluate(emptyList(), screen))
        assertNull(OcrCloseScorer.evaluate(listOf(block("关闭广告")), RectSnapshot.EMPTY))
    }

    @Test fun `不能用广告正文让屏幕上别的关闭获得语境`() {
        val blocks = listOf(block("广告"), block("关闭"))
        assertNull(OcrCloseScorer.evaluate(blocks, screen))
    }
}
