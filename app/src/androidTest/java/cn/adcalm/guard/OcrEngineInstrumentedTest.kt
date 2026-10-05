package cn.adcalm.guard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.adcalm.guard.core.OcrBlock
import cn.adcalm.guard.core.OcrCloseScorer
import cn.adcalm.guard.model.RectSnapshot
import cn.adcalm.guard.ocr.OcrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OCR 整条链路的验证。
 *
 * 这是全项目里最该被真机验证、也最容易出问题的一段：模型有没有真的打进包、
 * ML Kit 在这个 Android 版本上能不能初始化、返回的坐标该怎么换算。
 * 这些靠单测的假数据一个都测不出来。
 *
 * 用合成图片而不是真实广告截图，是因为我们要验证的是**链路通不通**，
 * 不是识别准不准。识别准不准只能靠真实日志。
 */
@RunWith(AndroidJUnit4::class)
class OcrEngineInstrumentedTest {

    private fun createTextBitmap(
        width: Int,
        height: Int,
        text: String,
        x: Float,
        y: Float,
        textSize: Float = 90f,
        background: Int = Color.WHITE,
        foreground: Int = Color.BLACK,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val paint = Paint().apply {
            color = foreground
            this.textSize = textSize
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(text, x, y, paint)
        return bitmap
    }

    private fun recognize(bitmap: Bitmap): List<OcrBlock> = runBlocking {
        val engine = OcrEngine()
        try {
            engine.recognize(bitmap).map {
                OcrBlock(
                    text = it.text,
                    bounds = RectSnapshot(
                        it.rect.left, it.rect.top, it.rect.right, it.rect.bottom,
                    ),
                )
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun MLKit模型能在本机加载并识别中文() {
        val bitmap = createTextBitmap(600, 300, "跳过", 80f, 190f)
        val blocks = recognize(bitmap)

        val recognized = blocks.joinToString(" | ") { it.text }
        assertTrue(
            "模型应能识别出「跳过」。识别到：[$recognized]\n" +
                "若为空，说明 ML Kit 模型没有打进包，或该设备 ABI 缺少 native 库。",
            blocks.any { it.text.contains("跳过") },
        )
    }

    @Test
    fun 识别结果带有可用的包围盒() {
        val bitmap = createTextBitmap(600, 300, "跳过", 80f, 190f)
        val blocks = recognize(bitmap)

        val hit = blocks.firstOrNull { it.text.contains("跳过") }
        assertNotNull("应能找到「跳过」块", hit)
        assertTrue("包围盒应有宽度", hit!!.bounds.width > 0)
        assertTrue("包围盒应有高度", hit.bounds.height > 0)
        assertTrue(
            "包围盒应落在文字绘制的左半侧，实际 ${hit.bounds}",
            hit.bounds.left < 300,
        )
    }

    /**
     * 全链路：合成的"开屏广告"截图 → OCR → 判定。
     *
     * 这条用例的价值在于它用的是**真实的识别引擎**而不是构造的假数据。
     * 前一版所有 OCR 判定测试喂的都是手工构造的 OcrBlock，
     * 中间"引擎返回什么、坐标对不对"这一段完全没被验证过。
     */
    @Test
    fun 合成广告截图能走通OCR到判定的全链路() {
        val screen = RectSnapshot(0, 0, 1080, 2400)

        // 造一张深色背景的"广告页"，右上角画跳过按钮
        val bitmap = createTextBitmap(
            width = 1080,
            height = 2400,
            text = "跳过",
            x = 880f,
            y = 170f,
            textSize = 70f,
            background = Color.rgb(28, 30, 36),
            foreground = Color.WHITE,
        )

        val blocks = recognize(bitmap)
        val candidate = OcrCloseScorer.evaluate(blocks, screen)

        assertNotNull(
            "右上角的「跳过」应被判为可点击的关闭按钮。" +
                "识别到：${blocks.joinToString(" | ") { it.text }}",
            candidate,
        )
        // 点击坐标应落在右上角区域
        assertTrue(
            "点击点应位于屏幕右侧，实际 x=${candidate!!.clickX}",
            candidate.clickX > screen.width / 2,
        )
        assertTrue(
            "点击点应位于屏幕上部，实际 y=${candidate.clickY}",
            candidate.clickY < screen.height / 4,
        )
    }

    @Test
    fun 画面里没有关闭文案时不产生候选() {
        val screen = RectSnapshot(0, 0, 1080, 2400)
        val bitmap = createTextBitmap(
            width = 1080,
            height = 2400,
            text = "今日热点新闻",
            x = 100f,
            y = 170f,
            textSize = 60f,
            background = Color.rgb(28, 30, 36),
            foreground = Color.WHITE,
        )

        assertNull(
            "无关文案不该产生候选，识别到：${recognize(bitmap).joinToString(" | ") { it.text }}",
            OcrCloseScorer.evaluate(recognize(bitmap), screen),
        )
    }
}
