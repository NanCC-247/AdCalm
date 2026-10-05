package cn.adcalm.guard.core

import cn.adcalm.guard.model.RectSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OCR 结果判定的边界测试。
 *
 * 这是全项目里误判代价最高的一条路径：节点树判定错了顶多点到别的控件，
 * OCR 判定错了会按坐标乱点，可能正好点开广告。所以这里的用例密度比别处高。
 */
class OcrCloseScorerTest {

    private val screen = RectSnapshot(0, 0, 1080, 2400)

    private fun block(text: String, l: Int, t: Int, r: Int, b: Int) =
        OcrBlock(text, RectSnapshot(l, t, r, b))

    /** 右上角一个 100x60 的小文本块——真关闭按钮的典型形态。 */
    private fun cornerBlock(text: String) = block(text, 900, 100, 1000, 160)

    // ---- 应当点击 ----

    @Test
    fun `角落里的跳过能被识别并达线`() {
        // 45 (关闭语义) + 20 (角落) + 15 (尺寸像按钮) = 80
        val c = OcrCloseScorer.evaluate(listOf(cornerBlock("跳过")), screen)
        assertNotNull(c)
        assertEquals(80, c!!.score)
        assertEquals("跳过", c.text)
    }

    @Test
    fun `带倒计时的跳过同样识别`() {
        val c = OcrCloseScorer.evaluate(listOf(cornerBlock("跳过 3")), screen)
        assertNotNull("OCR 常把跳过和倒计时一起读出来", c)
        assertEquals(80, c!!.score)
    }

    @Test
    fun `圆圈里的跳过被读成带括号时仍能达线`() {
        // 2026-10-05 真机原文：广告左下角那个「跳过」画在圆圈里，OCR 读回来是 `(跳过`——
        // 圆圈的左半边被认成了括号。精确匹配的时代价是**整条被拒**，
        // 而它的位置（45+20）和尺寸（15）全对，本该是 80 分。
        val c = OcrCloseScorer.evaluate(listOf(block("(跳过", 69, 2130, 142, 2163)), screen)
        assertNotNull("带包裹符号的关闭文案不该被丢掉", c)
        assertEquals(80, c!!.score)
    }

    @Test
    fun `关闭 也能识别`() {
        val c = OcrCloseScorer.evaluate(listOf(cornerBlock("关闭")), screen)
        assertNotNull(c)
    }

    @Test
    fun `点击坐标取文本块中心`() {
        val c = OcrCloseScorer.evaluate(listOf(cornerBlock("跳过")), screen)!!
        assertEquals(950, c.clickX)
        assertEquals(130, c.clickY)
    }

    @Test
    fun `多个候选时取分最高的`() {
        val blocks = listOf(
            block("关闭", 500, 1150, 580, 1210),   // 屏幕中央，会被扣分
            cornerBlock("跳过"),                    // 角落里，高分
        )
        val c = OcrCloseScorer.evaluate(blocks, screen)
        assertNotNull(c)
        assertEquals("跳过", c!!.text)
    }

    // ---- 必须拒绝 ----

    @Test
    fun `整句里包含关闭两个字的不算`() {
        // OCR 会把整行读成一块。只认独立成词的短文案，包含关系一律拒绝——
        // 这是 OCR 路径最重要的安全阀。
        val c = OcrCloseScorer.evaluate(listOf(cornerBlock("点击关闭按钮退出")), screen)
        assertNull("包含关系不该命中", c)
    }

    @Test
    fun `屏幕中央的关闭不点`() {
        val c = OcrCloseScorer.evaluate(listOf(block("关闭", 490, 1150, 590, 1210)), screen)
        assertNull("中央位置证据不足", c)
    }

    @Test
    fun `横跨整行的文本块被拒绝`() {
        // 即使内容就是"跳过"，横跨整个屏幕宽度的也不可能是按钮
        val c = OcrCloseScorer.evaluate(listOf(block("跳过", 0, 100, 1080, 160)), screen)
        assertNull(c)
    }

    @Test
    fun `过高的文本块被当成标题拒绝`() {
        val c = OcrCloseScorer.evaluate(listOf(block("跳过", 900, 100, 1000, 400)), screen)
        assertNull(c)
    }

    @Test
    fun `识别结果为空时不产生候选`() {
        assertNull(OcrCloseScorer.evaluate(emptyList(), screen))
    }

    @Test
    fun `无关文案不产生候选`() {
        val blocks = listOf(
            cornerBlock("北京天气"),
            cornerBlock("17"),
            cornerBlock("消息"),
        )
        assertNull(OcrCloseScorer.evaluate(blocks, screen))
    }

    @Test
    fun `屏幕尺寸无效时不做判定`() {
        val c = OcrCloseScorer.evaluate(listOf(cornerBlock("跳过")), RectSnapshot.EMPTY)
        assertNull(c)
    }
}
