package cn.adcalm.guard.core

import cn.adcalm.guard.model.Candidate
import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import cn.adcalm.guard.model.ScoreReason
import cn.adcalm.guard.model.Verdict
import cn.adcalm.guard.rules.RuleResult
import cn.adcalm.guard.rules.RuleSet

/**
 * 在窗口节点树里找出所有"可能是关闭按钮"的节点，逐个打分后按分数降序返回。
 *
 * 三级策略中 L1（规则库）和 L2（启发式）在这里汇合；
 * L3（OCR 兜底）只在 L1/L2 都没找到候选时才触发。
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
            .filter { it.visible && it.bounds.isValid }
            // 太小的节点是布局噪声。实测日志里出现过 1x1 像素的"可点击"节点，
            // 它没有任何实际触达面积，不可能是能按到的按钮。
            .filter { it.bounds.width >= MIN_NODE_PX && it.bounds.height >= MIN_NODE_PX }
            .filter { it.clickable || it.text != null || it.contentDescription != null }
            .map { node -> evaluate(node, screen, nowMs, pkg, activity, ruleSet) }
            // 同分时优先"节点自己写着关闭"的候选，而不是"只有 id 像"的。
            // 广告会摆两个关闭按钮：真按钮有文案（要给人看，难造假），
            // 诱饵往往只有一个 skip 味的 id。按树序取第一个的话，谁在前谁中——那是运气。
            .sortedWith(
                compareByDescending<Candidate> { it.score }
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
                // 规则命中是"这个节点是什么"的明确判断。这里虽然是被否决，
                // 但证据类型相同——置 true 免得下游把它当成"只靠位置猜"的候选。
                hasStrongEvidence = true,
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
