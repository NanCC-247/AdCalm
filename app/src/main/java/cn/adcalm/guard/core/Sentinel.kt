package cn.adcalm.guard.core

/**
 * 点击哨兵。
 *
 * 点击关闭按钮之后，如果广告实际上是"点击穿透"的，就会跳转到别的应用。
 * 哨兵在点击后的一小段时间窗口内盯住前台包名的变化，用来区分两种情况：
 *
 * - 用户自己软件之间的正常跳转 → 不动
 * - 广告把用户拽到了浏览器/应用市场/马甲包 → 回退并强停
 *
 * 时间窗口是这套判定的核心：只在"我们刚点过"的瞬间成立，
 * 范围之外的任何窗口变化都与其他时刻无异，不会被误伤。
 *
 * @param canTouchPackage 目标包是否允许回退处理。两个参数：包名，以及它**是不是被外部拉起来的**
 *   （进来的界面不是自己的桌面入口）。生产环境传入
 *   [cn.adcalm.guard.core.ProtectionRegistry.canRollback]，测试里传入任意谓词。
 */
class Sentinel(private val canTouchPackage: (String, Boolean) -> Boolean) {

    private var armedAt = 0L
    private var armedPackage: String? = null
    private var armedWindowMs = WINDOW_MS

    /**
     * 武装哨兵。
     *
     * @param windowMs 观察窗口。默认 [WINDOW_MS] 就够——服务不再按模式挑窗口了，
     *                 观察模式是纯观察、压根不武装（见 [OBSERVE_WINDOW_MS]）。
     *                 这个参数留着是因为"换一个窗口"本身是个正经能力，单测在钉着。
     */
    fun arm(pkg: String, now: Long, windowMs: Long = WINDOW_MS) {
        armedAt = now
        armedPackage = pkg
        armedWindowMs = windowMs
    }

    fun disarm() {
        armedPackage = null
        armedAt = 0L
        armedWindowMs = WINDOW_MS
    }

    fun isArmed(now: Long): Boolean =
        armedPackage != null && now - armedAt <= armedWindowMs

    /**
     * 新出现的前台包是不是"还在原应用里"。
     *
     * 调用方用它区分两种**都不该回退**的情况——它们的处理方式完全不同：
     *
     * - **还在原应用内** → 什么都不做，**哨兵继续武装**
     * - 换了包但不是误跳目标 → 用户自己切走了，哨兵任务结束
     *
     * 混为一谈会漏掉最要命的一类广告。2026-10-04 晚的实测：
     * 广告 SDK 会在宿主应用**内部**弹出自己的 Activity，此时 activity 变了
     * （一个广告 SDK 自己的 Activity 类名）而包名没变。旧代码把它当成"用户自己切的"而
     * disarm，可那恰恰是广告在动——等它真把用户弹到安装器时哨兵早没了，
     * 一整晚 `ROLLBACK` 的次数是 0。
     */
    fun isStillInOrigin(newPackage: String): Boolean = armedPackage == newPackage

    /**
     * 哨兵武装时所在的包，也就是**用户本来在用的那个应用**。
     *
     * 回退流程靠它把用户送回去——只把广告应用强停掉是不够的，
     * 用户被拽走之前是在某个应用里，回退应该让他回到那里，而不是被扔在桌面上。
     *
     * ⚠ [disarm] 之后就是 null，所以调用方要在 disarm **之前**取走。
     */
    fun originPackage(): String? = armedPackage

    /**
     * 判"被广告拽过去"要不要回退。
     *
     * 条件全部满足才回退，任何一个不满足都放行：
     * 1. 哨兵处于激活窗口内
     * 2. 新包名与点击时所在的包不同（还在原应用内是正常现象）
     * 3. [canTouchPackage] 放行
     *
     * @param externallyLaunched 这个包是被外部拉起来的，而不是用户自己点开的。
     *   它能把行为保护名单（L2）放宽一档——广告把你塞进某通讯应用的小程序时，
     *   某通讯应用确实是你天天用的应用，但那一刻是广告在动，不是你。
     *   **注意它放宽的只是"把用户拉回来"，不是"强停"**，后者另有一道更严的准入。
     */
    fun shouldRollback(newPackage: String, now: Long, externallyLaunched: Boolean = false): Boolean {
        if (!isArmed(now)) return false
        if (newPackage == armedPackage) return false
        return canTouchPackage(newPackage, externallyLaunched)
    }

    /**
     * 哨兵的**迟到窗口**还在不在（3 秒窗口已过，但还没过 [LATE_WINDOW_MS]）。
     *
     * 需要它是因为一条实测出来的绕过手法：把跳转**延后**就能躲开 3 秒窗口。
     * 2026-10-05 用广告样机复现过——点完「关闭」4 秒后才跳，哨兵早已失效，
     * 用户被留在广告落地页里。所以窗口之外再留一条更窄的兜底，见 [shouldRollbackLate]。
     */
    fun wasArmedRecently(now: Long): Boolean =
        armedPackage != null && now - armedAt <= LATE_WINDOW_MS

    /**
     * 窗口之外出现的跳转要不要回退。
     *
     * **只在目标是"刚装上不久的应用"时才认**，这一点由调用方判定后传进来。
     * 依据是一条很硬的事实：广告跳过去的目标，基本都是它自己刚下载安装的马甲包。
     * **浏览器和应用市场不算**——那正是用户自己点链接会去的地方，
     * 放宽到它们就等于把正常跳转也当成误跳（[OBSERVE_WINDOW_MS] 那 30 秒的代价就是这么来的）。
     *
     * 代价说清楚：用户自己刚装了个应用、又恰好在我们点过广告之后这十几秒里打开它，
     * 会被送回上一个应用。概率很低，且后果只是"被送回去"。
     */
    fun shouldRollbackLate(newPackage: String, now: Long, isFreshlyInstalled: Boolean): Boolean {
        if (armedPackage == null || newPackage == armedPackage) return false
        val elapsed = now - armedAt
        if (elapsed <= armedWindowMs) return false      // 窗口内的走 [shouldRollback]
        if (elapsed > LATE_WINDOW_MS) return false
        if (!isFreshlyInstalled) return false
        // 第二参数是"被外部拉起"：迟到这一档按 true 走——那个包是广告拽起来的，
        // 不是用户自己点开的，跟窗口内的判定口径保持一致。
        return canTouchPackage(newPackage, true)
    }

    companion object {
        /** 点击后的观察窗口。太短会漏掉慢跳转，太长会误伤正常的应用内跳转。 */
        const val WINDOW_MS = 3_000L

        /**
         * 迟到窗口的长度。
         *
         * 15 秒是"还能被当成同一次点击的后果"的上限：再长就分不清是广告延后跳转
         * 还是用户自己打开了什么。而且这条兜底只认"刚装上的应用"（见 [shouldRollbackLate]），
         * 判据很窄，所以给到 15 秒也不会把正常使用扫进来。
         */
        const val LATE_WINDOW_MS = 15_000L

        /**
         * 观察模式原本用的加宽窗口（30 秒）。**2026-10-05 起不再被服务使用，别再照着加回来。**
         *
         * 它当年解决的问题是真的：观察模式里我们**不点**，是用户自己看到开屏、再决定去点它，
         * 从"检测到广告"到"用户真的点下去"中间隔着人的反应时间，默认那 3 秒根本不够，
         * 结果是用户点了广告、跳走了，哨兵却早已解除，看起来像保护没生效。
         *
         * 但它同时把「观察模式」变成了名不副实的东西：用户以为只是记录，手机却仍会被返回、
         * 被强停。2026-10-05 的审计把这条列成最影响信任的一个问题，用户拍板拆开——
         * **观察模式从此是纯观察，覆盖所有动作入口**（不点、不回退、不强停），
         * 于是这个宽窗口没有用武之地了。
         *
         * "只记录、但保留误跳保护"仍然可以要，但那是**另一件事**，得用独立开关表达：
         * 一个布尔开关同时管两件事，正是这个项目踩过的那类坑。
         *
         * 常量本身留着当墓碑，[arm] 的 `windowMs` 参数也留着（它是个正经能力，
         * 单测在钉着）——只是服务不再按模式去挑它了。
         */
        @Suppress("unused")
        const val OBSERVE_WINDOW_MS = 30_000L
    }
}
