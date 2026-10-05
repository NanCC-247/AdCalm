package cn.adcalm.guard.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「最近一次扫描」那行文案的真值表。
 *
 * 它存在的理由是**把两件长得一样、后果完全不同的事分开**：
 * "扫了但今天没认出广告" 和 "根本一拍都没扫"。所以断言的重点是
 * **四档都要说得准**——尤其是"还没有过"这一档，它对应的是真出过的那次静默失效。
 */
class ScanFreshnessTest {

    private fun describe(ms: Long?, up: Boolean = true) = ScanFreshness.describe(ms, up)

    // ---- 服务不在：只说明这一件事，不去猜"上次扫描是多久以前" ----

    @Test
    fun `后台未挂起时说未挂起，不说上次扫描过了多久`() {
        assertEquals("最近一次扫描：后台未挂起", describe(ms = 3_000, up = false))
        assertEquals("最近一次扫描：后台未挂起", describe(ms = null, up = false))
    }

    // ---- 从没扫过：这一档最要紧 ----

    @Test
    fun `一次都没扫过时说还没有过`() {
        // 服务挂着、却一拍都没扫——2026-10-05 真出现过（前台包名缓存为空，节拍器永远不扫）。
        // 这一行就是为它存在的：那时首页其余部分显示得和正常一模一样。
        assertEquals("最近一次扫描：还没有过", describe(null))
    }

    // ---- 有扫描记录：按粗细分档 ----

    @Test
    fun `几秒内说刚刚`() {
        assertEquals("最近一次扫描：刚刚", describe(0))
        assertEquals("最近一次扫描：刚刚", describe(4_999))
    }

    @Test
    fun `一分钟内按秒说`() {
        assertEquals("最近一次扫描：5 秒前", describe(5_000))
        assertEquals("最近一次扫描：59 秒前", describe(59_999))
    }

    @Test
    fun `一小时内按分钟说`() {
        assertEquals("最近一次扫描：1 分钟前", describe(60_000))
        assertEquals("最近一次扫描：58 分钟前", describe(3_500_000))
    }

    @Test
    fun `超过一小时按小时说`() {
        assertEquals("最近一次扫描：1 小时前", describe(3_600_000))
        assertEquals("最近一次扫描：5 小时前", describe(5 * 3_600_000L))
    }

    @Test
    fun `四档在边界上不重叠、也不漏`() {
        // 防回归：分档的边界值最容易写岔（>= 写成 > 之类），而这一行的全部价值
        // 就在于"说得准"。四个档各取一个边界点验一遍。
        assertEquals("最近一次扫描：刚刚", describe(4_999))
        assertEquals("最近一次扫描：5 秒前", describe(5_000))
        assertEquals("最近一次扫描：59 秒前", describe(59_999))
        assertEquals("最近一次扫描：1 分钟前", describe(60_000))
        assertEquals("最近一次扫描：59 分钟前", describe(3_599_999))
        assertEquals("最近一次扫描：1 小时前", describe(3_600_000))
    }
}
