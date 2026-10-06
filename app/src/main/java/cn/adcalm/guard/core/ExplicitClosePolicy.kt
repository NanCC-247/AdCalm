package cn.adcalm.guard.core

import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import java.util.Locale

/**
 * 公开测试版的动作边界：只识别现有控件的完整关闭文案。
 * ID、位置、递减数字和规则命中均不能证明用户看到的是广告关闭控件。
 * 通用的「关闭」「跳过」还须有控件同层的广告标记；应用被选中、刚启动均不算广告语境。
 */
object ExplicitClosePolicy {
    private val AD_CLOSE_LABELS = setOf(
        "跳过广告", "略过广告", "关闭广告", "关闭广告页",
        "skip ad", "skip advertisement", "close ad", "close advertisement", "dismiss ad",
    )
    private val GENERIC_CLOSE_LABELS = setOf(
        "跳过", "略过", "关闭", "点击跳过", "关闭弹窗", "skip", "close", "dismiss",
    )
    private val AD_MARKERS = setOf("广告", "广告推广", "ad", "advertisement")
    private val ICON_TEXTS = setOf("×", "✕", "✖", "x")
    private val DISALLOWED_CHINESE = listOf(
        "付费", "收费", "支付", "付款", "购买", "会员", "订阅", "开通", "充值",
        "安装", "下载", "确认", "继续", "同意", "允许", "授权", "领取", "详情",
        "了解", "观看", "前往", "保存", "删除", "注销", "无法", "不能", "不可", "禁止",
    )
    private val DISALLOWED_ENGLISH = Regex(
        "\\b(pay|payment|purchase|buy|subscribe|subscription|premium|membership|install|download|" +
            "confirm|continue|accept|agree|allow|authorize|claim|details|learn|watch|save|delete|" +
            "logout|account|disable|not|cannot)\\b",
    )
    private val SPACES = Regex("\\s+")
    private val CHINESE_GAP = Regex("(?<=[\\p{IsHan}])\\s+(?=[\\p{IsHan}])")
    private val TRAILING_COUNT = Regex("^(.+?)\\s*\\d{1,2}\\s*[s秒]?$", RegexOption.IGNORE_CASE)
    // 仅剥包装括号。问号、否定词和其他正文标点不能被清洗成动作标签。
    private const val WRAPPERS = "()[]{}「」『』【】（）"

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if ('\n' in trimmed || '\r' in trimmed) return trimmed.lowercase(Locale.ROOT)
        val text = trimmed.trim { it in WRAPPERS }.trim().lowercase(Locale.ROOT)
            .replace(CHINESE_GAP, "").replace(SPACES, " ")
        if (text in AD_CLOSE_LABELS || text in GENERIC_CLOSE_LABELS) return text
        val match = TRAILING_COUNT.matchEntire(text) ?: return text
        val label = match.groupValues[1].trim()
        return if (label in AD_CLOSE_LABELS || label in GENERIC_CLOSE_LABELS) label else text
    }

    fun isExplicitCloseText(raw: String): Boolean =
        normalize(raw).let { it in AD_CLOSE_LABELS || it in GENERIC_CLOSE_LABELS }

    fun isAdSpecificCloseText(raw: String): Boolean = normalize(raw) in AD_CLOSE_LABELS

    fun isDisallowedText(raw: String): Boolean {
        val text = raw.lowercase(Locale.ROOT).replace(CHINESE_GAP, "")
        return DISALLOWED_CHINESE.any { it in text } || DISALLOWED_ENGLISH.containsMatchIn(text)
    }

    fun hasDisallowedAction(node: NodeSnapshot): Boolean =
        listOfNotNull(node.text, node.contentDescription).any(::isDisallowedText)

    /** 只使用紧邻同层的完整标记，屏幕上的广告正文或类名猜测不构成放行条件。 */
    fun hasLocalAdContext(node: NodeSnapshot): Boolean = node.siblingTexts.any {
        isAdMarker(it)
    }

    fun isAdMarker(raw: String): Boolean =
        raw.trim().lowercase(Locale.ROOT).replace(SPACES, " ") in AD_MARKERS

    fun isIconText(raw: String): Boolean = normalize(raw) in ICON_TEXTS

    /** 可见正文与描述冲突时不采用描述；只有无文字图标可由完整无障碍描述补充语义。 */
    fun hasExplicitCloseLabel(node: NodeSnapshot): Boolean {
        if (hasDisallowedAction(node)) return false
        val text = node.text.orEmpty().trim()
        if (text.isNotEmpty() && normalize(text) !in ICON_TEXTS) {
            return isExplicitCloseText(text) &&
                (node.contentDescription.isNullOrBlank() || isExplicitCloseText(node.contentDescription))
        }
        return node.contentDescription?.let(::isExplicitCloseText) == true
    }

    fun hasEvidence(node: NodeSnapshot): Boolean {
        if (!hasExplicitCloseLabel(node)) return false
        return isAdSpecificCloseText(node.text.orEmpty()) ||
            isAdSpecificCloseText(node.contentDescription.orEmpty()) || hasLocalAdContext(node)
    }

    fun isEligible(node: NodeSnapshot): Boolean =
        node.visible && node.enabled && node.bounds.isValid && hasEvidence(node)

    fun isInside(bounds: RectSnapshot, screen: RectSnapshot): Boolean =
        bounds.isValid && screen.isValid && bounds.left >= screen.left && bounds.top >= screen.top &&
            bounds.right <= screen.right && bounds.bottom <= screen.bottom
}
