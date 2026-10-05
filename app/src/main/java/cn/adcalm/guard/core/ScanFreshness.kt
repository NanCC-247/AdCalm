package cn.adcalm.guard.core

/**
 * 「最近一次扫描」那行字。
 *
 * ## 它回答的是哪一问
 *
 * 首页原先只有「今天已跳过 N 次」。那个数字是 **0** 的时候，它同时对应两种完全不同的
 * 处境，而用户分不出来：
 *
 * - 扫描一直在跑，只是今天一个广告都没认出 → 功能没问题，碰巧没广告；
 * - **根本一拍都没扫** → 整套东西静默失效了。
 *
 * 后一种不是假想：2026-10-05 就出现过一次——节拍器每拍都从"前台包名缓存"取当前应用，
 * 而服务重启那一刻缓存是空的，用户不切应用就没有窗口事件，缓存永远空，
 * **于是节拍器永远不扫**。真机上的表现是"重装之后 20 秒内一条扫描日志都没有"，
 * 而首页当时显示的是「自动模式已开启 / 当前状态：后台已挂起」——一片健康。
 *
 * 所以这一行是和「今天已跳过 N 次」配对存在的：一个说"干成过活没有"，
 * 一个说"到底在不在干活"。少了任何一个，剩下的那个都有解释不了的空白。
 *
 * 纯函数（只做文案），可直接单元测试。
 */
object ScanFreshness {

    /** 多久以内算"刚刚"。扫描是秒级的，几秒前和"现在"对用户没有区别。 */
    private const val JUST_NOW_MS = 5_000L

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L

    /**
     * @param msSinceScan 距上一次真正扫过一遍的毫秒数；**从没扫过传 null**。
     *        服务实例不在时也传 null（那种情况另有 [serviceUp] 区分）。
     * @param serviceUp   无障碍服务是否还挂着。为 false 时这一行只说明这件事——
     *        因为那时"最近一次扫描"根本无从谈起。
     */
    fun describe(msSinceScan: Long?, serviceUp: Boolean): String = when {
        // 用词跟着首页上面那行（「后台已挂起 / 未挂起」）走，两行说的要像同一件事
        !serviceUp -> "最近一次扫描：后台未挂起"
        msSinceScan == null -> "最近一次扫描：还没有过"
        msSinceScan < JUST_NOW_MS -> "最近一次扫描：刚刚"
        msSinceScan < MINUTE_MS -> "最近一次扫描：${msSinceScan / 1000} 秒前"
        msSinceScan < HOUR_MS -> "最近一次扫描：${msSinceScan / MINUTE_MS} 分钟前"
        else -> "最近一次扫描：${msSinceScan / HOUR_MS} 小时前"
    }
}
