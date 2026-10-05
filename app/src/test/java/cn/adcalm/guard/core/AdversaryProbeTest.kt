package cn.adcalm.guard.core

import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import cn.adcalm.guard.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红队用例：站到**广告厂商**那边，逐条验证识别侧挡不挡得住。
 *
 * 和别的测试不同，这里的每条用例都对应一个"我们假设对手会这么做"的具体做法，
 * 而且尽量用真机见过或公开报道过的形态，不用凭空想象的。判定三种结果：
 *
 * - **挡住**：候选被判为不可点，或点的是真按钮
 * - **挡不住**：候选被点（`CLICK`）或选错了目标 —— 必须修，或者明确记为已知缺口
 * - **不适用**：这一层管不到（比如时序问题），记在文档里而不是这里
 *
 * 真机日志校准过的分值表是这套判定的基础，所以这里的断言也顺便钉住了分值表。
 */
class AdversaryProbeTest {

    private val screen = RectSnapshot(0, 0, 1080, 2400)

    /** 右上角——国内开屏广告跳过键最常见的落点。 */
    private val topRight = RectSnapshot(880, 120, 1040, 200)

    private fun node(
        text: String? = null,
        viewId: String? = null,
        desc: String? = null,
        clickable: Boolean = true,
        bounds: RectSnapshot = topRight,
        parentBounds: RectSnapshot? = null,
        ancestors: List<String> = emptyList(),
        siblingTexts: List<String> = emptyList(),
    ) = NodeSnapshot(
        viewId = viewId,
        text = text,
        contentDescription = desc,
        className = "android.widget.TextView",
        packageName = "com.example",
        clickable = clickable,
        bounds = bounds,
        parentBounds = parentBounds,
        ancestorClassNames = ancestors,
        siblingTexts = siblingTexts,
    )

    // ---- 攻击一：跳过按钮做成不可点击，广告 SDK 自己接管触摸 ----

    @Test
    fun `攻击_跳过不可点击_应被挡住`() {
        // 真机两批日志里 13 个「跳过」节点无一例外都是 clickable=false。
        // 识别侧对精确强文案放开了这条要求，点击侧会退化成坐标手势。
        val c = ConfidenceScorer.score(node(text = "跳过", clickable = false), screen)
        assertEquals(75, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    // ---- 攻击二：关闭文案后面挂倒计时，第一次看到就要能点 ----

    @Test
    fun `攻击_跳过带倒计时_应被挡住`() {
        val c = ConfidenceScorer.score(node(text = "跳过 5", clickable = false), screen)
        assertEquals(75, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    // ---- 攻击一之二：把点击热区做宽，让"中心点"掉出角落边带 ----
    //
    // 这条是**广告样机真跑出来的**：摆一个右边距 24dp、宽 120dp 的「跳过」在右上角，
    // 识别侧只给了 55 分（只有文案分）——因为中心点离右边缘 252px，超过 15% 的边带。
    // 判据改成"矩形贴到边缘"之后就够了。坐标是样机实测的那一组。

    @Test
    fun `攻击_宽点击热区的跳过_应该仍然算在角落`() {
        val wideSkip = RectSnapshot(648, 219, 1008, 387)
        val c = ConfidenceScorer.score(node(text = "跳过", clickable = false, bounds = wideSkip), screen)
        assertEquals(75, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `攻击_整宽的关闭条不算角落`() {
        // 贴边判定放宽之后必须挡住这个：一条横跨整屏的「关闭」工具栏
        val bar = RectSnapshot(0, 60, 1080, 200)
        assertFalse("整宽横条不是角落里的关闭键", ConfidenceScorer.isInCorner(bar, screen))
        assertFalse("也不该吃顶部那条加分", ConfidenceScorer.isAtTopEdge(bar, screen))
    }

    @Test
    fun `攻击_大块的诱饵不算角落`() {
        // 真机日志里抓过的那个诱饵（`fl_skip_wrong`）：360x390，占屏 5.4%。
        // 把角落判定从"中心点"改成"矩形贴边"之后，它一度也变成"在角落"、分数从 45 涨到 65——
        // 护栏测试当场抓到，于是补了面积上限（合规要求关闭键只占屏幕百分之几）。
        val decoy = RectSnapshot(720, 0, 1080, 390)
        assertFalse("大块头不是关闭键", ConfidenceScorer.isInCorner(decoy, screen))
        assertFalse(ConfidenceScorer.isAtTopEdge(decoy, screen))
    }

    // ---- 攻击三：两个关闭按钮，真按钮和诱饵 ----
    //
    // 公开报道里"假关闭键"是国内投诉最多的一类。真机抓到过一个具体形态：
    // 真按钮 `tv_skip` 与诱饵 `fl_skip_wrong`（广告自己命名的"错误的跳过"）同时存在。
    //
    // 这里设计的是**更狠的一版**：诱饵不仅有 skip 味的 id，还带「关闭」描述，
    // 于是它和真按钮**同分**——选中就成了"按树序取第一个"的运气问题。

    @Test
    fun `攻击_诱饵只有id不构成证据`() {
        // 最朴素的一版：诱饵没有文案、没有描述，只有 id 像。45 分，停在疑似档。
        //
        // 注意口径：id 关键词**算自带证据**（闸门文档里写明的：文案/id/描述含关闭语义都算），
        // 但它**不算文案级证据**——后者才是同分时压过诱饵的那一票。这里两条都钉住。
        val decoy = ConfidenceScorer.score(
            node(viewId = "com.example:id/fl_skip_wrong", bounds = RectSnapshot(720, 0, 1080, 390)),
            screen,
        )
        assertEquals(45, decoy.score)
        assertEquals(Verdict.SUSPECT, decoy.verdict)
        assertFalse("id 不是文案级证据", decoy.hasTextualClose)
    }

    @Test
    fun `攻击_诱饵和真按钮同分时应优先选真按钮`() {
        // 诱饵：id 像 + **描述写「关闭」**（可见文案是空的）+ 在角落 = 45 + 10 + 20 = 75
        val decoy = ConfidenceScorer.score(
            node(viewId = "com.example:id/fl_skip_wrong", desc = "关闭"),
            screen,
        )
        // 真按钮：**可见文案**「跳过」 + 在角落 = 55 + 20 = 75
        val real = ConfidenceScorer.score(node(text = "跳过"), screen)

        assertEquals("两个都够线，这是这套攻击成立的前提", 75, decoy.score)
        assertEquals(75, real.score)

        // 判据是"可见文案"：诱饵的描述虽然也写「关闭」，但那是看不见的、可以随便写。
        assertFalse("描述不算可见文案", decoy.hasTextualClose)
        assertTrue("真按钮有可见文案", real.hasTextualClose)

        // 断言**选中的是哪一个**。2026-10-05 之前这里断言的是"选中的有没有文案"——
        // 而两个都是 true，断言恒真，等于没测；样机真跑一遍才把它露出来。
        val picked = listOf(decoy, real).sortedWith(
            compareByDescending<cn.adcalm.guard.model.Candidate> { it.score }
                .thenByDescending { it.hasTextualClose },
        ).first()
        assertEquals("同分时必须选真按钮，而不是树序在前的那一个", "跳过", picked.snapshot.text)
    }

    // ---- 攻击四：只有几何特征，没有任何文字证据 ----
    //
    // 这是真机日志里数量最大的一族（328 条 45 分），量下来 285 条是常驻 UI。
    // 闸门按"位置不是证据"拦下。（曾经想用"紧邻广告标识"给这类放行，2026-10-05
    // 按真机数据撤掉了——那条形状在真实界面里几乎不出现，见 ConfidenceScorer 里的说明。）

    @Test
    fun `攻击_纯几何横幅叉_没有广告标识时不点`() {
        val banner = RectSnapshot(33, 2007, 1047, 2154)
        val c = ConfidenceScorer.score(
            node(bounds = RectSnapshot(969, 2007, 1047, 2085), parentBounds = banner),
            screen,
        )
        assertEquals(65, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
        assertFalse("分数够了，但证据全来自位置——闸门会拦", c.hasStrongEvidence)
    }

    @Test
    fun `攻击_旁边标了广告字样_现在不再放行`() {
        // 这条原来是"紧邻广告标识 → 算证据 → 放行"。2026-10-05 按真机数据撤掉了：
        // 2,486 个带兄弟文本的节点里只有 1 个的兄弟含「广告」，而且那个不是关闭按钮。
        // 撤掉之后行为回到闸门那一档：分数够但证据不足 → 拦下。
        val banner = RectSnapshot(33, 2007, 1047, 2154)
        val c = ConfidenceScorer.score(
            node(
                bounds = RectSnapshot(969, 2007, 1047, 2085),
                parentBounds = banner,
                siblingTexts = listOf("广告"),
            ),
            screen,
        )
        assertEquals(65, c.score)
        assertFalse("广告标识不再构成证据", c.hasStrongEvidence)
    }

    // ---- 攻击五：把关闭文案画进图片里（节点树里什么都没有）----
    //
    // 节点树这一层**看不见**，只能靠 OCR。这里验的是 OCR 判定本身：
    // 真机原文是 `(跳过`——按钮的圆圈被认成了左括号。

    @Test
    fun `攻击_关闭文案画在图里_OCR侧要认得出`() {
        val c = OcrCloseScorer.evaluate(
            listOf(OcrBlock("(跳过", RectSnapshot(69, 2130, 142, 2163))),
            screen,
        )
        assertNotNull("带包裹符号的关闭文案不能被丢掉", c)
        assertEquals(80, c!!.score)
    }

    @Test
    fun `攻击_整行广告文案里碰巧有跳过_不能被当成按钮`() {
        // 广告可以故意把"跳过"写进正文里，指望我们按坐标乱点
        val c = OcrCloseScorer.evaluate(
            listOf(OcrBlock("点击跳过即可领取新人红包继续观看视频", RectSnapshot(40, 2100, 1040, 2160))),
            screen,
        )
        assertNull("横跨整行的文本块不是按钮", c)
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
