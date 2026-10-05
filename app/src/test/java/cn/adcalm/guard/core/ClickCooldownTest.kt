package cn.adcalm.guard.core

import cn.adcalm.guard.model.RectSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点击冷却的边界测试。
 *
 * 这套逻辑错了的代价是"白白多等几秒"——真机上就发生过一次：
 * 点错了一个空白节点之后，真正的倒计时按钮被冷却挡了两次，多等 3 秒。
 */
class ClickCooldownTest {

    private val cooldown = ClickCooldown(sameElementCooldownMs = 3_000L, minIntervalMs = 400L)

    private val app = "com.example.app"
    private val elementA = RectSnapshot(900, 100, 1000, 200)
    private val elementB = RectSnapshot(100, 2000, 200, 2100)

    @Test
    fun `从未点过时允许点击`() {
        assertTrue(cooldown.allows(app, 10_000L, elementA))
    }

    @Test
    fun `同一元素在冷却期内被挡住`() {
        cooldown.record(app, 10_000L, elementA)
        assertFalse("防连点，同一位置应继续挡", cooldown.allows(app, 10_500L, elementA))
    }

    @Test
    fun `冷却期内换一个元素应放行`() {
        // 这是真机日志里那 3 秒延迟的修复点：
        // 上一次点错了地方，发现了正确的按钮就该立刻能点，不该被冷却连累。
        cooldown.record(app, 10_000L, elementA)
        assertTrue(
            "换了位置说明上次没生效，应允许再点",
            cooldown.allows(app, 10_500L, elementB),
        )
    }

    @Test
    fun `同一元素超过冷却时间后放行`() {
        cooldown.record(app, 10_000L, elementA)
        assertTrue(cooldown.allows(app, 13_000L, elementA))
    }

    @Test
    fun `全局硬间隔对任何元素都生效`() {
        cooldown.record(app, 10_000L, elementA)
        assertFalse(
            "间隔不足 400ms 时，即使是不同元素也不点",
            cooldown.allows(app, 10_300L, elementB),
        )
    }

    @Test
    fun `换应用时不受同元素冷却影响`() {
        cooldown.record(app, 10_000L, elementA)
        assertTrue(cooldown.allows("com.other.app", 10_500L, elementA))
    }

    @Test
    fun `部分重叠也算同一元素`() {
        cooldown.record(app, 10_000L, elementA)
        // 与 elementA 有交叠
        assertFalse(cooldown.allows(app, 10_500L, RectSnapshot(950, 150, 1050, 250)))
    }

    @Test
    fun `边缘相接不算重叠`() {
        cooldown.record(app, 10_000L, elementA)
        // right=1000 与 left=1000 相接但不重叠
        assertTrue(cooldown.allows(app, 10_500L, RectSnapshot(1000, 100, 1100, 200)))
    }

    @Test
    fun `重置后不再受冷却影响`() {
        cooldown.record(app, 10_000L, elementA)
        cooldown.reset()
        assertTrue(cooldown.allows(app, 10_100L, elementA))
    }

    @Test
    fun `连续点不同元素不会退化成无限连点`() {
        // 硬间隔保证：即使一直换元素，两次点击之间也至少隔 400ms
        cooldown.record(app, 10_000L, elementA)
        assertFalse(cooldown.allows(app, 10_200L, elementB))
        assertTrue(cooldown.allows(app, 10_400L, elementB))
    }

    // ---- 「点了会跳走」的位置：长期不再点 ----
    //
    // 2026-10-05 用广告样机复现出来的：一个**假关闭**——点下去不是关闭，而是 4 秒后跳走。
    // 回退把人送回来之后我们又点了一遍，于是"点 → 被带走 → 回退 → 再点"成了循环。

    @Test
    fun `标记为会跳转的位置在十分钟内不再点`() {
        cooldown.record(app, 10_000L, elementA)
        cooldown.markLastClickAsJumping(10_100L)
        // 3 秒的常规冷却早过了，但"会跳走"这条还压着
        assertFalse("明知会跳走还点，就是把人往火坑里推", cooldown.allows(app, 20_000L, elementA))
        assertTrue(
            "十分钟之后应当放行——应用改版之后那个位置可能真的变成关闭按钮了",
            cooldown.allows(app, 10_100L + ClickCooldown.JUMPED_COOLDOWN_MS + 1, elementA),
        )
    }

    @Test
    fun `标记只压同一位置，不牵连别处`() {
        cooldown.record(app, 10_000L, elementA)
        cooldown.markLastClickAsJumping(10_100L)
        assertTrue("别的位置该照常能点", cooldown.allows(app, 20_000L, elementB))
    }

    @Test
    fun `没标记过时不受影响`() {
        cooldown.record(app, 10_000L, elementA)
        assertTrue(cooldown.allows(app, 14_000L, elementA))
    }

    @Test
    fun `重叠但大小不同的按钮不受标记牵连`() {
        // 2026-10-05 用样机踩到：一页上诱饵（140dp）和陷阱（120dp）本来就互相重叠，
        // 标记若按"相交"判定，会把诱饵也一起压住，整页都点不动。
        // 口径是"同一颗按钮才是同一颗按钮"，不是"碰得到就算同一颗"。
        cooldown.record(app, 10_000L, elementA)
        cooldown.markLastClickAsJumping(10_100L)
        val wider = RectSnapshot(elementA.left - 60, elementA.top, elementA.right, elementA.bottom)
        assertTrue("宽度差了 60px，是另一颗按钮", cooldown.allows(app, 20_000L, wider))
    }
}
