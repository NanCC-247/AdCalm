package cn.adcalm.guard.core

import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import cn.adcalm.guard.model.Verdict
import cn.adcalm.guard.rules.MatchType
import cn.adcalm.guard.rules.RuleSet
import cn.adcalm.guard.rules.RuleTarget
import cn.adcalm.guard.rules.SplashRule
import org.junit.Assert.*
import org.junit.Test

class CloseButtonFinderTest {
    private val screen = RectSnapshot(0, 0, 1080, 2400)
    private val close = NodeSnapshot(text = "关闭广告", clickable = true, bounds = RectSnapshot(900, 100, 1040, 180), path = listOf(1))
    private fun root(vararg children: NodeSnapshot) = NodeSnapshot(bounds = screen, children = children.toList())

    @Test fun `可用明确控件优先于高分规则诱饵`() {
        val decoy = close.copy(text = null, viewId = "com.example:id/notclose", path = listOf(0))
        val rules = RuleSet(listOf(SplashRule(
            name = "旧ID规则", pkg = "com.example",
            targets = listOf(RuleTarget(MatchType.ID_CONTAINS, "close", 999)),
        )))
        val candidates = CloseButtonFinder().find(root(decoy, close), screen, 0, "com.example", ruleSet = rules)
        assertEquals("关闭广告", candidates.first().snapshot.text)
        assertEquals(Verdict.CLICK, candidates.first().verdict)
        assertFalse(candidates.first { it.snapshot.viewId != null }.hasStrongEvidence)
    }

    @Test fun `隐藏禁用越界关闭不进入动作候选`() {
        val candidates = CloseButtonFinder().find(root(
            close.copy(visible = false), close.copy(enabled = false),
            close.copy(bounds = RectSnapshot(1000, 100, 1140, 180)),
        ), screen, 0)
        assertFalse(candidates.any { it.verdict == Verdict.CLICK })
    }

    @Test fun `规则黑名单可以拒绝明确关闭但不能创建动作权限`() {
        val blocked = close.copy(viewId = "com.example:id/false_close")
        val rules = RuleSet(listOf(SplashRule(name = "拒绝诱饵", pkg = "com.example", blacklist = listOf("false_close"))))
        val candidate = CloseButtonFinder().find(root(blocked), screen, 0, "com.example", ruleSet = rules)
            .first { it.snapshot.viewId != null }
        assertEquals(Verdict.IGNORE, candidate.verdict)
        assertFalse(candidate.hasStrongEvidence)
    }

    @Test fun `连续递减数字不转为关闭控件`() {
        val finder = CloseButtonFinder()
        val timer = close.copy(text = "3", viewId = "com.example:id/timer")
        finder.find(root(timer), screen, 0)
        val candidates = finder.find(root(timer.copy(text = "2")), screen, 1000)
        assertFalse(candidates.any { it.verdict == Verdict.CLICK || it.hasStrongEvidence })
    }
}
