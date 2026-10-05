package cn.adcalm.guard.core

/**
 * 「一键选择多广告应用」的推荐逻辑。
 *
 * 纯函数，输入全是本机数据，可以直接单元测试。
 *
 * 判据只有两条，**都来自本机已经有的数据，不依赖任何外部应用清单**——
 * 这既是合规要求（项目不随源码分发按应用点名的清单），也是准确性要求：
 * 别处抄来的清单在这台机器上未必成立，而且抄来的东西错了你也不知道。
 *
 *   1. **广告证据**：观察日志里这个应用出现过多少次「疑似以上」的判定
 *   2. **使用情况**：这个应用累计用了多久（[UsageBehaviorScanner.rankByForegroundTime]）
 *
 * 顺序不能反。新用户手上还没有日志，第 1 条必然是空的，这时才走第 2 条——
 * 而第 2 条只是**代理指标**：用得多不等于有广告，只是你待得久的地方更可能碰上。
 * [Basis] 会把依据如实带出来，让界面能说清楚，不骗用户。
 */
object AppRecommender {

    /**
     * 广告证据累积到这么多条，才认为「这个应用确实有广告」。
     *
     * 取 3 而不是 1：单条证据可能只是误判或偶然路过（比如装应用时的安装器页面）。
     * 真机数据上 3 条能把浏览器这类明显有广告的（几十条）和只有一两条的区分开。
     */
    const val AD_EVIDENCE_THRESHOLD = 3

    /**
     * 走用量判据时，累计前台时长要达到这个值。
     *
     * 10 分钟／7 天 ≈ 每天一分多钟，低于它的基本是「装过、偶尔点开一下」，
     * 推给用户只会让范围虚胖——而这个工具的原则是作用面越小越安全。
     */
    const val USAGE_MIN_FOREGROUND_MS = 10 * 60 * 1000L

    /** 用量判据最多推这么多个。再多就不叫「帮你挑」而是「全选」了。 */
    const val USAGE_MAX_SUGGESTIONS = 40

    enum class Basis {
        /** 只有用量依据（新用户，还没有日志） */
        USAGE_ONLY,

        /** 用量 + 已经确认有广告的，两者并集 */
        USAGE_AND_ADS,

        /** 什么依据都没有，推不出来 */
        NOTHING,
    }

    data class Suggestion(
        val packages: Set<String>,
        val basis: Basis,
        /**
         * 前几个依据，用于在确认框里说明「凭什么推这些」。
         * 数值含义跟着 [basis] 走：是广告条数或分钟数，由 [usageBased] 区分。
         */
        val topEvidence: List<Pair<String, Int>> = emptyList(),
        /** [topEvidence] 里哪些来自用量（另一部分来自广告证据） */
        val usageBased: Set<String> = emptySet(),
    )

    /**
     * 推荐 = **用户真的在用的应用** ∪ **已经确认有广告的应用**。
     *
     * 以用量为底，是因为这个工具要解决的场景就是「你打开一个常用应用时被广告拦一下」——
     * 你根本不打开的应用，有没有广告与你无关。新用户手上还没有日志，这时就只有用量这一半；
     * 用着用着日志攒起来了，广告应用会自动并进来（[Basis.USAGE_AND_ADS]）。
     *
     * @param selectable  这台机器上真正可勾选的应用（已装、非系统包……由调用方给）
     * @param adEvidence  包名 → 广告判定条数
     * @param usageRanked 包名 → 累计前台毫秒，按时长降序
     */
    fun suggest(
        selectable: Collection<String>,
        adEvidence: Map<String, Int>,
        usageRanked: List<Pair<String, Long>>,
    ): Suggestion {
        val allowed = selectable.toHashSet()

        // 用量那一半
        val byUsage = usageRanked
            .filter { (pkg, ms) -> pkg in allowed && ms >= USAGE_MIN_FOREGROUND_MS }
            .take(USAGE_MAX_SUGGESTIONS)

        // 广告那一半
        val byAds = allowed
            .mapNotNull { pkg ->
                val n = adEvidence[pkg] ?: return@mapNotNull null
                if (n < AD_EVIDENCE_THRESHOLD) null else pkg to n
            }
            .sortedByDescending { it.second }

        if (byUsage.isEmpty() && byAds.isEmpty()) return Suggestion(emptySet(), Basis.NOTHING)

        val usagePkgs = byUsage.mapTo(LinkedHashSet()) { it.first }
        val packages = LinkedHashSet(usagePkgs).apply { byAds.forEach { add(it.first) } }

        val evidence = buildList {
            byUsage.take(4).forEach { (pkg, ms) -> add(pkg to (ms / 60_000L).toInt()) }
            byAds.filterNot { it.first in usagePkgs }.take(4).forEach { add(it) }
        }

        return Suggestion(
            packages = packages,
            basis = if (byAds.isEmpty()) Basis.USAGE_ONLY else Basis.USAGE_AND_ADS,
            topEvidence = evidence,
            usageBased = usagePkgs,
        )
    }
}
