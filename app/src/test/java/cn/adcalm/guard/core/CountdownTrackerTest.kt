package cn.adcalm.guard.core

import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountdownTrackerTest {

    private val tracker = CountdownTracker(windowMs = 1_500L)

    @Test
    fun `数值递减被识别为倒计时`() {
        assertFalse("第一次观测没有参照物", tracker.observe("k", "3", 1_000L))
        assertTrue("3 -> 2 应判定为递减", tracker.observe("k", "2", 2_000L))
        assertTrue("2 -> 1 应判定为递减", tracker.observe("k", "1", 3_000L))
    }

    @Test
    fun `数值递增不算倒计时`() {
        tracker.observe("k", "1", 1_000L)
        assertFalse(tracker.observe("k", "2", 2_000L))
    }

    @Test
    fun `超出时间窗口的样本不作数`() {
        tracker.observe("k", "9", 1_000L)
        // 距上次观测 3 秒，已经不是一个连续的倒计时序列
        assertFalse(tracker.observe("k", "2", 4_000L))
    }

    @Test
    fun `带 s 或 秒 后缀同样识别`() {
        tracker.observe("k", "5s", 1_000L)
        assertTrue(tracker.observe("k", "4s", 2_000L))

        tracker.observe("j", "5秒", 1_000L)
        assertTrue(tracker.observe("j", "4秒", 2_000L))
    }

    @Test
    fun `不同节点的样本互不干扰`() {
        // a 观测到 5，紧接着 b 观测到 2。
        // 若实现共用一份全局样本，这里会把 5 -> 2 误判成倒计时递减。
        tracker.observe("a", "5", 1_000L)
        assertFalse("不同签名之间不应互相参照", tracker.observe("b", "2", 1_100L))
    }

    @Test
    fun `非数字文案直接忽略`() {
        assertFalse(tracker.observe("k", "跳过", 1_000L))
        assertFalse(tracker.observe("k", "1999", 2_000L))
        assertFalse(tracker.observe("k", null, 3_000L))
    }

    @Test
    fun `共用 viewId 的列表项不能算作同一个节点`() {
        // 真机日志：RecyclerView 的列表项共用 viewId，一棵树里
        // `rank_item_game_index` 出现 4 次、值是 1/2/3/4。
        // 若签名只取 viewId，扫描顺序一变（4 在 1 前面）就会读成"数值递减"，
        // 把榜单序号误判成倒计时跳过按钮。
        val rank1 = NodeSnapshot(
            viewId = "com.example:id/rank_index",
            className = "android.widget.TextView",
            text = "1",
            bounds = RectSnapshot(42, 100, 117, 151),
        )
        val rank4 = rank1.copy(text = "4", bounds = RectSnapshot(42, 400, 117, 451))
        assertNotEquals(
            "位置不同就是不同的节点",
            tracker.signatureOf(rank1),
            tracker.signatureOf(rank4),
        )
    }

    @Test
    fun `同一个位置上的数字递减仍然识别为倒计时`() {
        // 回归保护：加上位置之后，真正的倒计时（节点不动、数字在变）不能失效。
        val skip = NodeSnapshot(
            viewId = "com.example:id/skip",
            className = "android.widget.TextView",
            bounds = RectSnapshot(900, 100, 1000, 160),
        )
        assertFalse(tracker.observe(tracker.signatureOf(skip), "3", 1_000L))
        assertTrue(
            "同 id 同位置，3 -> 2 应当判为递减",
            tracker.observe(tracker.signatureOf(skip.copy(text = "2")), "2", 2_000L),
        )
    }
}
