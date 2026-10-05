package cn.adcalm.guard.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广告落地面与安装器的识别。
 *
 * 这一小块决定的是「广告把用户弹过去之后，回退能不能动手」。
 * 2026-10-04 的现场就栽在这里：`com.android.packageinstaller` 因为被广告
 * 反复拉起而累积了 9 分 32 秒的前台时长，被行为保护名单收成了"用户自用"，
 * 于是广告把它弹出来时回退反而被挡住了。
 */
class AppClassifierTest {

    @Test
    fun `各家 ROM 的安装器都能认出来`() {
        assertTrue(AppClassifier.isInstaller("com.android.packageinstaller"))
        assertTrue(AppClassifier.isInstaller("com.google.android.packageinstaller"))
        assertTrue(AppClassifier.isInstaller("com.miui.packageinstaller"))
        assertTrue(AppClassifier.isInstaller("com.coloros.packageinstaller"))
    }

    @Test
    fun `名单之外的 ROM 变体靠包名关键词兜住`() {
        // 各家 ROM 的安装器包名不统一，而且会变。名单兜不全，
        // 所以再加一条关键词匹配——这条比名单更重要。
        assertTrue(AppClassifier.isInstaller("com.somevendor.packageinstaller"))
        assertTrue(AppClassifier.isInstaller("com.another.PackageInstaller"))
    }

    @Test
    fun `普通应用不会被误认成安装器`() {
        for (pkg in listOf("com.tencent.mm", "com.taobao.taobao", "com.android.settings")) {
            assertFalse(pkg, AppClassifier.isInstaller(pkg))
        }
    }

    @Test
    fun `浏览器、应用市场、安装器都算广告落地面`() {
        // 这三类是"广告把你弹过去之后落脚的地方"，不享受行为保护——
        // 你对它们的使用很可能本身就是广告造成的。
        assertTrue(AppClassifier.isAdLandingSurface("com.tencent.mtt"))
        assertTrue(AppClassifier.isAdLandingSurface("com.android.vending"))
        assertTrue(AppClassifier.isAdLandingSurface("com.android.packageinstaller"))
    }

    @Test
    fun `普通应用不是广告落地面`() {
        // 这条是"不误伤用户软件"的底线：某通讯应用、某支付应用这类永远不会因为
        // 能回退就被当成广告落地面处理。
        for (pkg in listOf("com.tencent.mm", "com.eg.android.AlipayGphone", "com.taobao.taobao")) {
            assertFalse(pkg, AppClassifier.isAdLandingSurface(pkg))
        }
    }
}
