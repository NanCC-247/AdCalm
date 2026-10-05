package cn.adcalm.guard.core

import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import cn.adcalm.guard.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打分表的逐条验证。
 *
 * 这里的断言直接对应 [ConfidenceScorer] 里的分值表——**改分值表必须同步改这里**，
 * 否则阈值行为会悄悄漂移，而那正是最容易导致误点（或一律点不动）的地方。
 *
 * 分值表两次校准都来自真机日志：
 * - 第一次：原表下"跳过 + 角落"只有 60 分，够不到 65 的点击线，什么按钮都点不了
 * - 第二次：一批真关闭按钮（含 id 明写 close/skip 的）卡在 55~60 分
 */
class ConfidenceScorerTest {

    /** 1080x2400 的常见手机屏幕。 */
    private val screen = RectSnapshot(0, 0, 1080, 2400)

    /** 右上角的小按钮，符合"真关闭按钮"的空间特征。 */
    private val cornerBounds = RectSnapshot(880, 120, 1040, 200)

    private fun node(
        text: String? = null,
        viewId: String? = null,
        desc: String? = null,
        clickable: Boolean = true,
        bounds: RectSnapshot = cornerBounds,
        parentBounds: RectSnapshot? = null,
        ancestors: List<String> = emptyList(),
        siblings: List<String> = emptyList(),
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
        siblingClassNames = siblings,
        siblingTexts = siblingTexts,
    )

    // ---- 底线：一个明确的关闭文案配上角落位置，必须能独立跨过阈值 ----

    @Test
    fun `跳过加角落位置即可点击`() {
        // 55 (强文案) + 20 (角落) = 75
        val c = ConfidenceScorer.score(node(text = "跳过"), screen)
        assertEquals(75, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `倒计时加角落位置即可点击`() {
        // 45 (倒计时) + 20 (角落) = 65
        val c = ConfidenceScorer.score(node(text = "3"), screen, hasCountdown = true)
        assertEquals(65, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `跳过 加倒计时数字即可点击，不必等数值跳动`() {
        // 55 + 20 = 75。国内 App 的跳过按钮基本都长这样，
        // 只认精确的"跳过"三个字会匹配不上，得白等一整秒。
        val c = ConfidenceScorer.score(node(text = "跳过 3"), screen)
        assertEquals(75, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `倒计时叠加 viewId 关键词`() {
        // 45 (倒计时) + 45 (viewId) + 20 (角落) = 110
        val c = ConfidenceScorer.score(
            node(text = "3", viewId = "com.example:id/ad_skip"),
            screen,
            hasCountdown = true,
        )
        assertEquals(110, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `跳过 叠加 viewId 关键词`() {
        // 55 + 45 + 20 = 120
        val c = ConfidenceScorer.score(
            node(text = "跳过", viewId = "com.example:id/tv_skip"),
            screen,
        )
        assertEquals(120, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `强关闭文案不要求节点自己可点击`() {
        // 2026-10-04 实测：某浏览器的开屏广告里，「跳过」是 clickable=false 的 TextView，
        // 连父容器也是 false（广告 SDK 自己接管触摸）。要求可点击就等于放弃这类广告。
        val c = ConfidenceScorer.score(node(text = "跳过", clickable = false), screen)
        // 55（强文案）+ 20（角落）
        assertEquals(75, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `宽泛的关闭文案仍然要求可点击`() {
        // 「关闭自动播放」是某短视频应用长按菜单里的一项，「无法关闭」是说明文字——
        // 它们含有"关闭"但不等于关闭按钮。这条钉住放开的范围没有扩大。
        val menu = ConfidenceScorer.score(node(text = "关闭自动播放", clickable = false), screen)
        assertEquals(20, menu.score)
        assertEquals(Verdict.IGNORE, menu.verdict)

        val notice = ConfidenceScorer.score(node(text = "无法关闭", clickable = false), screen)
        assertEquals(20, notice.score)
        assertEquals(Verdict.IGNORE, notice.verdict)
    }

    // ---- 规则分与启发式分取最大值，不相加 ----

    @Test
    fun `规则分不叠加，取最大值`() {
        // 启发式 55+45+20 = 120；规则 95 → 取 120，不是 215
        val c = ConfidenceScorer.score(
            node(text = "跳过", viewId = "com.example.app:id/tv_skip"),
            screen,
            ruleScore = 95,
        )
        assertEquals(120, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `规则分高于启发式时采用规则分`() {
        // 启发式 45+20 = 65；规则 70 → 取 70
        val c = ConfidenceScorer.score(
            node(viewId = "com.example:id/example_skip_btn"),
            screen,
            ruleScore = 70,
        )
        assertEquals(70, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `弱规则不能借启发式的力越过点击线`() {
        // 回归测试。早先规则分是与启发式相加的，后果是：带通用规则的应用里，
        // 屏幕正中央的「跳过」也变成了可点击目标。
        //
        //   相加：  55 (文案) − 40 (中央) + 55 (规则) = 70  → CLICK   ← 错
        //   取最大：max(15, 55)                       = 55  → SUSPECT ← 对
        val center = RectSnapshot(440, 1100, 640, 1300)
        val c = ConfidenceScorer.score(
            node(text = "跳过", bounds = center),
            screen,
            ruleScore = 55,
        )
        assertEquals(55, c.score)
        assertEquals("弱规则只能抬到疑似档", Verdict.SUSPECT, c.verdict)
    }

    @Test
    fun `规则分不为整屏热区翻案`() {
        // 整屏热区被扣到 -140，规则分取最大值也只是规则分自己
        val fullScreen = RectSnapshot(0, 0, 1080, 2400)
        val c = ConfidenceScorer.score(
            node(text = "跳过", bounds = fullScreen),
            screen,
            ruleScore = 70,
        )
        assertEquals(70, c.score)
    }

    // ---- WebView ----

    @Test
    fun `WebView 内的跳过按钮不受惩罚`() {
        // 整个界面都是 WebView 的应用（某地图应用、大量资讯类 App）里，
        // 无条件 -50 会把连真正的跳过按钮一起打死。
        val c = ConfidenceScorer.score(
            node(text = "关闭", ancestors = listOf("android.webkit.WebView")),
            screen,
        )
        assertEquals(75, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `WebView 内没有关闭语义的可点击节点被扣分`() {
        // 20 (角落) - 15 (WebView 内容链接) = 5
        val c = ConfidenceScorer.score(
            node(ancestors = listOf("android.webkit.WebView")),
            screen,
        )
        assertEquals(5, c.score)
        assertEquals(Verdict.IGNORE, c.verdict)
    }

    @Test
    fun `WebView 内的 viewId 关键词不算内容链接`() {
        // 45 (viewId) + 20 (角落) = 65。id 里写着 skip，就不是普通链接。
        val c = ConfidenceScorer.score(
            node(viewId = "com.example:id/skip_btn", ancestors = listOf("android.webkit.WebView")),
            screen,
        )
        assertEquals(65, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    // ---- 负向规则 ----

    @Test
    fun `整屏点击热区被一票否决`() {
        val fullScreen = RectSnapshot(0, 0, 1080, 2400)
        val c = ConfidenceScorer.score(node(text = "跳过", bounds = fullScreen), screen)
        assertTrue("整屏节点应为 IGNORE，实际 ${c.score}", c.score < ConfidenceScorer.SUSPECT_THRESHOLD)
        assertEquals(Verdict.IGNORE, c.verdict)
    }

    @Test
    fun `诱导下载文案被重罚`() {
        // 20 (角落) - 60 (诱导) = -40
        val c = ConfidenceScorer.score(node(text = "立即下载"), screen)
        assertEquals(-40, c.score)
        assertEquals(Verdict.IGNORE, c.verdict)
    }

    @Test
    fun `屏幕中央的按钮被扣分`() {
        val center = RectSnapshot(440, 1100, 640, 1300)
        // 55 (强文案) + 45 (viewId) - 40 (中央) = 60
        val c = ConfidenceScorer.score(
            node(text = "关闭", viewId = "com.example:id/btn_close", bounds = center),
            screen,
        )
        assertEquals(60, c.score)
        assertEquals("中央位置只能到疑似档，不该点击", Verdict.SUSPECT, c.verdict)
    }

    @Test
    fun `三位以上纯数字被当作价格而非倒计时`() {
        // 20 (角落) - 30 (长数字) = -10
        val c = ConfidenceScorer.score(node(text = "1999"), screen)
        assertEquals(-10, c.score)
        assertEquals(Verdict.IGNORE, c.verdict)
    }

    @Test
    fun `无效 bounds 的节点不会因为面积为零而拿到文案加分`() {
        val c = ConfidenceScorer.score(
            node(text = "跳过", bounds = RectSnapshot.EMPTY),
            screen,
        )
        assertFalse("不可见节点不应获得强文案加分", c.score >= 55)
        assertEquals(Verdict.IGNORE, c.verdict)
    }

    // ---- 文案归一化 ----

    @Test
    fun `跳过 文案带倒计时数字也能归一化`() {
        assertEquals("跳过", ConfidenceScorer.normalizeCloseText("跳过 3"))
        assertEquals("跳过", ConfidenceScorer.normalizeCloseText("跳过3"))
        assertEquals("跳过", ConfidenceScorer.normalizeCloseText("跳过 3s"))
        // 剥掉的是数字，不是"广告"两个字——归一化结果本身也在关闭词表里
        assertEquals("跳过广告", ConfidenceScorer.normalizeCloseText("跳过广告 5秒"))
        assertEquals("关闭", ConfidenceScorer.normalizeCloseText("关闭2"))
    }

    @Test
    fun `非关闭词的文案归一化后仍拿不到点击分`() {
        val c = ConfidenceScorer.score(node(text = "广告3"), screen)
        assertTrue("实际 ${c.score}", c.score < ConfidenceScorer.CLICK_THRESHOLD)
    }

    // ---- 短文本包含关闭词也算（来自 GKD 的真实规则：`[text*="跳过"][text.length<10]`）----

    @Test
    fun `点击跳过 这类短文案能被识别`() {
        // 真实世界里大量按钮写的是「点击跳过」「跳过 >」而不是光秃秃的「跳过」。
        // 只认精确相等会全部漏掉。
        val c = ConfidenceScorer.score(node(text = "点击跳过"), screen)
        assertEquals(75, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `关闭弹窗 这类短文案能被识别`() {
        val c = ConfidenceScorer.score(node(text = "关闭弹窗"), screen)
        assertEquals(75, c.score)
    }

    @Test
    fun `长段落里含关闭词不算按钮`() {
        // 真机日志里某旅行应用的隐私政策正文里就含「关闭个性化广告推荐」。
        // 长度限制是这条规则的关键——用精确匹配兜覆盖面太小，用长度兜才对。
        val policy = "感谢您使用某应用！我们非常重视您的个人信息和隐私保护。" +
            "您可以在设置中关闭个性化广告推荐，关闭后我们将不再基于您的个人特征进行广告推荐。"
        val c = ConfidenceScorer.score(node(text = policy), screen)
        assertTrue("长正文不该拿到关闭文案加分，实际 ${c.score}", c.score < ConfidenceScorer.SUSPECT_THRESHOLD)
        assertEquals(Verdict.IGNORE, c.verdict)
    }

    @Test
    fun `长正文在 WebView 里仍被当作内容链接扣分`() {
        val policy = "点击下方按钮关闭该页面并返回上一级菜单"
        val c = ConfidenceScorer.score(
            node(text = policy, ancestors = listOf("android.webkit.WebView")),
            screen,
        )
        // 20 (角落) - 15 (WebView 无关闭语义) = 5
        assertEquals(5, c.score)
        assertEquals(Verdict.IGNORE, c.verdict)
    }

    // ---- 横幅广告：关闭叉常常没有任何文本/id，只能靠相对父容器的位置 ----

    @Test
    fun `横幅容器角上的小节点被认出来记入疑似档`() {
        // 坐标取自真机日志：某地图应用底部横幅 [33,2007,1047,2154] 里的一个 78x78 方块
        val banner = RectSnapshot(33, 2007, 1047, 2154)
        val closeX = RectSnapshot(969, 2007, 1047, 2085)

        val c = ConfidenceScorer.score(
            node(bounds = closeX, parentBounds = banner, ancestors = listOf("android.webkit.WebView")),
            screen,
        )
        // 20 (屏幕角落) + 45 (容器角落) - 15 (WebView 无关闭语义) = 50
        assertEquals(50, c.score)
        assertEquals(Verdict.SUSPECT, c.verdict)
    }

    // ---- 「紧邻广告标识」：加过又撤掉了（2026-10-05），别再照着加 ----
    //
    // 当初的理由很硬：合规要求广告标出「广告」字样，GKD 的规则用的也是这个形状
    // （`[text="广告"] + [text^="跳过"]`）。但真机数据不支持——
    // **2,486 个带兄弟文本的节点里只有 1 个的兄弟含「广告」，而且那个不是关闭按钮。**
    // 一条从不触发的规则长得像能力，比没有更糟，所以撤了。下面是钉住"撤掉"这件事的用例。

    @Test
    fun `兄弟里有广告字样不再影响分数和证据`() {
        // 同一几何、同一位置：兄弟里有没有「广告」字样，结果必须一样（50 分疑似、不算证据）。
        // 将来谁想把它加回来，先拿新的真机日志把上面那个 1/2486 重量一遍。
        val banner = RectSnapshot(33, 2007, 1047, 2154)
        val closeX = RectSnapshot(969, 2007, 1047, 2085)
        for (sibs in listOf(listOf("广告"), listOf("推荐", "关注", "我的"))) {
            val c = ConfidenceScorer.score(
                node(
                    bounds = closeX,
                    parentBounds = banner,
                    ancestors = listOf("android.webkit.WebView"),
                    siblingTexts = sibs,
                ),
                screen,
            )
            assertEquals("兄弟文案不该改变分数（$sibs）", 50, c.score)
            assertEquals(Verdict.SUSPECT, c.verdict)
            assertFalse("也不该构成自带证据（$sibs）", c.hasStrongEvidence)
        }
    }

    @Test
    fun `横幅关闭叉若带 viewId 关键词则足以点击`() {
        val banner = RectSnapshot(33, 2007, 1047, 2154)
        val closeX = RectSnapshot(969, 2007, 1047, 2085)

        val c = ConfidenceScorer.score(
            node(viewId = "com.example:id/ad_close", bounds = closeX, parentBounds = banner),
            screen,
        )
        // 45 (viewId) + 20 (屏幕角落) + 45 (容器角落) = 110
        assertEquals(110, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    // ---- 真机数据回归：坐标和 id 直接来自用户手机的观察日志 ----

    @Test
    fun `真机日志里的 closeIv 能过线`() {
        // 真机日志：某浏览器底部横幅的关闭按钮
        //   id='com.example.browser:id/closeIv'   bounds=[1007,1845,1061,1899]
        // 校准前它只得 60 分，差 5 分没点 —— 这正是"有些广告关不掉"的直接原因。
        //
        // 日志没记录父容器位置，这里用一个同时满足「面积 6 倍以上」和
        // 「节点在容器角上」的父容器，验证的是打分逻辑本身。
        val nodeBounds = RectSnapshot(1007, 1845, 1061, 1899)
        val parentBounds = RectSnapshot(890, 1830, 1080, 2100)

        val c = ConfidenceScorer.score(
            node(viewId = "com.example.browser:id/closeIv", bounds = nodeBounds, parentBounds = parentBounds),
            screen,
        )
        // 45 (viewId) + 45 (容器角落) = 90
        assertEquals(90, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `真机日志里某票务应用的 tv_skip 能过线`() {
        // 真机日志：某票务应用 的跳过按钮，id 里明写着 skip
        //   id='com.example.ticket:id/tv_skip'   bounds=[864,150,1008,225]
        // 校准前是 40+20=60，差 5 分没点（当时靠同位置的 tv_main_splash_skip 顶上了，
        // 但如果某个应用只有这一个节点就会漏掉）
        val c = ConfidenceScorer.score(
            node(viewId = "com.example.ticket:id/tv_skip", bounds = RectSnapshot(864, 150, 1008, 225)),
            screen,
        )
        // 45 (viewId) + 20 (屏幕角落) = 65
        assertEquals(65, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `广告自己的诱饵按钮不会被点`() {
        // 真机日志里某票务应用的广告同时暴露了两个跳过节点：
        //   tv_skip        —— 真的
        //   fl_skip_wrong  —— 广告 SDK 自己命名的"错误的跳过"（诱饵）
        // 诱饵没有位置加分，停在疑似档。
        val decoy = ConfidenceScorer.score(
            node(viewId = "com.example.ticket:id/fl_skip_wrong", bounds = RectSnapshot(720, 0, 1080, 390)),
            screen,
        )
        assertEquals(45, decoy.score)
        assertEquals(Verdict.SUSPECT, decoy.verdict)
    }

    @Test
    fun `两个位置信号叠加足以点击无文本的横幅关闭叉`() {
        // 真机日志里 某浏览器 [870,117,1038,192] 和某旅行应用 [1041,1978,1080,2134]
        // 各有一个这样的节点，校准前都只有 55 分没点。
        val nodeBounds = RectSnapshot(870, 117, 1038, 192)
        val parentBounds = RectSnapshot(0, 0, 1080, 500)

        val c = ConfidenceScorer.score(node(bounds = nodeBounds, parentBounds = parentBounds), screen)
        // 20 (屏幕角落) + 45 (容器角落) = 65
        assertEquals(65, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `太细的节点不算按钮，分数够了也降级为疑似`() {
        // 真机日志：浏览器里一个 16x48 的节点靠「倒计时递减 + 屏幕角落」凑够 65 分被点过。
        // 那是滚动条或徽标的边缘。已知的真关闭按钮最短边是 39px。
        val sliver = RectSnapshot(999, 324, 1015, 372)
        val c = ConfidenceScorer.score(node(text = "4", bounds = sliver), screen, hasCountdown = true)

        // 分数照记——校准要看得到它；但不能真去点。
        assertEquals(65, c.score)
        assertEquals(Verdict.SUSPECT, c.verdict)
    }

    @Test
    fun `窄但够长的关闭按钮不受尺寸下限影响`() {
        // 某旅行应用那个真关闭按钮是 39x156 —— 很窄，但 39 > 36 的下限，不该被尺寸规则拦下。
        // 这里用 viewId 拿那 45 分，好让这条测试只考「尺寸」一件事。
        val narrow = RectSnapshot(1041, 1978, 1080, 2134)
        val c = ConfidenceScorer.score(
            node(viewId = "com.example.travel:id/close", bounds = narrow),
            screen,
        )
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `viewId 单独成立时仍不足以点击`() {
        // 45 分：id 里有 close，但位置既不在角落也不在容器角上 —— 只进疑似档。
        // 这条钉住"抬 id 权重"没有把安全性一起搭进去。
        val c = ConfidenceScorer.score(
            node(viewId = "com.example:id/btn_close", bounds = RectSnapshot(700, 400, 820, 520)),
            screen,
        )
        assertEquals(45, c.score)
        assertEquals(Verdict.SUSPECT, c.verdict)
    }

    @Test
    fun `带文字标签的图标不会被当成容器角落的关闭按钮`() {
        // 真机日志里启动器图标就是这个形态：窄条、贴左边缘、带文字标签。
        // 早先无条件给"容器角落"加分时它们全部拿到 55 分，把日志刷满噪声。
        val cell = RectSnapshot(0, 111, 267, 431)
        val icon = RectSnapshot(0, 111, 89, 431)

        val c = ConfidenceScorer.score(
            node(text = "学而思", desc = "学而思", bounds = icon, parentBounds = cell),
            screen,
        )
        assertEquals("只该拿到屏幕角落的 20 分", 20, c.score)
        assertEquals(Verdict.IGNORE, c.verdict)
    }

    @Test
    fun `过大的节点不会被当成容器角落的关闭按钮`() {
        // 某地图应用的控件是 150x120，面积 18000，超过屏幕的 0.5%
        val map = RectSnapshot(0, 0, 1080, 2280)
        val control = RectSnapshot(920, 114, 1070, 234)

        val c = ConfidenceScorer.score(
            node(bounds = control, parentBounds = map),
            screen,
        )
        assertEquals("不该拿到容器角落分，实际 ${c.score}", 20, c.score)
    }

    @Test
    fun `同级大小的小方块不算容器角落`() {
        // 父容器和节点差不多大时，"角"就没有意义了
        val siblingSized = RectSnapshot(500, 500, 560, 560)
        val parent = RectSnapshot(500, 500, 560, 560)
        val c = ConfidenceScorer.score(
            node(bounds = siblingSized, parentBounds = parent),
            screen,
        )
        assertTrue("不该拿到容器角落加分，实际 ${c.score}", c.score < 20)
    }

    @Test
    fun `没有父节点信息时不加分`() {
        val c = ConfidenceScorer.score(node(bounds = RectSnapshot(500, 500, 560, 560)), screen)
        assertEquals(0, c.score)
    }

    // ---- 空间判定 ----

    @Test
    fun `四角都能被识别为角落安全区`() {
        val topLeft = RectSnapshot(20, 40, 180, 120)
        val topRight = RectSnapshot(900, 40, 1060, 120)
        val bottomLeft = RectSnapshot(20, 2280, 180, 2360)
        val bottomRight = RectSnapshot(900, 2280, 1060, 2360)
        for (r in listOf(topLeft, topRight, bottomLeft, bottomRight)) {
            assertTrue("$r 应被判为角落", ConfidenceScorer.isInCorner(r, screen))
        }
    }

    @Test
    fun `屏幕正中央不是角落`() {
        assertFalse(ConfidenceScorer.isInCorner(RectSnapshot(490, 1150, 590, 1250), screen))
        assertTrue(ConfidenceScorer.isInCenter(RectSnapshot(490, 1150, 590, 1250), screen))
    }

    // ---- 顶部边缘：2026-10-05 从真机日志里量出来的第二处位置证据 ----
    //
    // 一批真关闭按钮卡在 55 分：关闭语义成立、位置全在顶部那一条，只是**横向**没进
    // "角落"的 15% 边带（边带线在 85% 和 15%，而跳过按钮的中心在 77%~88%、
    // 关闭按钮在 14%~20%）。两批一共 56 条疑似记录，全是该点没点的。

    @Test
    fun `顶部边缘的跳过按钮能过线`() {
        // 真机形态：text=跳过、clickable=false、[836,60,950,150]——横向差 25px 进不了角落边带。
        // 55 (强文案) + 20 (顶部) = 75
        val c = ConfidenceScorer.score(
            node(text = "跳过", clickable = false, bounds = RectSnapshot(836, 60, 950, 150)),
            screen,
        )
        assertEquals(75, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `顶部边缘的关闭按钮（关键词加描述）能过线`() {
        // 真机形态：viewId 含 close、contentDescription=关闭、[150,135,222,207]。
        // 45 (关键词) + 10 (描述) + 20 (顶部) = 75
        val c = ConfidenceScorer.score(
            node(
                viewId = "com.example:id/title_close_btn",
                desc = "关闭",
                bounds = RectSnapshot(150, 135, 222, 207),
            ),
            screen,
        )
        assertEquals(75, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
    }

    @Test
    fun `顶部边缘不给没有关闭语义的图标加分`() {
        // 顶部那一条里没有文字、没有描述、id 也不含关键词的图标：几何上同样在顶部，
        // 但它**没有关闭语义**，不该吃这 20 分——它在日志里靠"容器角落"拿 45 分，
        // 本来就该停在疑似档（工具栏图标、导航栏图标都是这个形状）。
        val c = ConfidenceScorer.score(
            node(
                bounds = RectSnapshot(610, 40, 694, 124),
                parentBounds = RectSnapshot(300, 30, 700, 900),
            ),
            screen,
        )
        assertEquals(45, c.score)
        assertEquals(Verdict.SUSPECT, c.verdict)
    }

    @Test
    fun `顶部边缘之外的强文案仍是疑似`() {
        // 屏幕中部偏上的「跳过」（纵向 22%、横向居中）既不是角落、也不在顶部边带里，
        // 仍然是 55 分疑似——顶部这条放宽不能顺手把中部也放了。
        val c = ConfidenceScorer.score(
            node(text = "跳过", bounds = RectSnapshot(400, 500, 560, 590)),
            screen,
        )
        assertEquals(55, c.score)
        assertEquals(Verdict.SUSPECT, c.verdict)
    }

    // ---- 「自带证据」标志 ----
    //
    // 它不影响分数，只决定**非开屏语境下能不能动手**（见 ClickGate）。
    // 所以这里要钉住的是"哪些算证据"——判宽了会让误点回来，判窄了会让真按钮点不掉。

    @Test
    fun `关闭文案算证据`() {
        assertTrue(ConfidenceScorer.score(node(text = "跳过"), screen).hasStrongEvidence)
        assertTrue(ConfidenceScorer.score(node(text = "跳过 3"), screen).hasStrongEvidence)
    }

    @Test
    fun `viewId 含关闭关键词算证据`() {
        // 某票务应用的 tv_main_splash_skip：开发者亲手命名的，是最硬的证据。
        val c = ConfidenceScorer.score(node(viewId = "com.example:id/tv_main_splash_skip"), screen)
        assertTrue(c.hasStrongEvidence)
    }

    @Test
    fun `描述含关闭语义算证据`() {
        val c = ConfidenceScorer.score(node(desc = "关闭广告"), screen)
        assertTrue(c.hasStrongEvidence)
    }

    @Test
    fun `观测到倒计时递减算证据`() {
        val c = ConfidenceScorer.score(node(text = "4"), screen, hasCountdown = true)
        assertTrue(c.hasStrongEvidence)
    }

    @Test
    fun `规则命中算证据`() {
        val c = ConfidenceScorer.score(node(), screen, ruleScore = 70, ruleLabel = "测试规则")
        assertTrue(c.hasStrongEvidence)
    }

    @Test
    fun `纯位置凑到 65 分的候选不算证据`() {
        // 这是真机上点错最多的那一类，也是 ClickGate 存在的全部理由：
        // 节点三样全空，只靠「屏幕角落 20 + 容器角落 45」= 65 压线。
        // 分数和判定都不变（65 / CLICK），变的只是"允许它动手的场合"。
        val c = ConfidenceScorer.score(
            node(bounds = cornerBounds, parentBounds = screen),
            screen,
        )
        assertEquals(65, c.score)
        assertEquals(Verdict.CLICK, c.verdict)
        assertFalse(
            "无文案无 id 无描述，不该被当成自带证据",
            c.hasStrongEvidence,
        )
    }

    @Test
    fun `只有 id 但不是关闭关键词的，不算证据`() {
        // 某短视频应用那几次点错的节点是有 viewId 的，但名字是 uu5 / ns1 这种混淆过的，
        // 不含任何关闭语义——"有 id"本身不是证据，"id 说得出这是什么"才是。
        for (id in listOf("com.example:id/uu5", "com.example:id/ns1", "com.example:id/ncv_check")) {
            val c = ConfidenceScorer.score(node(viewId = id, bounds = cornerBounds, parentBounds = screen), screen)
            assertFalse("$id 不含关闭关键词，不该算证据", c.hasStrongEvidence)
        }
    }
}
