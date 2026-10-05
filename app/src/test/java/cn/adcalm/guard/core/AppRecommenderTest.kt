package cn.adcalm.guard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「广告软件更新」的推荐逻辑。
 *
 * 口径是 **用户真的在用的应用 ∪ 已经确认有广告的应用**：
 * · 新用户还没有日志，只有用量那一半（USAGE_ONLY）
 * · 用一段时间后，广告应用自动并进来（USAGE_AND_ADS）
 *
 * 这里钉的是几处边界：什么时候算「用得多」、什么时候该老实说推不出来、
 * 别把没装的应用推出来。
 */
class AppRecommenderTest {

    private val installed = listOf("com.a", "com.b", "com.c", "com.d")

    /** 用量排序的简写：输入 (包名, 分钟数)，转成毫秒。 */
    private fun usage(vararg pairs: Pair<String, Long>) =
        pairs.map { it.first to it.second * 60_000L }.sortedByDescending { it.second }

    @Test
    fun `新用户没有日志时只按用量推荐`() {
        val s = AppRecommender.suggest(
            selectable = installed,
            adEvidence = emptyMap(),
            usageRanked = usage("com.a" to 120, "com.b" to 45, "com.c" to 2),
        )
        assertEquals(AppRecommender.Basis.USAGE_ONLY, s.basis)
        // com.c 只用了 2 分钟，低于 10 分钟的下限
        assertEquals(setOf("com.a", "com.b"), s.packages)
    }

    @Test
    fun `有日志后广告应用并进用量那批`() {
        // 用户要的就是这个：常用的照收，另外把日志里确认有广告的也加进来——
        // 哪怕它自己不怎么用（比如偶尔才打开的购物应用）。
        val s = AppRecommender.suggest(
            selectable = installed,
            adEvidence = mapOf("com.d" to 8),
            usageRanked = usage("com.a" to 120, "com.b" to 45),
        )
        assertEquals(AppRecommender.Basis.USAGE_AND_ADS, s.basis)
        assertEquals(setOf("com.a", "com.b", "com.d"), s.packages)
    }

    @Test
    fun `广告证据不足阈值的不算数`() {
        val s = AppRecommender.suggest(
            selectable = installed,
            adEvidence = mapOf("com.d" to AppRecommender.AD_EVIDENCE_THRESHOLD - 1),
            usageRanked = usage("com.a" to 120),
        )
        assertEquals(setOf("com.a"), s.packages)
    }

    @Test
    fun `推出来的条目要注明依据是哪一半`() {
        val s = AppRecommender.suggest(
            selectable = installed,
            adEvidence = mapOf("com.d" to 8),
            usageRanked = usage("com.a" to 120),
        )
        // 确认框里靠这个决定显示「用了多少分钟」还是「几次广告判定」
        assertTrue("com.a" in s.usageBased)
        assertTrue("com.d" !in s.usageBased)
    }

    @Test
    fun `用量推荐有条数上限，不会变成全选`() {
        val many = (1..80).map { "com.p$it" }
        val s = AppRecommender.suggest(
            selectable = many,
            adEvidence = emptyMap(),
            usageRanked = usage(*many.map { it to 60L }.toTypedArray()),
        )
        assertEquals(AppRecommender.USAGE_MAX_SUGGESTIONS, s.packages.size)
    }

    @Test
    fun `两条依据都空时必须说推不出来`() {
        val s = AppRecommender.suggest(installed, emptyMap(), emptyList())
        assertEquals(AppRecommender.Basis.NOTHING, s.basis)
        assertTrue(s.packages.isEmpty())
    }

    @Test
    fun `不推荐没装的应用`() {
        // 日志里可能有别的机器/已卸载应用的记录，不能照着推
        val s = AppRecommender.suggest(
            selectable = installed,
            adEvidence = mapOf("com.a" to 10, "com.卸载了的" to 99),
            usageRanked = usage("com.也没装的" to 999),
        )
        assertEquals(setOf("com.a"), s.packages)
    }
}
