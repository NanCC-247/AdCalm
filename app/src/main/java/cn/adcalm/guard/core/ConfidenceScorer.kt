package cn.adcalm.guard.core

import cn.adcalm.guard.model.Candidate
import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import cn.adcalm.guard.model.ScoreReason
import cn.adcalm.guard.model.Verdict
import kotlin.math.abs

/** 关闭控件评分。分数用于排序，完整广告关闭语义和可用目标是独立的执行条件。 */
object ConfidenceScorer {
    const val CLICK_THRESHOLD = 65
    const val SUSPECT_THRESHOLD = 40

    fun normalizeCloseText(raw: String): String = ExplicitClosePolicy.normalize(raw)
    fun isCloseText(raw: String): Boolean = ExplicitClosePolicy.isExplicitCloseText(raw)

    /**
     * 保留倒计时和规则输入供诊断；它们不能绕过完整文案、广告语境、可见/启用和尺寸边界。
     */
    fun score(
        node: NodeSnapshot,
        screen: RectSnapshot,
        hasCountdown: Boolean = false,
        ruleScore: Int? = null,
        ruleLabel: String? = null,
    ): Candidate {
        val reasons = mutableListOf<ScoreReason>()
        var total = 0
        fun add(delta: Int, label: String) {
            total += delta
            reasons += ScoreReason(delta, label)
        }

        val hasLabel = ExplicitClosePolicy.hasExplicitCloseLabel(node)
        val hasEvidence = ExplicitClosePolicy.hasEvidence(node)
        val hasTextualClose = hasLabel && isCloseText(node.text.orEmpty())
        val disallowed = ExplicitClosePolicy.hasDisallowedAction(node)

        if (disallowed) {
            add(-1000, "含付费、会员、安装、确认、继续或其他非关闭动作，禁止自动操作")
        } else {
            if (hasLabel) add(55, "控件自身具有完整关闭文案或完整无障碍描述")
            if (hasEvidence) add(20, "广告专用关闭文案，或同层明确广告标记")
            if (hasLabel && !hasEvidence) {
                reasons += ScoreReason(0, "通用关闭文案缺少可验证广告语境，不允许操作")
            }
            if (node.viewId?.contains(Regex("skip|close|dismiss", RegexOption.IGNORE_CASE)) == true) {
                reasons += ScoreReason(0, "ID 仅供诊断，不能替代可见关闭控件")
            }
        }
        if (hasCountdown) reasons += ScoreReason(0, "观测到倒计时；数值递减不作为关闭动作证据")
        if (ruleScore != null) {
            reasons += ScoreReason(0, "${ruleLabel ?: "规则命中"}（诊断分 $ruleScore），不得提升自动点击权限")
        }

        val validTarget = ExplicitClosePolicy.isInside(node.bounds, screen) &&
            minOf(node.bounds.width, node.bounds.height) >= MIN_BUTTON_SIDE &&
            smallEnoughToBeCloseButton(node.bounds, screen)
        if (!node.visible || !node.enabled) reasons += ScoreReason(0, "控件不可见或未启用")
        if (!validTarget) reasons += ScoreReason(0, "目标越界、无效或尺寸不适合独立关闭控件")

        val verdict = when {
            disallowed || !node.visible || !node.enabled -> Verdict.IGNORE
            hasEvidence && validTarget && total >= CLICK_THRESHOLD -> Verdict.CLICK
            total >= SUSPECT_THRESHOLD -> Verdict.SUSPECT
            else -> Verdict.IGNORE
        }
        return Candidate(node.path, node, total, reasons, verdict, hasEvidence && !disallowed, hasTextualClose)
    }

    /** 仅用于诊断位置；不能凭几何特征生成关闭动作证据。 */
    fun isInCorner(b: RectSnapshot, s: RectSnapshot): Boolean {
        if (!ExplicitClosePolicy.isInside(b, s) || !smallEnoughToBeCloseButton(b, s)) return false
        val horizontal = b.left < s.left + s.width * 0.15 || b.right > s.right - s.width * 0.15
        val vertical = b.top < s.top + s.height * 0.20 || b.bottom > s.bottom - s.height * 0.20
        return horizontal && vertical
    }

    fun isAtTopEdge(b: RectSnapshot, s: RectSnapshot): Boolean =
        ExplicitClosePolicy.isInside(b, s) && smallEnoughToBeCloseButton(b, s) &&
            b.top < s.top + s.height * 0.12

    fun isInCenter(b: RectSnapshot, s: RectSnapshot): Boolean {
        if (!ExplicitClosePolicy.isInside(b, s)) return false
        val dx = abs(b.centerX - (s.left + s.width / 2.0)) / (s.width / 2.0)
        val dy = abs(b.centerY - (s.top + s.height / 2.0)) / (s.height / 2.0)
        return dx < 0.5 && dy < 0.5
    }

    fun isAtParentCorner(b: RectSnapshot, parent: RectSnapshot?): Boolean {
        if (parent == null || !ExplicitClosePolicy.isInside(b, parent) || parent.area < b.area * 6) return false
        val horizontal = b.centerX - parent.left < parent.width * 0.25 ||
            parent.right - b.centerX < parent.width * 0.25
        val vertical = b.centerY - parent.top < parent.height * 0.35 ||
            parent.bottom - b.centerY < parent.height * 0.35
        return horizontal && vertical
    }

    private fun smallEnoughToBeCloseButton(b: RectSnapshot, s: RectSnapshot): Boolean =
        b.width <= s.width * 0.5 && b.height <= s.height * 0.15 && b.area <= s.area * 0.045

    private const val MIN_BUTTON_SIDE = 36
}
