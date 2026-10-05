package cn.adcalm.guard.core

import cn.adcalm.guard.model.Candidate
import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import cn.adcalm.guard.model.ScoreReason
import cn.adcalm.guard.model.Verdict
import kotlin.math.abs

/**
 * 关闭按钮置信度打分。
 *
 * 设计原则：**宁可漏点，不可错点**。但"漏点"的代价也要认真对待——
 * 一律不点等于没装。所以分值是这么定的：一个明确的关闭语义文案配上角落位置，
 * **必须**能独立跨过阈值，不依赖规则库命中。
 *
 * 纯函数实现，输入是 NodeSnapshot，不接触任何 Android 类，可直接单元测试。
 */
object ConfidenceScorer {

    /** 达到该分数才执行点击。 */
    const val CLICK_THRESHOLD = 65

    /** 达到该分数但不足点击阈值时，记为"疑似"写入日志，供人工复核。 */
    const val SUSPECT_THRESHOLD = 40

    /** 明确的关闭语义文案。注意不收录单独的 "×"——假关闭按钮最常用它，需要靠 id/位置佐证。 */
    private val STRONG_CLOSE_TEXTS = setOf(
        "跳过", "跳过广告", "关闭", "关闭广告", "略过", "关闭广告页",
        "skip", "skip ad", "close",
    )

    private val CLOSE_WORDS_IN_DESC =
        listOf("关闭", "跳过", "close", "skip", "dismiss")

    /** 诱导性文案：出现这些词的节点几乎肯定是广告本身或"立即下载"陷阱。 */
    private val NEGATIVE_TEXTS = listOf(
        "立即下载", "点击安装", "查看详情", "了解更多",
        "免费领取", "立即领取", "摇一摇", "下载", "安装",
    )

    private val ID_KEYWORDS =
        listOf("skip", "close", "dismiss", "close_ad", "ad_close", "btn_skip")

    /**
     * 广告容器类名里的**通用**描述词。
     *
     * **刻意不含任何厂商前缀。** 这里原本还有两个带厂商标识的类名（一个厂商缩写前缀、
     * 一个某厂的广告控件类名），2026-10-04 移除了——那类字面量不该随项目分发，
     * 理由和默认规则库留空是同一条（见 `assets/rules/builtin.json` 的 `_why_empty`）。
     *
     * 移除在功能上没有损失：这几条只值 +10，**单独永远跨不过 65 分线**，
     * 而且 29 份真机节点树里一个广告 SDK 类名都没出现过。
     */
    private val AD_CONTAINER_HINTS = listOf("SplashAd", "SplashView")

    /**
     * 广告标识文案——**曾经用来加分，2026-10-05 按真机数据撤掉了，别再照着加回来**。
     *
     * 加它的理由看着很硬：国内开屏广告的合规要求是"清晰标明「广告」字样"，
     * 所以跳过按钮旁边基本都会有一个——GKD 的全局规则用的也是这个形状
     * （`[text="广告"] + [text^="跳过"]`）。
     *
     * 但真机上量下来它不成立：**2,486 个带兄弟文本的节点里，只有 1 个的兄弟含「广告」，
     * 而且那个节点根本不是关闭按钮。** 也就是说"广告标识是关闭按钮的兄弟"这个形状，
     * 在真实界面里几乎不出现——标记往往跟广告内容在一起，而关闭按钮在另一层。
     *
     * 留着它的代价是实打实的：一条从不触发的规则长得像能力，会让下一个会话以为
     * 这条已经覆盖了。真要重新拾起来，先拿新的真机日志再量一遍上面的数字。
     */
    @Suppress("unused")
    private val AD_MARKERS = setOf("广告", "ad", "推广", "赞助", "广告推广", "advertisement")

    private val LONG_NUMBER = Regex("^\\d{3,}$")

    /** 关闭文案的长度上限，超过就当成正文而不是按钮。取自 GKD 的 `text.length<10`。 */
    private const val MAX_CLOSE_TEXT_LENGTH = 10

    /**
     * 「跳过 3」「跳过广告3」这类"关闭文案 + 倒计时数字"的形态。
     *
     * 真机上国内 App 的跳过按钮大多是这种写法，而不是纯"跳过"三个字。
     * 不归一化的话 `text_exact "跳过"` 匹配不上，只能等倒计时跳动后靠 [CountdownTracker]
     * 认出来——那要多等一整秒。归一化之后第一次看到就能点。
     */
    private val TRAILING_COUNT = Regex("^(.+?)\\s*\\d{1,2}\\s*[sS秒]?$")

    /**
     * 文案两侧常被一起识别/带上的包裹符号。
     *
     * 2026-10-05 真机踩到：广告左下角那个「跳过」画在一个圆圈里，
     * **OCR 读回来的原文是 `(跳过`**——圆圈的左半边被认成了括号。
     * 而 OCR 那条路是精确匹配，带个括号就整条被拒，位置和尺寸明明都对得上。
     * 括号、书名号、项目符号、破折号这些都是同一类噪声，一次剥干净。
     */
    private const val EDGE_NOISE = "()[]{}<>「」『』【】《》（）·•.,，、:：;；!！?？~～-—_\"'“”‘’|/\\*#"

    /** 剥掉尾部的倒计时数字，返回归一化后的文案。 */
    fun normalizeCloseText(raw: String): String {
        // 两侧噪声先剥再小写：OCR 的括号、节点文案里的项目符号都走这一条
        val t = raw.trim().trim { it in EDGE_NOISE }.trim().lowercase()
        if (t in STRONG_CLOSE_TEXTS) return t
        val match = TRAILING_COUNT.matchEntire(t) ?: return t
        return match.groupValues[1].trim()
    }

    /**
     * 短文案里**包含**关闭词也算。
     *
     * 这条来自 GKD 的真实规则（它的全局开屏规则写的是
     * `[text*="跳过"][text.length<10][width<500 && height<300]`）：
     * 真实世界里大量按钮写的是「点击跳过」「跳过 >」「关闭弹窗」，
     * 而不是光秃秃的「跳过」两个字——只认精确相等会全部漏掉。
     *
     * 长度限制是关键：它挡住了「感谢您使用某应用…关闭个性化广告推荐…」
     * 这种长段落里恰好含"关闭"的情况。用长度兜比用精确匹配兜覆盖面大得多。
     */
    private fun isShortCloseText(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty() || t.length >= MAX_CLOSE_TEXT_LENGTH) return false
        val lower = t.lowercase()
        return CLOSE_WORDS_IN_DESC.any { lower.contains(it) }
    }

    /**
     * 该文案是否是关闭语义（归一化之后）。
     *
     * 供 OCR 路径复用同一套词表——两条识别路径必须用同一把尺子，
     * 否则规则改了这边没改那边，会出现"节点树认、OCR 不认"的诡异不一致。
     * 顺带把空格去掉，OCR 常把「跳 过」这种切分结果带回来。
     */
    fun isCloseText(raw: String): Boolean =
        normalizeCloseText(raw.replace(" ", "").replace("　", "")) in STRONG_CLOSE_TEXTS

    /**
     * @param hasCountdown 该节点是否被观测到数值递减（由 [CountdownTracker] 判定）
     * @param ruleScore    规则库命中时直接给定的分数，null 表示未命中
     * @param ruleLabel    规则命中的具体来源，写进日志便于回溯是哪条规则
     */
    fun score(
        node: NodeSnapshot,
        screen: RectSnapshot,
        hasCountdown: Boolean = false,
        ruleScore: Int? = null,
        ruleLabel: String? = null,
    ): Candidate {
        val reasons = mutableListOf<ScoreReason>()
        var total = 0
        fun add(delta: Int, label: String) {
            total += delta
            reasons += ScoreReason(delta, label)
        }

        val rawText = node.text?.trim().orEmpty()
        val text = rawText.lowercase()
        val rawDesc = node.contentDescription?.trim().orEmpty()
        val desc = rawDesc.lowercase()
        val id = node.viewId.orEmpty().lowercase()
        val closeText = normalizeCloseText(rawText)
        val areaRatio =
            if (screen.area > 0 && node.bounds.isValid) {
                node.bounds.area.toDouble() / screen.area.toDouble()
            } else {
                1.0
            }

        // 先算出"是否带关闭语义"，WebView 惩罚要用到它
        val hasCloseSemantics = closeText in STRONG_CLOSE_TEXTS ||
            isShortCloseText(rawText) ||
            ID_KEYWORDS.any { id.contains(it) } ||
            (desc.isNotEmpty() && CLOSE_WORDS_IN_DESC.any { desc.contains(it) })

        // 节点**自己写着**关闭语义：可见文案，或无障碍描述。**不含 viewId**——
        // 那个可以随便起，广告 SDK 真拿它做过诱饵（见下面位置加分处的说明）。
        val saysClose = closeText in STRONG_CLOSE_TEXTS ||
            (rawDesc.isNotEmpty() && normalizeCloseText(rawDesc) in STRONG_CLOSE_TEXTS)

        // ---- 正向 ----
        // 注意：规则分**不**加进 total。两者在最后取最大值，理由见下方注释。
        val strongText = closeText in STRONG_CLOSE_TEXTS || isShortCloseText(rawText)

        // **强关闭文案不能要求节点自己可点击。**
        //
        // 2026-10-04 在 某浏览器的开屏广告上实测到：广告明明在跑
        // （系统日志里有 splash ad load success），「跳过」也明明白白在无障碍树里，
        // 但它是个 clickable=false 的 TextView，**连它的父容器也是 false**——
        // 广告 SDK 自己接管触摸，整棵树里没有任何节点标 clickable。
        // 于是这 55 分一次都加不上，「跳过」只剩角落 +20，
        // 而旁边那个 16px 宽的倒计时数字反而以 65 分胜出——广告自然关不掉。
        //
        // 两份真机日志里 13 个「跳过」节点**无一例外**都是 clickable=false，
        // 全是真广告的跳过按钮。ClickExecutor 对不可点节点本来就会退化成坐标手势，
        // 所以这里放开是安全的。
        //
        // 只对精确强文案放开：「关闭自动播放」「无法关闭」这类"含有但不等于是"的
        // 宽泛短文本仍然要求可点击，免得点到设置项或说明文字。
        val mayClick = node.clickable || closeText in STRONG_CLOSE_TEXTS
        if (strongText && mayClick && node.bounds.isValid && areaRatio < 0.12) {
            val label = when {
                closeText in STRONG_CLOSE_TEXTS && closeText != text ->
                    "文本为「$closeText + 倒计时」形态，且面积小"
                closeText in STRONG_CLOSE_TEXTS -> "文本为关闭语义词，且面积小"
                else -> "短文本含关闭语义词（$rawText）、可点击，且面积小"
            }
            add(55, label)
        }
        if (hasCountdown) add(45, "观测到倒计时数值递减")

        // viewId 含 close/skip 这类词，是**开发者亲手命名的**，不是我们的猜测。
        //
        // 45 分是拿真机数据校准出来的：某票务应用 的 `tv_skip`（id 里明写着 skip）
        // 加上屏幕角落原本只得 40+20=60，差 5 分没点。提到 45 后 45+20=65 能点。
        // 单靠它自己仍不足以点击——45 分停在疑似档，必须再有一条位置证据。
        if (ID_KEYWORDS.any { id.contains(it) }) add(45, "viewId 含关闭关键词")

        // 位置证据：角落，或者（**节点自己写着关闭语义**时的）屏幕顶部。
        //
        // 顶部这条是 2026-10-05 从真机日志里量出来的，不是拍脑袋：一批**真关闭按钮**
        // 卡在 55 分——语义成立、位置全在顶部那一条，只是横向没进"角落"的 15% 边带
        // （跳过按钮的中心在屏幕宽度 77%~88%、关闭按钮在 14%~20%，边带线在 85% 和 15%）。
        // 两批一共 56 条疑似记录，全是该点没点的。屏幕顶部本来就是跳过/关闭按钮的
        // 另一处常见位置（开屏广告的跳过、页面左上角那种"关闭"）。
        //
        // 门槛刻意卡在 [saysClose] 而不是 [hasCloseSemantics]：后者含 viewId 关键词，
        // 而**广告 SDK 会拿 id 做诱饵**——真机日志里某票务应用的广告同时挂了 `tv_skip`
        // （真）和 `fl_skip_wrong`（广告自己命名的"错误的跳过"），后者只靠 id 拿到 45 分。
        // 一旦让 id 也能吃位置加分，那个诱饵就会凑到 65 被点下去。
        // 所以：**id 只配当分数，不配当"它就是关闭按钮"的证据。**
        if (isInCorner(node.bounds, screen)) {
            add(20, "位于屏幕角落安全区")
        } else if (saysClose && isAtTopEdge(node.bounds, screen)) {
            add(20, "位于屏幕顶部，且文案或描述写着关闭")
        }

        // 横幅广告的关闭叉：没有文本、没有描述，只是个小方块待在容器的角上。
        // 条件必须收紧——真机日志显示，放宽之后启动器图标（带文字标签）和
        // 地图控件（150x120 的大按钮）全部命中，55 分的噪声刷满日志。
        //
        // 2026-10-04 的日志里某短视频应用**标签栏**（LinearLayout，168x60）也命中了，
        // 一次使用连点 11 次。但试过两个判据都不成立，没有采纳：
        //   · 长宽比——真关闭按钮的形状很杂，实测有 168x75 和 39x156；
        //   · 祖先里有 ViewPager——真关闭按钮的祖先里同样有。
        // 这个误判至今没解决，要区分它得看到屏幕内容（诊断模式现在会连截图一起存）。
        val looksLikeBareIcon = rawText.isEmpty() && rawDesc.isEmpty() &&
            node.bounds.isValid && node.bounds.area * 200 < screen.area
        if (looksLikeBareIcon && isAtParentCorner(node.bounds, node.parentBounds)) {
            // 45 分：与"屏幕角落"叠加正好 65，这是横幅关闭叉的典型得分组合。
            // 单独成立时只有 45 分，停在疑似档，不会自己触发点击。
            add(45, "位于较大容器的角上，疑似横幅广告关闭按钮")
        }

        if (node.siblingClassNames.any { sib -> AD_CONTAINER_HINTS.any { sib.contains(it) } }) {
            add(10, "同层存在广告容器节点")
        }
        if (desc.isNotEmpty() && CLOSE_WORDS_IN_DESC.any { desc.contains(it) }) {
            add(10, "contentDescription 含关闭语义")
        }

        // ---- 负向 ----
        if (node.bounds.isValid && areaRatio > 0.35) {
            add(-100, "节点面积超过屏幕 35%，疑似整屏点击热区")
        }
        if (NEGATIVE_TEXTS.any { text.contains(it) }) {
            add(-60, "文本含诱导下载/跳转语义词")
        }
        // WebView 惩罚只针对"没有任何关闭语义的可点击节点"（那多半是广告里的内容链接）。
        // 早先这里是无条件 -50，结果整个界面都是 WebView 的应用全部候选被打死，
        // 连真正的"跳过"按钮都翻不了身。
        if (node.ancestorClassNames.any { it.contains("WebView") } && !hasCloseSemantics) {
            add(-15, "位于 WebView 内且无关闭语义，疑似内容链接")
        }
        if (isInCenter(node.bounds, screen)) {
            add(-40, "位于屏幕中央区域")
        }
        if (LONG_NUMBER.matches(rawText)) {
            add(-30, "纯数字且位数过多，疑似价格/计数")
        }

        // ---- 合并规则分：取最大值，不叠加 ----
        //
        // 早先是直接相加，代价是"规则命中(+55)"会和"文案匹配(+55)"叠成 110，
        // 足以把任何位置的同名文字推过 65 分线。实测后果是：
        // 45 个带通用规则的应用里，屏幕正中央的"跳过"文字也变成了可点击目标——
        // 而正中央从来不是关闭按钮该在的位置。
        //
        // 取最大值意味着规则必须**自己站得住**：一条 70 分的 SDK 规则可以独立触发点击，
        // 一条 55 分的弱规则最多只能把候选抬到"疑似"，借不了启发式的力。
        val heuristicScore = total
        val finalScore = if (ruleScore != null) maxOf(heuristicScore, ruleScore) else heuristicScore

        if (ruleScore != null) {
            val label = ruleLabel ?: "规则库命中"
            reasons += if (ruleScore > heuristicScore) {
                ScoreReason(ruleScore - heuristicScore, "$label → 采用规则分 $ruleScore（高于启发式 $heuristicScore）")
            } else {
                ScoreReason(0, "$label → 规则分 $ruleScore 不高于启发式 $heuristicScore，采用后者")
            }
        }

        // 关闭按钮再小也有个下限。16x48 那种细条是滚动条或徽标的边缘，不是按钮——
        // 真机日志里它靠「倒计时递减 + 屏幕角落」凑够 65 分被点过。
        // 已知的真关闭按钮最短边都在 45px 以上（45 / 72 / 96）。
        val minSide =
            if (node.bounds.isValid) minOf(node.bounds.width, node.bounds.height) else Int.MAX_VALUE
        val tooSmallToBeButton = minSide < MIN_BUTTON_SIDE

        val verdict = when {
            finalScore >= CLICK_THRESHOLD && tooSmallToBeButton -> {
                reasons += ScoreReason(
                    0,
                    "最短边 ${minSide}px 小于 ${MIN_BUTTON_SIDE}px，不像按钮，降级为疑似",
                )
                Verdict.SUSPECT
            }
            finalScore >= CLICK_THRESHOLD -> Verdict.CLICK
            finalScore >= SUSPECT_THRESHOLD -> Verdict.SUSPECT
            else -> Verdict.IGNORE
        }
        // 「自带证据」= 有什么东西在说"这个节点是关闭按钮"，而不是只有"它待在一个角落里"。
        // 四样都算：关闭语义（文案/id/描述）、观测到倒计时递减、规则库命中。
        // 这个标志不参与打分，只用于决定**在非开屏语境下能不能动手**，见 ClickGate。
        val hasStrongEvidence = hasCloseSemantics || hasCountdown || ruleScore != null

        return Candidate(node.path, node, finalScore, reasons, verdict, hasStrongEvidence, strongText)
    }

    /**
     * 节点是否落在屏幕四角安全区内。真关闭按钮几乎都在角落。
     *
     * **判据是"矩形贴到边缘"，不是"中心点落在边带里"。** 2026-10-05 用广告样机量出来的：
     * 一个摆在右上角、右边距 24dp 的「跳过」，只要按钮宽到 120dp，它的**中心点**离右边缘
     * 就有 252px，超过 15% 的边带（162px）——分数从 75 掉到 55，直接点不了。
     * 而把点击热区做宽是广告 SDK 的常见做法（真机上「跳过 3s」就是长条）。
     * 贴边判定对按钮宽度不敏感，这才对得上"关闭键在角落里"这件事本身。
     *
     * 尺寸上限是必须的：不加的话，一条贴着顶部和左边的**整宽工具栏**也算"在角落"。
     * 关闭按钮按合规要求只有屏幕面积的百分之几，这里的上限比那宽松得多，够用。
     */
    fun isInCorner(b: RectSnapshot, s: RectSnapshot): Boolean {
        if (!b.isValid || !s.isValid) return false
        if (!smallEnoughToBeCloseButton(b, s)) return false
        val marginX = s.width * 0.15
        val marginY = s.height * 0.20
        val horizontally = b.left < s.left + marginX || b.right > s.right - marginX
        val vertically = b.top < s.top + marginY || b.bottom > s.bottom - marginY
        return horizontally && vertically
    }

    /**
     * 够不够小，像不像一个关闭按钮。
     *
     * 贴边判定放宽之后必须补这一条：2026-10-05 一试就露出来了——真机抓过的那个
     * 360x390 的**诱饵块**（`fl_skip_wrong`）本来只有 45 分，按贴边判定它也算"在角落"，
     * 一下子变成 65 分可点。护栏测试当场抓住。
     *
     * 上限取屏幕面积的 4.5%：合规要求关闭按钮只有屏幕面积百分之几（开屏跳过键实测约 3.3%），
     * 而那个诱饵块是 5.4%。宽度限制是防"整宽横条"——面积闸拦不住一条 1080x40 的细条。
     */
    private fun smallEnoughToBeCloseButton(b: RectSnapshot, s: RectSnapshot): Boolean =
        b.width <= s.width * MAX_CORNER_NODE_W && b.area <= s.area * MAX_CORNER_NODE_AREA

    /** 角落/顶部判定允许的最大宽度（占屏幕比例）。整宽横条不是关闭键。 */
    private const val MAX_CORNER_NODE_W = 0.5

    /** 角落/顶部判定允许的最大面积（占屏幕比例）。见 [smallEnoughToBeCloseButton]。 */
    private const val MAX_CORNER_NODE_AREA = 0.045

    /**
     * 节点是否贴着屏幕顶部。
     *
     * 12% 这条线是量出来的：两批真关闭按钮的**上边缘**分别落在屏幕高度的 4.4% 和 7.1% 处。
     * 这里也看上边缘而不是中心点——宽按钮的中心同样会掉出去（理由见 [isInCorner]）。
     * 只有带关闭语义的节点才吃这条加分（见 [score]），所以放宽一点也不会误伤工具栏图标。
     */
    fun isAtTopEdge(b: RectSnapshot, s: RectSnapshot): Boolean =
        b.isValid && s.isValid && s.height > 0 &&
            smallEnoughToBeCloseButton(b, s) &&
            b.top < s.top + s.height * TOP_EDGE_BAND

    /** [isAtTopEdge] 的顶部边带宽度（占屏幕高度的比例）。 */
    private const val TOP_EDGE_BAND = 0.12

    /** 节点是否落在屏幕中央区域。 */
    fun isInCenter(b: RectSnapshot, s: RectSnapshot): Boolean {
        if (!b.isValid || !s.isValid) return false
        val centerX = s.left + s.width / 2.0
        val centerY = s.top + s.height / 2.0
        if (centerX <= 0 || centerY <= 0) return false
        val dx = abs(b.centerX - centerX) / (s.width / 2.0)
        val dy = abs(b.centerY - centerY) / (s.height / 2.0)
        return dx < 0.5 && dy < 0.5
    }

    /**
     * 节点是不是待在一个明显更大的容器的角上。
     *
     * 这是横幅广告关闭按钮的典型形态：一个几十像素的小方块，贴在横幅的右上角或左下角，
     * 没有文本、没有 contentDescription、甚至不是 clickable——节点树里唯一的线索就是
     * "它相对于父容器处在角落里"。
     *
     * 要求父容器面积至少是节点的 6 倍，否则"角"就没有意义（同级大小的小方块到处都是）。
     */
    fun isAtParentCorner(b: RectSnapshot, parent: RectSnapshot?): Boolean {
        if (parent == null || !parent.isValid || !b.isValid) return false
        if (parent.area < b.area * MIN_PARENT_AREA_RATIO) return false

        val fromLeft = b.centerX - parent.left
        val fromRight = parent.right - b.centerX
        val nearHorizontalEdge =
            fromLeft < parent.width * 0.25 || fromRight < parent.width * 0.25

        val fromTop = b.centerY - parent.top
        val fromBottom = parent.bottom - b.centerY
        val nearVerticalEdge =
            fromTop < parent.height * 0.35 || fromBottom < parent.height * 0.35

        return nearHorizontalEdge && nearVerticalEdge
    }

    private const val MIN_PARENT_AREA_RATIO = 6

    /**
     * 关闭按钮最短边的下限（px）。
     *
     * 真机日志里出现过 16x48 的细条靠「倒计时递减 + 屏幕角落」凑到 65 分被点中——
     * 那是滚动条或徽标的边缘，不是按钮。已知的真关闭按钮最短边是 39px（某旅行应用）。
     *
     * 注意这个下限**只管尺寸**：某短视频应用标签栏（168x60）那类误判它挡不住，
     * 那种和真关闭按钮在尺寸形状上几乎一样，只能靠看屏幕内容区分。
     */
    private const val MIN_BUTTON_SIDE = 36
}
