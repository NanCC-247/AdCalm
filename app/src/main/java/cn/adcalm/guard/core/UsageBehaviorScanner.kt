package cn.adcalm.guard.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * 判定哪些应用是"用户自己在用的"。
 *
 * 这是保护名单里最关键的一层（L2 行为保护），用来区分两种情况：
 * - 用户自己点图标打开的某通讯应用 —— 绝对不能碰
 * - 广告把用户拽过去的马甲包 —— 允许强停
 *
 * ## 判据只有一个：**是否从桌面图标启动过**
 *
 * 早先还有一条"前台累计时长 > 60 秒"，**已删除**。理由是它分不清
 * "用户自己在用"和"被广告拽过去停了一会儿又回来"——而后者会累积得很快。
 * 2026-10-04 实测：`com.android.packageinstaller` 7 天累计前台 9 分 32 秒，
 * 于是这个**广告下载闭环的最后一环**被收进了"用户自用"名单，
 * 广告把它弹出来时，回退反而被保护名单挡住了。广告越频繁地拉它，它就越"受保护"——
 * 这是个自相矛盾的循环。
 *
 * "从桌面图标启动"则是用户**主动**的证据，广告伪造不了：它把你弹过去时，
 * 上一个前台是宿主应用而不是桌面。代价是只通过通知或链接打开、从没点过图标的应用
 * 会失去这一层保护——但强停还有另一道"像不像广告目标"的闸门挡着
 * （见 [ProtectionRegistry.canForceStop]），普通应用仍然不会被误杀。
 */
object UsageBehaviorScanner {

    /**
     * @param days 回溯天数
     * @return 应当被视为"用户自用"从而受到保护的包集合
     */
    fun scanUserLaunched(context: Context, days: Int): Set<String> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptySet()

        val end = System.currentTimeMillis()
        val begin = end - days * 24L * 60 * 60 * 1000

        return scanLauncherOriginated(context, usm, begin, end)
    }

    /**
     * 找出"由桌面启动"的包：某个包被前台拉起时，紧邻的上一个前台是桌面。
     * 这能兜住刚安装、前台时长还不够长的应用。
     */
    private fun scanLauncherOriginated(
        context: Context,
        usm: UsageStatsManager,
        begin: Long,
        end: Long,
    ): Set<String> {
        val launchers = AppClassifier.launcherPackages(context)
        if (launchers.isEmpty()) return emptySet()

        val result = mutableSetOf<String>()
        var previousPackage: String? = null

        runCatching {
            val events: UsageEvents = usm.queryEvents(begin, end)
            val event = UsageEvents.Event()
            while (events.getNextEvent(event)) {
                if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                val pkg = event.packageName ?: continue
                if (previousPackage in launchers) {
                    result += pkg
                }
                previousPackage = pkg
            }
        }

        return result
    }

    /**
     * 按「用了多久」给应用排序。
     *
     * 和 [scanUserLaunched] 的区别：那个回答的是「**是不是**用户在自己在用」——
     * 保护名单要的就是一个集合；这个回答的是「**用了多少**」——推荐要的是排序。
     *
     * 为什么推荐需要排序：新用户手上还没有观察日志，**「用得多」是唯一能拿到的信号**，
     * 而它必须能分出先后，否则只能要么一个都不推、要么把装过的全推上去。
     *
     * @return (包名, 累计前台毫秒) 按时长降序；没有使用情况权限时返回空列表
     */
    fun rankByForegroundTime(context: Context, days: Int): List<Pair<String, Long>> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val end = System.currentTimeMillis()
        val begin = end - days * 24L * 60 * 60 * 1000

        val ranked = ArrayList<Pair<String, Long>>()
        runCatching {
            usm.queryAndAggregateUsageStats(begin, end).forEach { (pkg, stats) ->
                val ms = stats.totalTimeInForeground
                if (ms > 0L) ranked += pkg to ms
            }
        }

        return ranked.sortedByDescending { it.second }
    }
}
