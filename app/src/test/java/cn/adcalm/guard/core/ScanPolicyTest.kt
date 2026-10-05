package cn.adcalm.guard.core

import cn.adcalm.guard.core.ScanPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描节奏的边界测试。
 *
 * 这套规则直接决定待机功耗：放松了坐在应用里会持续吃 CPU，
 * 收紧了会漏掉开屏广告。所以每一种情形都要有用例钉住。
 */
class ScanPolicyTest {

    private val splashWindow = ScanPolicy.SPLASH_WINDOW_MS

    // ---- 窗口切换永远全速 ----

    @Test
    fun `窗口切换无视节流，永远全速`() {
        // 即使刚刚才扫过、即使已经进应用很久了
        assertEquals(
            Mode.FULL,
            ScanPolicy.decide(isWindowChange = true, msSinceForeground = 99_999, msSinceLastScan = 0),
        )
    }

    @Test
    fun `窗口切换时也允许 OCR`() {
        assertTrue(ScanPolicy.allowsOcr(isWindowChange = true, msSinceForeground = 99_999))
    }

    // ---- 开屏窗口期：全速 ----

    @Test
    fun `刚进入应用时全速扫描`() {
        assertEquals(
            Mode.FULL,
            ScanPolicy.decide(isWindowChange = false, msSinceForeground = 500, msSinceLastScan = 500),
        )
    }

    @Test
    fun `开屏窗口期内仍受 100ms 节流`() {
        assertEquals(
            Mode.SKIP,
            ScanPolicy.decide(
                isWindowChange = false,
                msSinceForeground = 1_000,
                msSinceLastScan = ScanPolicy.FULL_THROTTLE_MS - 1,
            ),
        )
    }

    @Test
    fun `开屏窗口期的边界`() {
        assertEquals(
            Mode.FULL,
            ScanPolicy.decide(
                isWindowChange = false,
                msSinceForeground = splashWindow - 1,
                msSinceLastScan = ScanPolicy.FULL_THROTTLE_MS,
            ),
        )
    }

    @Test
    fun `开屏窗口期内允许 OCR`() {
        assertTrue(ScanPolicy.allowsOcr(isWindowChange = false, msSinceForeground = 1_000))
    }

    // ---- 出了开屏窗口：降频 ----

    @Test
    fun `过了开屏窗口期后降频到一秒一次`() {
        assertEquals(
            Mode.SKIP,
            ScanPolicy.decide(
                isWindowChange = false,
                msSinceForeground = splashWindow + 1,
                msSinceLastScan = ScanPolicy.IDLE_THROTTLE_MS - 1,
            ),
        )
        assertEquals(
            Mode.RELAXED,
            ScanPolicy.decide(
                isWindowChange = false,
                msSinceForeground = splashWindow + 1,
                msSinceLastScan = ScanPolicy.IDLE_THROTTLE_MS,
            ),
        )
    }

    @Test
    fun `降频后不再触发 OCR`() {
        assertFalse(
            "坐在应用里每分钟截几十次图是不可接受的",
            ScanPolicy.allowsOcr(isWindowChange = false, msSinceForeground = splashWindow + 1),
        )
    }

    // ---- 功耗对比 ----

    @Test
    fun `稳态下的扫描频率比原先低一个数量级`() {
        // 原先只有 100ms 节流，坐着不动也是每秒 10 次。
        // 这里直接比"间隔"而不是比"每秒几次"：后者是整数除法，
        // 间隔一旦超过 1 秒就会除出 0，测试自己先崩了（2026-10-05 改常量时踩到）。
        assertTrue(
            "稳态节流至少要比全速慢一个数量级（全速 ${ScanPolicy.FULL_THROTTLE_MS}ms、" +
                "稳态 ${ScanPolicy.IDLE_THROTTLE_MS}ms）",
            ScanPolicy.IDLE_THROTTLE_MS >= ScanPolicy.FULL_THROTTLE_MS * 10,
        )
    }
}
