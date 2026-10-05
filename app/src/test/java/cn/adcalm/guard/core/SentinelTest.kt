package cn.adcalm.guard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 哨兵判定的边界测试。
 *
 * 这段逻辑决定"要不要去强停另一个应用"，是整个项目里后果最重的判断，
 * 所以每条放行条件都要有独立用例钉住。
 */
class SentinelTest {

    private val protectedPackages = setOf("com.example.chat", "com.example.pay")

    /** 记录最近一次判定收到的"外部拉起"标志，用来验证它确实被透传下去了。 */
    private var lastExternallyLaunched: Boolean? = null

    private val sentinel = Sentinel { pkg, externallyLaunched ->
        lastExternallyLaunched = externallyLaunched
        pkg !in protectedPackages
    }

    private val clickAt = 10_000L

    @Test
    fun `点击后跳到非保护包应回退`() {
        sentinel.arm("com.example.app", clickAt)
        assertTrue(sentinel.shouldRollback("com.android.chrome", clickAt + 500))
    }

    @Test
    fun `仍在原应用内不回退`() {
        sentinel.arm("com.example.app", clickAt)
        assertFalse(
            "点完之后还在同一个应用里，属于正常情况",
            sentinel.shouldRollback("com.example.app", clickAt + 500),
        )
    }

    // ---- 原应用（回退后要把用户送回哪里） ----

    @Test
    fun `武装时记下原应用`() {
        // 回退流程靠它把用户送回被拽走之前那个应用，而不是扔在桌面上
        sentinel.arm("com.example.app", clickAt)
        assertEquals("com.example.app", sentinel.originPackage())
    }

    @Test
    fun `没武装时没有原应用`() {
        assertNull(sentinel.originPackage())
    }

    @Test
    fun `disarm 之后取不到原应用`() {
        // 回退流程一进来就会 disarm，所以必须在那之前把包名取走。
        // 这条钉住这个顺序约束——写反了返回原应用会静默失效。
        sentinel.arm("com.example.app", clickAt)
        sentinel.disarm()
        assertNull(sentinel.originPackage())
    }

    @Test
    fun `窗口过期后原应用仍然可读`() {
        // isArmed 会随时间失效，但 originPackage 不会——
        // 回退是在窗口内触发的，取包名时窗口可能刚好已经过了。
        sentinel.arm("com.example.app", clickAt)
        assertEquals("com.example.app", sentinel.originPackage())
        assertFalse(sentinel.isArmed(clickAt + Sentinel.WINDOW_MS + 1))
        assertEquals("com.example.app", sentinel.originPackage())
    }

    // ---- 「还在原应用里」 ----

    @Test
    fun `同一个包算还在原应用里`() {
        sentinel.arm("com.example.browser", clickAt)
        assertTrue(sentinel.isStillInOrigin("com.example.browser"))
    }

    @Test
    fun `换了包就不算`() {
        sentinel.arm("com.example.browser", clickAt)
        assertFalse(sentinel.isStillInOrigin("com.android.packageinstaller"))
    }

    @Test
    fun `没武装时不算"还在原应用里"`() {
        // armedPackage 是 null，任何包名都不该被判成"就是原应用"——
        // 那会让调用方以为可以继续等，其实哨兵根本不在。
        assertFalse(sentinel.isStillInOrigin("com.example.browser"))
    }

    @Test
    fun `包内窗口切换触发回退判定时会被这一条挡住`() {
        // 这是 2026-10-04 那次漏判的完整形状：
        // 广告 SDK 在宿主应用内部弹出自己的 Activity，包名没变，
        // 于是 shouldRollback 返回 false。旧代码据此 disarm，哨兵就没了。
        // 现在调用方要先用 isStillInOrigin 区分开，不能 disarm。
        sentinel.arm("com.example.browser", clickAt)
        val pkg = "com.example.browser"   // 事件包名仍是宿主
        assertTrue(sentinel.isStillInOrigin(pkg))
        assertFalse(
            "同包不该回退",
            sentinel.shouldRollback(pkg, clickAt + 1_000),
        )
        assertTrue(
            "但也不能因此把哨兵解除——广告还在动",
            sentinel.isArmed(clickAt + 1_000),
        )
    }

    // ---- 「被外部拉起」的透传 ----

    @Test
    fun `外部拉起标志会原样传给准入判断`() {
        // 这一位是"广告把你塞进某通讯应用的小程序"唯一的翻盘点：某通讯应用在你自己的保护名单里，
        // 但那一刻它是被广告从外面拉起来的，不是你点开的。
        // 传丢了就等于这条路径又断了，所以单独钉住。
        sentinel.arm("com.example.maps", clickAt)

        sentinel.shouldRollback("com.example.chat", clickAt + 500, externallyLaunched = true)
        assertEquals(true, lastExternallyLaunched)

        sentinel.shouldRollback("com.example.chat", clickAt + 500, externallyLaunched = false)
        assertEquals(false, lastExternallyLaunched)
    }

    @Test
    fun `不传时默认按"不是外部拉起"处理`() {
        // 默认值必须是保守的那一边：漏传一个参数不该悄悄放宽保护名单。
        sentinel.arm("com.example.maps", clickAt)
        sentinel.shouldRollback("com.example.chat", clickAt + 500)
        assertEquals(false, lastExternallyLaunched)
    }

    @Test
    fun `没武装时连准入判断都不该走到`() {
        // 哨兵没武装就不该去问准入，否则会平白调用一次可能很贵的判断
        // （生产环境那条谓词要查保护名单）
        sentinel.shouldRollback("com.example.chat", clickAt + 500, externallyLaunched = true)
        assertNull(lastExternallyLaunched)
    }

    // ---- 观察模式的窗口 ----

    @Test
    fun `不传窗口时用默认的 3 秒`() {
        sentinel.arm("com.example.app", clickAt)
        assertTrue(sentinel.isArmed(clickAt + Sentinel.WINDOW_MS))
        assertFalse(sentinel.isArmed(clickAt + Sentinel.WINDOW_MS + 1))
    }

    @Test
    fun `观察模式的窗口能活过 3 秒，但仍在 30 秒内失效`() {
        // 观察模式下我们**不点**，是用户自己看到开屏之后去点它——
        // 从"检测到广告"到"人真的点下去"隔着反应时间，3 秒不够。
        // 这条同时钉住另一头：放宽不等于无限，30 秒之后必须失效。
        sentinel.arm("com.example.app", clickAt, Sentinel.OBSERVE_WINDOW_MS)
        assertTrue(
            "3 秒时就不该失效了，否则用户还没反应过来哨兵就没了",
            sentinel.isArmed(clickAt + Sentinel.WINDOW_MS + 1),
        )
        assertTrue(sentinel.isArmed(clickAt + Sentinel.OBSERVE_WINDOW_MS))
        assertFalse(sentinel.isArmed(clickAt + Sentinel.OBSERVE_WINDOW_MS + 1))
    }

    @Test
    fun `disarm 之后窗口复位成默认值`() {
        // 否则下一次用默认值 arm 的调用会莫名其妙地继承上一次的宽窗口——
        // 那等于在正常模式下也放宽了误判面。
        sentinel.arm("com.example.app", clickAt, Sentinel.OBSERVE_WINDOW_MS)
        sentinel.disarm()
        sentinel.arm("com.example.app", clickAt)
        assertFalse(sentinel.isArmed(clickAt + Sentinel.WINDOW_MS + 1))
    }

    @Test
    fun `未激活时不回退`() {
        assertFalse(sentinel.shouldRollback("com.android.chrome", clickAt))
    }

    @Test
    fun `超出观察窗口后不再回退`() {
        sentinel.arm("com.example.app", clickAt)
        val tooLate = clickAt + Sentinel.WINDOW_MS + 1
        assertFalse(
            "3 秒之后出现的窗口变化与本次点击无关，不能算在广告头上",
            sentinel.shouldRollback("com.android.chrome", tooLate),
        )
    }

    @Test
    fun `窗口边界内仍然回退`() {
        sentinel.arm("com.example.app", clickAt)
        assertTrue(sentinel.shouldRollback("com.android.chrome", clickAt + Sentinel.WINDOW_MS))
    }

    @Test
    fun `跳到自己保护的软件不回退`() {
        sentinel.arm("com.example.app", clickAt)
        assertFalse(
            "用户自己的某通讯应用绝不能被强停，即使它是在点击后出现的",
            sentinel.shouldRollback("com.example.chat", clickAt + 500),
        )
    }

    @Test
    fun `disarm 之后不再回退`() {
        sentinel.arm("com.example.app", clickAt)
        sentinel.disarm()
        assertFalse(sentinel.shouldRollback("com.android.chrome", clickAt + 100))
    }

    @Test
    fun `重复 arm 会重置计时窗口`() {
        sentinel.arm("com.example.app", clickAt)
        sentinel.arm("com.example.app", clickAt + 2_000)
        // 距最后一次点击只过了 1.5 秒，仍在窗口内
        assertTrue(sentinel.shouldRollback("com.android.chrome", clickAt + 3_500))
    }

    // ---- 迟到窗口：把跳转延后就能躲开 3 秒窗口，这条兜底是为此加的 ----
    //
    // 2026-10-05 广告样机实测：点完「关闭」4 秒后才跳，哨兵早已失效，
    // 用户被留在广告落地页里。兜底只认"刚装上的应用"（广告自己装的马甲包）。

    @Test
    fun `窗口过期后目标是刚装的应用时仍然回退`() {
        sentinel.arm("com.example.host", clickAt)
        assertFalse("正常窗口已经不认了", sentinel.shouldRollback("com.example.newapp", clickAt + 4_000))
        assertTrue(sentinel.wasArmedRecently(clickAt + 4_000))
        assertTrue(
            "迟到的、又是刚装上的应用 → 兜底要认",
            sentinel.shouldRollbackLate("com.example.newapp", clickAt + 4_000, isFreshlyInstalled = true),
        )
    }

    @Test
    fun `窗口过期后目标不是刚装的应用就不认`() {
        sentinel.arm("com.example.host", clickAt)
        assertFalse(
            "老应用不能扫进来——用户自己切走也是这个形状",
            sentinel.shouldRollbackLate("com.example.old", clickAt + 4_000, isFreshlyInstalled = false),
        )
    }

    @Test
    fun `迟到兜底也有上限`() {
        sentinel.arm("com.example.host", clickAt)
        val tooLate = clickAt + Sentinel.LATE_WINDOW_MS + 1
        assertFalse("过了 15 秒就不再算同一次点击的后果", sentinel.wasArmedRecently(tooLate))
        assertFalse(sentinel.shouldRollbackLate("com.example.newapp", tooLate, true))
    }

    @Test
    fun `还在正常窗口里时不走迟到那条`() {
        sentinel.arm("com.example.host", clickAt)
        assertFalse(
            "窗口内该由 shouldRollback 判定，两条不能重叠",
            sentinel.shouldRollbackLate("com.example.newapp", clickAt + 1_000, isFreshlyInstalled = true),
        )
    }

    @Test
    fun `迟到兜底仍然尊重保护名单`() {
        sentinel.arm("com.example.host", clickAt)
        assertFalse(
            "保护名单里的包，迟到那条也不动",
            sentinel.shouldRollbackLate("com.example.chat", clickAt + 4_000, true),
        )
    }

    @Test
    fun `回到原应用不算迟到跳转`() {
        sentinel.arm("com.example.host", clickAt)
        assertFalse(sentinel.shouldRollbackLate("com.example.host", clickAt + 4_000, true))
    }

    @Test
    fun `没武装过就没有迟到窗口`() {
        assertFalse(sentinel.wasArmedRecently(clickAt))
        assertFalse(sentinel.shouldRollbackLate("com.example.newapp", clickAt, true))
    }
}
