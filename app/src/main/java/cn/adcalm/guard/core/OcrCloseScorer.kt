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
 * 默认仅接受完整广告专用关闭文案，例如「跳过广告」。
 * OCR 没有控件结构，整句包含关闭词、通用关闭词、裸 X 和倒计时均不能靠位置获准点击。
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
     * @param hasAdContext 调用方独立核验的当前广告控件语境；不得按启动时间、应用选择或位置猜测
     * @return 分数最高的候选；没有任何块过线时返回 null
     */
    fun evaluate(
        blocks: List<OcrBlock>,
        screen: RectSnapshot,
        hasAdContext: Boolean = false,
    ): OcrCandidate? {
        if (!screen.isValid) return null

        var best: OcrCandidate? = null

        for (block in blocks) {
            if (!ExplicitClosePolicy.isInside(block.bounds, screen)) continue
            if (ExplicitClosePolicy.isDisallowedText(block.text)) continue
            if (!ConfidenceScorer.isCloseText(block.text)) continue
            // OCR 没有控件结构，裸「关闭」可能只是文档、聊天或设置里的文字。
            // 默认只接受广告专用完整文案；启动时间、位置和应用选择不能提供语境。
            if (!ExplicitClosePolicy.isAdSpecificCloseText(block.text) && !hasAdContext) continue

            // 无效、整行或标题尺寸不构成独立控件热区。
            val widthRatio = block.bounds.width.toDouble() / screen.width
            val heightRatio = block.bounds.height.toDouble() / screen.height
            if (widthRatio > MAX_WIDTH_RATIO || heightRatio > MAX_HEIGHT_RATIO) continue

            val reasons = mutableListOf<String>()
            var score = 0

            score += 65
            reasons += "+65:OCR 识别到完整广告关闭文案，或已验证广告语境内的完整关闭文案"

            // 尺寸只作拒绝条件；不能据此把未知文本变成关闭控件。
            if (heightRatio < 0.04 && widthRatio < 0.20 &&
                block.bounds.width >= MIN_TEXT_SIDE_PX && block.bounds.height >= MIN_TEXT_SIDE_PX
            ) {
                score += 15
                reasons += "+15:尺寸符合按钮特征"
            } else continue

            val candidate = OcrCandidate(block.text, block.bounds, score, reasons)
            if (best == null || candidate.score > best.score) best = candidate
        }

        return best?.takeIf { it.score >= CLICK_THRESHOLD }
    }

    private const val MIN_TEXT_SIDE_PX = 16
}
