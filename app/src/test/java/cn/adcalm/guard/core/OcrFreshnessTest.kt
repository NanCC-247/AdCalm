package cn.adcalm.guard.core

import cn.adcalm.guard.core.OcrFreshness.Identity
import cn.adcalm.guard.model.RectSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR 结果新鲜度的真值表。
 *
 * 要防的失败只有一个：**截图之后用户翻页了，我们却照着旧坐标点下去**。
 * 所以断言分两类——界面没变的一律放行（不能误伤，这个项目最怕的是"什么都不点"），
 * 拿得到明确证据说界面变了的一律拦下。
 */
class OcrFreshnessTest {

    private val screen = RectSnapshot(0, 0, 1080, 2400)

    private fun shot(
        pkg: String = "com.example.browser",
        activity: String? = "com.example.browser.SplashActivity",
        screen: RectSnapshot = this.screen,
    ) = Identity(pkg, activity, screen)

    // ---- 界面没变：放行 ----

    @Test
    fun `同一个界面放行`() {
        assertTrue(OcrFreshness.isSameScreen(shot(), shot()))
    }

    @Test
    fun `两边都读不到 Activity 时，只要包名和屏幕对得上就放行`() {
        // `currentActivity` 由窗口事件带上来，空档里可能是 null。
        // 不该因为"这次没读到"就拦下一次正确的点击。
        assertTrue(
            OcrFreshness.isSameScreen(
                shot(activity = null),
                shot(activity = null),
            )
        )
    }

    @Test
    fun `只有一边读到 Activity 时不作判断，包名和屏幕说了算`() {
        // 一边 null 一边有值，不足以当"跳转了"的证据——太脆，会误伤。
        assertTrue(OcrFreshness.isSameScreen(shot(activity = null), shot()))
        assertTrue(OcrFreshness.isSameScreen(shot(), shot(activity = null)))
    }

    @Test
    fun `读不到前台是谁时不拦——那是「不知道」，不是「变了」`() {
        // 窗口切换的空档 `rootInActiveWindow` 会返回 null。
        // 判成变了的话，一次读取失败就丢掉一次正确的点击。
        assertTrue(
            OcrFreshness.isSameScreen(
                shot(),
                Identity(pkg = "", activity = null, screen = screen),
            )
        )
    }

    // ---- 界面变了：拦下 ----

    @Test
    fun `换了应用就拦下`() {
        // 广告把人拽到别的应用：截图时还在浏览器里，点之前已经到了应用市场。
        assertFalse(
            OcrFreshness.isSameScreen(
                shot(pkg = "com.example.browser"),
                shot(pkg = "com.example.market"),
            )
        )
    }

    @Test
    fun `同一个应用里换了 Activity 也要拦下`() {
        // **这条是重点。** 广告把人送到落地页、送到商店页，大多发生在**同一个包里面**，
        // 只看包名根本认不出来。真机日志里那次误跳就是同一个浏览器换了 Activity。
        assertFalse(
            OcrFreshness.isSameScreen(
                shot(activity = "com.example.browser.SplashActivity"),
                shot(activity = "com.example.browser.LandingPageActivity"),
            )
        )
    }

    @Test
    fun `转屏或改尺寸就拦下`() {
        // 坐标是按旧几何算的，屏幕一变就对不上了（竖屏 1080x2400 → 横屏 2400x1080）。
        assertFalse(
            OcrFreshness.isSameScreen(
                shot(screen = RectSnapshot(0, 0, 1080, 2400)),
                shot(screen = RectSnapshot(0, 0, 2400, 1080)),
            )
        )
    }

    @Test
    fun `换 Activity 和换屏幕同时发生时也拦下`() {
        assertFalse(
            OcrFreshness.isSameScreen(
                shot(activity = "com.example.browser.SplashActivity"),
                Identity("com.example.browser", "com.example.browser.AdLanding", RectSnapshot(0, 0, 2400, 1080)),
            )
        )
    }

    @Test
    fun `包名对得上但屏幕变了，仍然拦下`() {
        // 防止"先比包名、包名相同就直接放行"这种写反了的实现。
        assertFalse(
            OcrFreshness.isSameScreen(
                shot(),
                shot(screen = RectSnapshot(0, 0, 720, 1600)),
            )
        )
    }
}
