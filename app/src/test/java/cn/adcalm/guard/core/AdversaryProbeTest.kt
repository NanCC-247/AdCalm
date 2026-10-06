package cn.adcalm.guard.core

import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import cn.adcalm.guard.model.Verdict
import org.junit.Assert.*
import org.junit.Test

/** 回归误操作案例；公开测试版只接受明确的现有广告关闭控件。 */
class AdversaryProbeTest {
    private val screen = RectSnapshot(0, 0, 1080, 2400)
    private val topRight = RectSnapshot(880, 120, 1040, 200)
    private fun node(text: String? = null, desc: String? = null, viewId: String? = null) =
        NodeSnapshot(text = text, contentDescription = desc, viewId = viewId, clickable = true, bounds = topRight)

    @Test fun `ID诱饵不能替代用户可见的关闭文案`() {
        val decoy = ConfidenceScorer.score(node(viewId = "com.example:id/fl_skip_wrong"), screen, ruleScore = 999)
        assertNotEquals(Verdict.CLICK, decoy.verdict)
        assertFalse(decoy.hasStrongEvidence)
    }

    @Test fun `描述不能让帮助或安装文案成为关闭操作`() {
        for (label in listOf("帮助", "安装", "继续", "会员")) {
            assertNotEquals(label, Verdict.CLICK, ConfidenceScorer.score(node(text = label, desc = "关闭广告"), screen).verdict)
        }
    }

    @Test fun `横幅角落和广告标记仍不能证明普通X是关闭控件`() {
        val geometry = node(text = "×").copy(parentBounds = screen, siblingTexts = listOf("广告"))
        assertNotEquals(Verdict.CLICK, ConfidenceScorer.score(geometry, screen).verdict)
        assertFalse(ConfidenceScorer.score(geometry, screen).hasStrongEvidence)
    }

    @Test fun `只递减的倒计时不能触发点击`() {
        val timer = ConfidenceScorer.score(node(text = "3"), screen, hasCountdown = true)
        assertNotEquals(Verdict.CLICK, timer.verdict)
        assertFalse(timer.hasStrongEvidence)
    }

    @Test fun `普通聊天文档里的关闭文字不产生OCR坐标`() {
        assertNull(OcrCloseScorer.evaluate(listOf(OcrBlock("关闭", topRight)), screen))
        assertNull(OcrCloseScorer.evaluate(listOf(OcrBlock("广告", topRight), OcrBlock("关闭", topRight)), screen))
    }

    @Test fun `完整广告关闭文案保留OCR支持`() {
        val candidate = OcrCloseScorer.evaluate(listOf(OcrBlock("(跳过广告", RectSnapshot(69, 2130, 200, 2163))), screen)
        assertNotNull(candidate)
    }

    // ---- 攻击六：安装包改名 / 换目录 ----
    //
    // 清理页靠"扩展名 + 位置"筛。厂商能绕的地方也在这两条上——
    // 这两条用例**故意断言现状**，好让缺口固定下来而不是被忘掉。

    @Test
    fun `攻击_安装包改扩展名_当前认不出`() {
        // 已知缺口：包名改成 .dat，靠扩展名判定的那一层就看不见它。
        assertFalse(AdPackageCleaner.looksLikePackage("/sdcard/Download/update.dat"))
        assertTrue(AdPackageCleaner.looksLikePackage("/sdcard/Download/update.apk"))
    }

    @Test
    fun `攻击_安装包下到非监控目录_位置判定覆盖不到`() {
        // 已知缺口：手动扫描只看 Android/data 与 Android/obb；
        // 标准下载目录交给 FileObserver，而它挂的是 Download / Browser / download 三个。
        // 下到 /sdcard/Documents/ 这类地方，两边都不管。
        val path = "/sdcard/Documents/promo.apk"
        assertTrue(AdPackageCleaner.looksLikePackage(path))
        assertNull("不在 Android/data 下，取不出归属应用", AdPackageCleaner.ownerPackageOf(path))
    }

    // ---- 真机探针：把造出来的文件放进设备，跑 App 一模一样的命令，拿真实输出喂给真实实现 ----
    //
    // 命令取自 ShellCommands.listPackages（`find` 管道 `stat -c '%s %Y %n'`），
    // 2026-10-05 在某国产机型上实跑，下面是**未经修改的真实输出行**。

    private val deviceScanOutput = listOf(
        "1200000 1791195049 /sdcard/Android/data/com.ume.browser/cache/adprobe/apk/ad_probe.apk",
        "400000 1791195049 /sdcard/Android/data/com.ume.browser/cache/adprobe/apk/tiny_probe.apk",
        "1200000 1791195049 /sdcard/Android/data/com.ume.browser/files/elsewhere_probe.apk",
    )

    @Test
    fun `真机探针_缓存目录里的被列出来并默认勾选`() {
        val found = AdPackageCleaner.scan(deviceScanOutput.asSequence())
        // 三条输入里 tiny（400KB）被 1MB 下限滤掉，剩两条
        assertEquals(2, found.size)
        assertEquals(AdPackageCleaner.Kind.CACHE_RESIDUE, found[0].kind)
        assertTrue("缓存残渣排在前面（要被默认勾上的那一类）", found[0].found.path.endsWith("ad_probe.apk"))
        assertEquals(AdPackageCleaner.Kind.ELSEWHERE, found[1].kind)
    }

    @Test
    fun `真机探针_非缓存目录的只列不勾`() {
        val found = AdPackageCleaner.scan(deviceScanOutput.asSequence())
        val elsewhere = found.first { it.kind == AdPackageCleaner.Kind.ELSEWHERE }
        assertTrue(elsewhere.found.path.endsWith("elsewhere_probe.apk"))
        assertEquals(1_200_000L, elsewhere.found.sizeBytes)
        assertEquals(
            "归属应用要能从路径里取出来，界面上才跳得过去",
            "com.ume.browser",
            AdPackageCleaner.ownerPackageOf(elsewhere.found.path),
        )
    }

    @Test
    fun `真机探针_小于一兆的碎片不列`() {
        val found = AdPackageCleaner.scan(deviceScanOutput.asSequence())
        assertFalse(found.any { it.found.path.endsWith("tiny_probe.apk") })
    }

    // ---- 强停流程：确认弹窗的宿主 ----
    //
    // 2026-10-05 广告样机实测：这台 ROM 的「强行停止」确认弹窗挂在 com.android.systemui 下，
    // 而状态机把它当成"用户切走了"→ 刚点完「强行停止」就放弃，强停从来没成功过。

    @Test
    fun `强停_系统确认弹窗的宿主不能被当成用户切走`() {
        assertTrue(AppNeutralizer.looksLikeConfirmDialogHost("com.android.systemui"))
        assertTrue(
            "有的 ROM 挂在权限控制器下",
            AppNeutralizer.looksLikeConfirmDialogHost("com.android.permissioncontroller"),
        )
        assertFalse(
            "普通应用仍然算切走，不能在它上面乱点",
            AppNeutralizer.looksLikeConfirmDialogHost("com.tencent.mm"),
        )
    }
}
