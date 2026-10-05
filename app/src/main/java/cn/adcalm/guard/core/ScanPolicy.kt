package cn.adcalm.guard.core

/**
 * 扫描节奏控制。
 *
 * 无障碍事件里绝大多数是 `TYPE_WINDOW_CONTENT_CHANGED`——列表滚动、动画、
 * 倒计时刷新，每秒能来十几次。每次都遍历整棵节点树的话，即便有节流也扛不住，
 * 因为单次遍历（最多 1500 个节点，每个节点的读取都是一次跨进程调用）本身
 * 就可能超过节流间隔。**结果是用户坐在某个应用里什么都不做，也在持续吃 CPU。**
 *
 * 关键在于：广告只在两个时刻出现——
 * 1. 刚打开应用那几秒（开屏广告）
 * 2. 有新的窗口/浮层弹出来时（应用内弹窗）
 *
 * 第 2 种必然伴随 `TYPE_WINDOW_STATE_CHANGED`，那是很稀有的事件（每分钟几次），
 * 永远值得全速处理。第 1 种用一个时间窗圈住。
 * 剩下的时间降频慢扫，用来兜住"不换窗口直接盖上来"的应用内浮层。
 *
 * 纯函数实现，可直接单元测试。
 */
object ScanPolicy {

    /** 刚进入应用后的全速窗口，覆盖开屏广告从出现到可关闭的全过程。 */
    const val SPLASH_WINDOW_MS = 8_000L

    /** 开屏窗口内的最小间隔。这时用户就盯着广告，值得全速。 */
    const val FULL_THROTTLE_MS = 100L

    /**
     * 其余时间的扫描间隔。
     *
     * **2026-10-05 从 1 秒放宽到 3 秒，是量出来的**：拿一个持续刷新的目标应用做稳态测量，
     * 60 秒里本应用烧掉 **2.41 秒 CPU（约单核 4%）**；A/B 把 OCR 关掉只降到 2.29 秒——
     * 也就是说这 4% 几乎全是**每秒一次的全树遍历**（每次最多 1500 个节点、
     * 每个节点一次跨进程读），不是 OCR。
     *
     * 放宽的依据：真正决定"关得掉关不掉"的是开屏那 8 秒，那时走 [FULL_THROTTLE_MS] 全速、
     * 不受这条影响；慢扫只负责兜"不换窗口就盖上来的应用内浮层"，
     * 那种场景晚一两秒发现可以接受。**代价说清楚：这类浮层最多晚 3 秒被处理。**
     */
    const val IDLE_THROTTLE_MS = 3_000L

    /**
     * 低频兜底 OCR 的间隔。
     *
     * 有一类广告两种"值得截图的场合"都不占：**画在应用自己的 SurfaceView / Canvas 上、
     * 又不换窗口**。2026-10-05 真机实测：某地图应用里出现广告的那一帧，
     * 节点树只剩整屏容器（连窗口都没换），OCR 因为不在开屏窗口、也没有窗口切换而没被叫起来，
     * 整条广告一次都没点掉。
     *
     * 所以给"树里没有任何可用候选"的情况加一条兜底：每隔这么久允许截一次图。
     * **它比慢扫节奏（[IDLE_THROTTLE_MS]，3 秒）还粗六倍多**，就是拿"能救回这类广告"
     * 换"常驻时的耗电"——单次截图 + 文字识别是几百毫秒的实打实开销，别再往上调。
     */
    const val RELAXED_OCR_INTERVAL_MS = 20_000L

    enum class Mode {
        /** 全速扫描：遍历整棵树，允许触发 OCR。 */
        FULL,

        /** 慢速扫描：仍遍历整棵树，但频率降到 [IDLE_THROTTLE_MS]（3 秒）一次。 */
        RELAXED,

        /** 什么都不做。 */
        SKIP,
    }

    /**
     * @param isWindowChange    本次事件是否是窗口切换（新活动/新浮层）
     * @param msSinceForeground 当前应用成为前台至今的毫秒数
     * @param msSinceLastScan   上次扫描至今的毫秒数
     */
    fun decide(
        isWindowChange: Boolean,
        msSinceForeground: Long,
        msSinceLastScan: Long,
    ): Mode {
        // 窗口切换很稀有，且弹窗必然伴随它，永远值得全速处理
        if (isWindowChange) return Mode.FULL

        if (msSinceForeground < SPLASH_WINDOW_MS) {
            return if (msSinceLastScan >= FULL_THROTTLE_MS) Mode.FULL else Mode.SKIP
        }

        return if (msSinceLastScan >= IDLE_THROTTLE_MS) Mode.RELAXED else Mode.SKIP
    }

    /**
     * 是否允许触发 OCR。
     *
     * OCR 要截屏加跑一次文字识别（几百毫秒），是整条链上最重的操作。
     * 只让它跟着窗口切换和开屏窗口走——那才是它真正有价值的场合。
     * 否则坐在一个应用里，每次慢扫都截一次图，耗电会非常难看。
     */
    fun allowsOcr(isWindowChange: Boolean, msSinceForeground: Long): Boolean =
        isWindowChange || msSinceForeground < SPLASH_WINDOW_MS
}
