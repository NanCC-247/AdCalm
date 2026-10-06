package cn.adcalm.guard.core

import cn.adcalm.guard.model.Candidate
import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import cn.adcalm.guard.model.ScoreReason
import cn.adcalm.guard.model.Verdict
import cn.adcalm.guard.rules.RuleResult
import cn.adcalm.guard.rules.RuleSet

/**
 * 按明确文案和可验证广告语境识别已有关闭控件，合格动作候选优先。
 * 规则只提供诊断信息和拒绝条件，不能绕过公开测试版的动作边界。
 */
class CloseButtonFinder(
    private val countdownTracker: CountdownTracker = CountdownTracker(),
) {

    fun find(
        root: NodeSnapshot,
        screen: RectSnapshot,
        nowMs: Long,
        pkg: String = "",
        activity: String? = null,
        ruleSet: RuleSet? = null,
    ): List<Candidate> {
        return root.walk()
            .filter { it.visible && it.enabled && ExplicitClosePolicy.isInside(it.bounds, screen) }
            // 太小的节点是布局噪声。实测日志里出现过 1x1 像素的"可点击"节点，
            // 它没有任何实际触达面积，不可能是能按到的按钮。
            .filter { it.bounds.width >= MIN_NODE_PX && it.bounds.height >= MIN_NODE_PX }
            .filter { it.clickable || it.text != null || it.contentDescription != null }
            .map { node -> evaluate(node, screen, nowMs, pkg, activity, ruleSet) }
            // 合格控件优先；同分优先可见文案，ID 命中不创造动作权限。
            .sortedWith(
                compareByDescending<Candidate> { it.verdict == Verdict.CLICK }
                    .thenByDescending { it.score }
                    .thenByDescending { it.hasTextualClose },
            )
            .toList()
    }

    private fun evaluate(
        node: NodeSnapshot,
        screen: RectSnapshot,
        nowMs: Long,
        pkg: String,
        activity: String?,
        ruleSet: RuleSet?,
    ): Candidate {
        val hasCountdown = countdownTracker.observe(
            countdownTracker.signatureOf(node),
            node.text,
            nowMs,
        )

        return when (val rule = ruleSet?.match(node, pkg, activity)) {
            // 黑名单命中（节点就是广告本身）直接否决，不给任何翻身机会
            is RuleResult.Veto -> Candidate(
                path = node.path,
                snapshot = node,
                score = VETO_SCORE,
                reasons = listOf(ScoreReason(VETO_SCORE, rule.detail)),
                verdict = Verdict.IGNORE,
                hasStrongEvidence = false,
            )

            is RuleResult.Hit -> ConfidenceScorer.score(
                node, screen, hasCountdown, rule.score, rule.detail,
            )

            null -> ConfidenceScorer.score(node, screen, hasCountdown)
        }
    }

    companion object {
        /** 黑名单否决的分数，压到任何阈值以下。 */
        const val VETO_SCORE = -1000

        /** 候选节点的最小边长（像素）。低于这个尺寸属于布局噪声。 */
        private const val MIN_NODE_PX = 16
    }
}
