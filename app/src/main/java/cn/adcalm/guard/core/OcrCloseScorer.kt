package cn.adcalm.guard.core

import cn.adcalm.guard.model.RectSnapshot

/** OCR 识别出的一个文本块，坐标已换算到屏幕坐标系。 */
data class OcrBlock(
    val text: String,
    val bounds: RectSnapshot,
)

/** OCR 找到的候选关闭按钮。 */
data class OcrCandidate(
    val text: String,
    val bounds: RectSnapshot,
    val score: Int,
    val reasons: List<String>,
) {
    /** 点击坐标：文本块中心。 */
    val clickX: Int get() = bounds.centerX
    val clickY: Int get() = bounds.centerY

    fun describe(): String =
        "OCR score=$score text='$text' bounds=$bounds [${reasons.joinToString(", ")}]"
}

/**
 * OCR 结果的判定逻辑。
 *
 * 这是 L3 兜底：节点树里找不到关闭按钮时（广告把按钮画在 Canvas 上、
 * 或者用 WebView 渲染且不暴露可点击节点），截图做文字识别。
 *
 * 与 L2 共用的安全底线：**必须能对上完整的关闭语义词**。
 * OCR 会返回整行文字，像「点击关闭按钮退出」这种只是包含"关闭"的句子一律拒绝——
 * 只认"跳过"「关闭」这类独立成词的短文案，这比节点树的判定更严格。
 *
 * 纯函数实现，不接触 Android 类，可直接单元测试。
 */
object OcrCloseScorer {

    /** 与 [ConfidenceScorer] 同一条点击线。 */
    const val CLICK_THRESHOLD = 65

    /** 文本块宽度超过屏幕这个比例就当成整行正文，不是按钮。 */
    private const val MAX_WIDTH_RATIO = 0.45

    /** 文本块高度超过屏幕这个比例就当成标题，不是按钮。 */
    private const val MAX_HEIGHT_RATIO = 0.10

    /**
     * @param blocks OCR 结果，坐标须已换算到屏幕坐标系
     * @return 分数最高的候选；没有任何块过线时返回 null
     */
    fun evaluate(blocks: List<OcrBlock>, screen: RectSnapshot): OcrCandidate? {
        if (!screen.isValid) return null

        var best: OcrCandidate? = null

        for (block in blocks) {
            if (!block.bounds.isValid) continue
            if (!ConfidenceScorer.isCloseText(block.text)) continue

            // 整行正文里恰好有"关闭"两个字的情况，靠尺寸挡掉
            val widthRatio = block.bounds.width.toDouble() / screen.width
            val heightRatio = block.bounds.height.toDouble() / screen.height
            if (widthRatio > MAX_WIDTH_RATIO || heightRatio > MAX_HEIGHT_RATIO) continue

            val reasons = mutableListOf<String>()
            var score = 0

            score += 45
            reasons += "+45:OCR 识别到完整关闭语义文案"

            if (ConfidenceScorer.isInCorner(block.bounds, screen)) {
                score += 20
                reasons += "+20:位于屏幕角落"
            } else if (ConfidenceScorer.isInCenter(block.bounds, screen)) {
                score -= 40
                reasons += "-40:位于屏幕中央，不像关闭按钮"
            }

            // 关闭叉/跳过按钮是很小的元素。这条同时兜住"OCR 把整段文字
            // 框成一块、里面碰巧以跳过开头"的情况。
            if (heightRatio < 0.04 && widthRatio < 0.20) {
                score += 15
                reasons += "+15:尺寸符合按钮特征"
            }

            val candidate = OcrCandidate(block.text, block.bounds, score, reasons)
            if (best == null || candidate.score > best.score) best = candidate
        }

        return best?.takeIf { it.score >= CLICK_THRESHOLD }
    }
}
