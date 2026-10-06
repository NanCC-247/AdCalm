package cn.adcalm.guard.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cn.adcalm.guard.core.AppClassifier
import cn.adcalm.guard.core.AppNeutralizer
import cn.adcalm.guard.core.ClickCooldown
import cn.adcalm.guard.core.ClickExecutor
import cn.adcalm.guard.core.ClickGate
import cn.adcalm.guard.core.CloseButtonFinder
import cn.adcalm.guard.core.ConfidenceScorer
import cn.adcalm.guard.core.GuardSettings
import cn.adcalm.guard.core.NodeTreeReader
import cn.adcalm.guard.core.OcrBlock
import cn.adcalm.guard.core.OcrCandidate
import cn.adcalm.guard.core.OcrCloseScorer
import cn.adcalm.guard.core.OcrFreshness
import cn.adcalm.guard.core.ProtectionRegistry
import cn.adcalm.guard.core.ScanPolicy
import cn.adcalm.guard.core.Sentinel
import cn.adcalm.guard.data.DownloadJanitor
import cn.adcalm.guard.data.ObservationEntry
import cn.adcalm.guard.data.ObservationLog
import cn.adcalm.guard.data.SnapshotStore
import cn.adcalm.guard.model.Candidate
import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import cn.adcalm.guard.model.ScoreReason
import cn.adcalm.guard.model.Verdict
import cn.adcalm.guard.ocr.OcrEngine
import cn.adcalm.guard.ocr.ScreenshotCapturer
import cn.adcalm.guard.rules.RuleRepository
import cn.adcalm.guard.rules.RuleSet
import cn.adcalm.guard.shizuku.ShizukuShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 核心服务：读取其他应用的窗口树，识别广告弹窗的关闭按钮并点击，
 * 点击后如果被跳转到别的软件则回退并强停。
 *
 * 事件处理有四道闸门，顺序不能调换：
 * 1. 强停流程进行中 → 事件全交给它，我们不在设置页里做广告识别
 * 2. 点击哨兵      → **必须早于生效范围过滤**，因为被广告拽过去的包几乎都不在生效范围内，
 *                    先过滤就永远检测不到误跳
 * 3. 生效范围过滤  → 没勾选的 App 完全不介入
 * 4. 观察模式      → 即使分数达标也只记录不点击
 */
class AdCalmAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var settings: GuardSettings
    private lateinit var registry: ProtectionRegistry
    private lateinit var log: ObservationLog
    private lateinit var finder: CloseButtonFinder
    private lateinit var clickExecutor: ClickExecutor
    private lateinit var sentinel: Sentinel
    private lateinit var neutralizer: AppNeutralizer
    private lateinit var ruleSet: RuleSet
    private lateinit var ocrEngine: OcrEngine
    private lateinit var snapshotStore: SnapshotStore
    private lateinit var janitor: DownloadJanitor

    /** 抓快照的最小间隔，以及"正在抓"的标志。见 [reserveSnapshotSlot]。 */
    private var lastSnapshotAt = 0L

    @Volatile
    private var snapshotInFlight = false

    /** OCR 又慢又费电，节流到每个包每秒最多一次。 */
    private var lastOcrAt = 0L
    private var lastOcrPackage: String? = null

    /** 上次真正扫描的时间。扫描节奏由 [ScanPolicy] 决定。 */
    private var lastScanAt = 0L

    /** 当前前台包与其进入时间，用来判断是否还在开屏广告的窗口期内。 */
    private var foregroundSince = 0L

    private lateinit var powerManager: PowerManager

    /** 最近一次窗口状态变化时记录的 Activity，用于日志标注与规则匹配。 */
    @Volatile
    private var currentActivity: String? = null

    /**
     * 最近一次窗口切换时的前台包名。
     *
     * 扫描节拍器靠它判断"这一拍该扫谁"，**不需要**取根节点——那是一次跨进程调用，
     * 而绝大多数拍里前台都不在生效范围。窗口切换仍然会实时推一次事件把它刷新。
     */
    @Volatile
    private var currentPackage: String? = null

    /** 防止截图期间切到其他应用再切回来后，仅靠同名 Activity 误判为未切换。 */
    @Volatile
    private var windowGeneration = 0L

    /** 点击冷却。逻辑在 [ClickCooldown] 里，纯类，有单测。 */
    private val clickCooldown = ClickCooldown()

    /** 日志去重：同一个静止候选会被慢扫反复评估，不该反复写进日志。 */
    private var lastLogKey: String? = null
    private var lastLogAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()

        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        settings = GuardSettings(prefs)
        registry = ProtectionRegistry(this, prefs)
        log = ObservationLog(this)
        snapshotStore = SnapshotStore(this)
        finder = CloseButtonFinder()
        clickExecutor = ClickExecutor(this)
        neutralizer = AppNeutralizer(this)
        // 哨兵用**回退**准入而不是**强停**准入：它决定的是"要不要把用户拉回来"，
        // 那比"杀掉对方"轻一个量级，准入条件也不该一样严。见 ProtectionRegistry.canRollback。
        sentinel = Sentinel { target, externallyLaunched ->
            registry.canRollback(target, externallyLaunched)
        }
        ruleSet = RuleRepository.load(this)
        ocrEngine = OcrEngine()
        powerManager = getSystemService(PowerManager::class.java)

        // 行为保护名单涉及系统查询，放后台线程加载；加载完成前 canForceStop 一律返回 false
        registry.refreshBehaviorAsync(scope)

        // 下载监控由同一个长期绑定服务管理；启停始终服从当前开关。
        // 前台服务在 Android 8+ 必须常驻一条通知，而用户明确要求去掉那条通知——
        // 这两件事的职责本来就重叠：无障碍服务同样由系统长期绑定，而且**开机自动拉起**，
        // 比「打开过应用才会启动」的前台服务更可靠。
        if (::janitor.isInitialized) janitor.stop()
        janitor = DownloadJanitor(this, settings)
        janitor.syncWithSettings()

        // 扫描节拍器：扫描从"被无障碍事件叫起来"改成"自己排期"（2026-10-05）。
        // 语义、代价、以及为什么值得改，都写在 [tickOnce] 上。
        startTicker()

        instance = this
        // 带上 lastUpdateTime：排查"改了却没生效"时，这一条能直接证明
        // 当前跑的是哪个构建。2026-10-04 就卡在这个问题上——按代码推演该拦住的没拦住，
        // 而分不清是逻辑错了还是跑着旧包。
        val buildStamp = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName}@${info.lastUpdateTime}"
        }.getOrDefault("未知")
        Log.i(
            TAG,
            "无障碍服务已连接：构建=$buildStamp，观察模式=${settings.dryRun}，" +
                "生效范围=${settings.targetedPackages.size} 个应用，" +
                "规则 ${ruleSet.size} 条，自动强停=${settings.autoForceStop}，" +
                "OCR=${settings.ocrEnabled && ScreenshotCapturer.isSupported}"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!::settings.isInitialized) return
        if (!settings.enabled || settings.dryRun || !settings.autoRollback) {
            sentinel.disarm()
            DownloadJanitor.clearAdJump()
        }
        if (!settings.enabled || settings.dryRun || !settings.autoRollback || !settings.autoForceStop) {
            neutralizer.reset()
            pendingReturnTo = null
        }
        if (!settings.enabled) return

        val now = SystemClock.uptimeMillis()
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        // 记录前台包与进入时刻。开屏广告的窗口期就是从这里起算的。
        // `currentPackage` 还给扫描节拍器用：它按拍问"现在该扫谁"，
        // 走缓存就不必每拍都做一次跨进程的 rootInActiveWindow。
        if (isWindowChange) {
            windowGeneration++
            currentActivity = event.className?.toString()
            currentPackage = pkg
            foregroundSince = now
        }

        // ---- 廉价闸门：绝大多数事件在这里就该返回，后面每一步都是它数倍的开销 ----
        //
        // 强停流程进行中例外。2026-10-05 用广告样机实测：这个流程要跨好几个窗口
        // （应用信息页 → 系统确认弹窗），整个过程可能比哨兵的 3 秒窗口更长，
        // 被这道闸门挡掉之后状态机再也收不到事件，**界面就卡在应用信息页不动**。
        val sentinelArmed = sentinel.isArmed(now)
        // 迟到的跳转（3 秒窗口已过、但还在更宽的兜底窗口里）也要放进来，
        // 否则它连检查都进不去——见 [Sentinel.shouldRollbackLate]。
        val lateWindowOpen = !sentinelArmed && sentinel.wasArmedRecently(now)
        if (!neutralizer.isActive && !sentinelArmed && !lateWindowOpen &&
            !settings.isTargeted(pkg)
        ) {
            return
        }

        // 闸门 1：强停流程进行中，全权交给状态机
        if (neutralizer.isActive && (!settings.enabled || settings.dryRun || !settings.autoRollback || !settings.autoForceStop)) {
            neutralizer.reset(); pendingReturnTo = null
        }
        if (neutralizer.isActive) {
            neutralizer.onEvent(pkg, rootInActiveWindow, now)
            // 刚才是最后一步（点了「确定」，或超时放弃）——那时界面多半还停在
            // 系统的「应用信息」页上，把用户送回他原来那个应用。
            if (!neutralizer.isActive) returnToOriginIfPending()
            return
        }

        // 闸门 2：点击哨兵。必须在生效范围过滤之前——被广告拽过去的包
        // 几乎都不在生效范围内，先过滤就永远检测不到误跳。
        if (isWindowChange && (sentinelArmed || lateWindowOpen)) {
            // **还在原应用里时什么都不能做，尤其不能解除哨兵。**
            //
            // 见 [Sentinel.isStillInOrigin]：广告 SDK 会在宿主应用内部弹出自己的
            // Activity，那是一次窗口切换、包名却没变。把它当成"用户自己切的"而
            // disarm，等于在最该盯着的时候把哨兵撤了。
            if (!sentinel.isStillInOrigin(pkg)) {
                // 它是被外部拉起来的吗？这一条能把行为保护名单放宽一档——
                // 广告把你塞进某通讯应用的小程序时，某通讯应用确实是你天天用的应用，但那一刻是广告在动。
                //
                // **还要兜一种情况（2026-10-05 用样机复现出来的）**：广告跳过去的往往是
                // 单 Activity 的马甲包——它的**落地页就是它自己的桌面入口**，于是
                // `isExternallyLaunched` 永远判 false；再加上"被广告拽过去"也算前台用时，
                // 攒够 60 秒它就进了 L2 行为保护名单，**从此再也回退不动**，
                // 而它本来正是最该被回退的那一类。
                //
                // 补的判据是"**今天刚装上、又在我们点击后的窗口里冒出来**"——
                // 和 [Sentinel.shouldRollbackLate] 用的是同一个事实：广告的目标几乎都是
                // 它自己刚下载安装的包。它只放宽"把用户拉回来"，强停那关照旧（见 canForceStop）。
                val externallyLaunched = isExternallyLaunched(pkg, event.className?.toString()) ||
                    AppClassifier.isRecentlyInstalled(this, pkg, FRESH_INSTALL_DAYS)

                val allowed = if (sentinelArmed) {
                    sentinel.shouldRollback(pkg, now, externallyLaunched)
                } else {
                    // 迟到那一档：只认"今天刚装上的应用"——广告自己下载安装的马甲包。
                    // 浏览器和应用市场不算，那正是用户自己点链接会去的地方。
                    sentinel.shouldRollbackLate(
                        pkg,
                        now,
                        AppClassifier.isRecentlyInstalled(this, pkg, FRESH_INSTALL_DAYS),
                    )
                }
                if (allowed) {
                    rollback(pkg, now)
                    return
                }
                // 闸门没放行。**这条日志是排"为什么没保护我"的唯一线索**：
                // 没有它，事后只能看到"有候选、没回退"，分不清是哨兵没武装、窗口过期，
                // 还是准入把人挡住了。
                Log.i(
                    TAG,
                    "哨兵拦回退：原应用=${sentinel.originPackage()} → $pkg，" +
                        "外部拉起=$externallyLaunched，" +
                        "回退准入=${registry.canRollback(pkg, externallyLaunched)}，" +
                        "强停准入=${registry.canForceStop(pkg)}",
                )
                // 确实换了包，又不是该回退的目标（多半是用户自己切到别的应用了），
                // 哨兵任务结束
                sentinel.disarm()
            } else {
                // 还在**原应用内部**换了页：什么都不做，哨兵继续武装（见 [Sentinel.isStillInOrigin]）。
                //
                // **但要留一条记录。** "刚判定成广告、宿主应用内部就换了一页"正是摇一摇和
                // 点击穿透最常见的落点（广告落地页、WebView），而现在的策略是对它不动作。
                // 不动作有两种解释——"同一个应用里换页，认不出是不是广告"与
                // "这恰恰就是广告在动"——**只有先量出它多常发生，才谈得上决定要不要管**。
                recordInAppNavigationAfterAd(pkg, event.className?.toString())
            }
        }

        // 闸门 3：不处理硬保护的系统组件；不处理生效范围外的应用
        if (registry.isHardProtected(pkg)) return
        val inScope = settings.isTargeted(pkg)
        // 诊断也遵守用户选定范围，不读取未选应用的节点树。
        if (!inScope) return

        handleWindow(pkg, rootInActiveWindow, isWindowChange, now, inScope)
    }

    /**
     * 扫描节拍器的协程。启动/停止都走 [startTicker] / [stopTicker]——
     * **被中断或解绑时必须停掉**（2026-10-05 补）：系统把服务中断之后，
     * 我们不该继续按拍去取根节点、遍历节点树。
     */
    private var tickerJob: Job? = null

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                delay(tickOnce())
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * 扫描节拍器的一拍：跑一次扫描，返回"下一拍该等多久"。
     *
     * **为什么从事件驱动改成自己排期**（2026-10-05）：原来只在无障碍事件到达时才扫描，
     * 于是界面越活跃（列表滚动、动画、计数器）就被叫得越勤。量过：一个持续刷新的目标应用
     * 能让本应用烧到**单核 4%**，其中"每个事件过一次回调"占相当一部分。
     * 改完之后**扫描频率与界面活跃度无关**，只由 [ScanPolicy] 决定。
     *
     * 顺带补上一个覆盖漏洞：以前"没有事件就不扫"，**静止画面上已经存在的广告永远扫不到**。
     *
     * 窗口切换仍然走事件那条路（实时扫一次），开屏那 8 秒也仍然是 100ms 一拍——
     * 决定"关得掉关不掉"的正是它。
     */
    private suspend fun tickOnce(): Long {
        janitor.syncWithSettings()
        if (!settings.enabled || settings.dryRun || !settings.autoRollback) {
            sentinel.disarm()
            DownloadJanitor.clearAdJump()
        }

        // 强停流程进行中：那条路**必须**拿根节点（状态机要在设置页/确认弹窗上找按钮），
        // 而且它也要靠这一拍推进——这台 ROM 的确认弹窗不一定产生我们订阅得到的事件，
        // 光等事件会卡在设置页（2026-10-05 踩过一次同类坑：入口闸门把事件吃掉）。
        if (neutralizer.isActive && (!settings.enabled || settings.dryRun || !settings.autoRollback || !settings.autoForceStop)) {
            neutralizer.reset(); pendingReturnTo = null
        }
        if (!settings.enabled) return TICK_IDLE_MS
        if (neutralizer.isActive) {
            val now = SystemClock.uptimeMillis()
            val root = rootInActiveWindow
            neutralizer.onEvent(root?.packageName?.toString().orEmpty(), root, now)
            if (!neutralizer.isActive) returnToOriginIfPending()
            return ScanPolicy.FULL_THROTTLE_MS
        }

        if (!powerManager.isInteractive) return TICK_IDLE_MS

        // 前台包名走**缓存**，不在这里每拍都取根节点。
        // `rootInActiveWindow` 是一次跨进程调用，而绝大多数拍里前台都不在生效范围
        // （桌面、系统界面、没被选中的应用）——那些拍完全不需要根。
        // 实测：每 3 秒白取一次，会让待机开销从 3 ticks/60s 涨到 27。
        //
        // **但缓存为空时必须取一次把它填上**：服务重启（或开机）那一刻前台已经开着某个应用，
        // 如果之后一直没窗口切换，缓存就永远是空的，节拍器永远不扫——连开屏那 8 秒的
        // 100ms 全速扫描也没了。2026-10-05 实测踩到过：重装服务之后 20 秒内一条扫描日志都没有。
        var pkg = currentPackage
        var root: AccessibilityNodeInfo? = null
        if (pkg == null) {
            root = rootInActiveWindow ?: return TICK_IDLE_MS
            pkg = root.packageName?.toString() ?: return TICK_IDLE_MS
            currentPackage = pkg
        }
        if (pkg == packageName) return TICK_IDLE_MS
        if (registry.isHardProtected(pkg)) return TICK_IDLE_MS

        val inScope = settings.isTargeted(pkg)
        if (!inScope) return TICK_IDLE_MS

        val now = SystemClock.uptimeMillis()
        // 取根节点也是一次跨进程调用。2026-10-05 量过：扫描本身只有 4ms，
        // 所以"每拍取根"的成本值得盯着——这条计时就是为了别再靠猜。
        val fetchStart = SystemClock.uptimeMillis()
        if (root == null) root = rootInActiveWindow ?: return TICK_IDLE_MS
        if (settings.debugMode) {
            Log.d(TAG, "取根耗时 ${SystemClock.uptimeMillis() - fetchStart}ms（$pkg）")
        }
        handleWindow(pkg, root, isWindowChange = false, now = now, inScope = inScope)
        return nextTickDelay(now)
    }

    /**
     * 距上一次**真的扫过一遍**过了多久（毫秒）；一次都没扫过返回 null。
     *
     * 首页拿它回答"服务活着吗"之后的下一问：**它到底在扫吗**。
     * 这两种失败长得不一样——服务挂着、一拍都没扫的时候，界面上和正常运行完全一样
     * （2026-10-05 真出现过：前台包名缓存为空，节拍器就永远不扫）。见 [ScanFreshness]。
     *
     * 线程：`lastScanAt` 只由主线程的节拍器和事件回调写，首页也在主线程读，无需同步。
     */
    fun msSinceLastScan(): Long? =
        if (lastScanAt == 0L) null else SystemClock.uptimeMillis() - lastScanAt

    /**
     * 诊断用：把当前前台窗口树里的可见文案读出来。**只读，没有任何动作入口。**
     *
     * 给「能力自检」用。最要紧的一处是强停的**降级路径**：没有 Shizuku 时，强停要靠打开
     * 应用的「应用信息」页、再点上面那个「强行停止」按钮——而它在别的 ROM 上叫什么、
     * 在不在，只有真机看了才知道。自检会把那一页打开、读一遍、再回来。
     *
     * 它**故意不走进生效范围过滤**——要看的正是范围外的系统界面。
     * 这和 2026-10-03 那个 bug 的区别必须说清楚：那次是把"放行**观察**"顺手变成了
     * "放行**操作**"，服务开始点系统设置的搜索框；这一个从头到尾只有读取，
     * 而且只有本应用自己的前台界面调得到它。
     */
    fun probeVisibleTexts(): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val seen = LinkedHashSet<String>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (depth > PROBE_MAX_DEPTH) continue
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { seen += it }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { seen += it }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it to depth + 1) }
            }
        }
        return seen.toList()
    }

    /**
     * 诊断用：在屏幕坐标上注入一次点击。
     *
     * 返回的是 `dispatchGesture` **自己的**结果，而**它说 true 不等于真的点下去了**——
     * 2026-10-05 就栽在这上面（零长度路径：返回 true、日志一切正常、点击根本没发生）。
     * 所以自检不能只看这个返回值，得看**那个坐标上的界面有没有真的响应**。
     */
    fun probeTapAt(x: Int, y: Int): Boolean =
        if (::clickExecutor.isInitialized) clickExecutor.clickAt(x, y) else false

    /** 下一拍该等多久：跟着 [ScanPolicy] 的模式走，冷却期里别空转。 */
    private fun nextTickDelay(now: Long): Long {
        val sinceForeground = now - foregroundSince
        val sinceScan = now - lastScanAt
        return when (ScanPolicy.decide(isWindowChange = false, sinceForeground, sinceScan)) {
            ScanPolicy.Mode.FULL -> ScanPolicy.FULL_THROTTLE_MS
            ScanPolicy.Mode.RELAXED -> ScanPolicy.IDLE_THROTTLE_MS
            // 还在冷却里：算到"下一次该扫"的时刻再醒，别每 100ms 空转一次
            ScanPolicy.Mode.SKIP -> {
                val throttle =
                    if (sinceForeground < ScanPolicy.SPLASH_WINDOW_MS) {
                        ScanPolicy.FULL_THROTTLE_MS
                    } else {
                        ScanPolicy.IDLE_THROTTLE_MS
                    }
                (throttle - sinceScan).coerceAtLeast(TICK_MIN_MS)
            }
        }
    }

    private fun handleWindow(
        pkg: String,
        root: AccessibilityNodeInfo?,
        isWindowChange: Boolean,
        now: Long,
        inScope: Boolean,
    ) {
        // 扫描节奏：窗口切换永远全速；刚进入应用的几秒内是开屏广告的窗口期，
        // 也全速；其余时间降到一秒一次，只为兜住不换窗口直接盖上来应用内浮层。
        val mode = ScanPolicy.decide(
            isWindowChange = isWindowChange,
            msSinceForeground = now - foregroundSince,
            msSinceLastScan = now - lastScanAt,
        )
        if (mode == ScanPolicy.Mode.SKIP) return
        lastScanAt = now

        // 屏幕关着的时候没有广告可关，别白白遍历节点树
        if (!powerManager.isInteractive) return

        // 根节点由调用方传进来：事件那条路刚取过一次，节拍器那条也在同一拍里取过，
        // 在这里再取一次等于白多一次跨进程调用。
        if (root == null) return

        // 事件可能是过期的：窗口已经切走，rootInActiveWindow 拿到的是另一个界面。
        // 真机日志里出现过"事件说 autonavi，节点树却是桌面图标"的情况——
        // 照着那份树判定等于在错误的界面上找关闭按钮。
        val rootPackage = root.packageName?.toString()
        if (rootPackage != null && rootPackage != pkg) return

        val screen = screenRect()
        // 分段计时。挂在诊断模式后面：平时不产生任何开销，需要查"扫描为什么贵"时打开看。
        // 2026-10-05：最坏稳态的开销几乎全在扫描上，而扫描由"读树"和"打分"两段组成，
        // 先量清楚是哪一段，再决定优化谁——上一轮凭直觉砍节点树，白试一次。
        val readStart = SystemClock.uptimeMillis()
        val snapshot = NodeTreeReader.read(root) ?: return
        val findStart = SystemClock.uptimeMillis()
        val candidates = finder.find(snapshot, screen, now, pkg, currentActivity, ruleSet)
        if (settings.debugMode) {
            Log.d(
                TAG,
                "扫描耗时：读树 ${findStart - readStart}ms，打分 ${SystemClock.uptimeMillis() - findStart}ms，" +
                    "节点 ${snapshot.walk().count()}，候选 ${candidates.size}",
            )
        }
        val best = candidates.firstOrNull()

        // 内容变化事件只在出现"疑似"以上候选时记录，否则日志会被无意义事件淹没
        if (!isWindowChange && (best == null || best.score < SUSPECT_FLOOR)) return

        var decision = when {
            best == null -> DECISION_NO_CANDIDATE
            best.verdict == Verdict.CLICK -> DECISION_WOULD_CLICK
            best.verdict == Verdict.SUSPECT -> DECISION_SUSPECT_ONLY
            else -> DECISION_NO_CANDIDATE
        }
        var clickMethod: String? = null

        if (best != null && best.verdict == Verdict.CLICK) {
            if (!inScope) {
                // 保留动作前的范围检查；调用方只允许所选应用进入扫描。
                //
                // 少了这一层就是 2026-10-03 真机上发生的事——诊断模式一开，
                // 生效范围过滤被整体绕过，服务对着系统设置的搜索框
                // （android:id/search_close_btn 得 65 分）和输入法的「关闭」键
                // （OCR 得 80 分）连着点，用户当时正在设置里搜「无线」。
                decision = DECISION_OUT_OF_SCOPE
                Log.i(TAG, "生效范围外，只记不点 $pkg：${best.score}分")
            } else {
                // 分数够了，还要过最后一道闸门：有些候选的分数全部来自"它待在一个角落里"，
                // 而任何界面都有控件待在角落里。见 ClickGate。
                val gate = ClickGate.evaluate(
                    hasStrongEvidence = best.hasStrongEvidence,
                    inputMethodActive = isInputMethodActive(),
                    snapshot = best.snapshot,
                )

                if (gate != ClickGate.Reason.OK) {
                    // 降级：分数和理由照记进日志（那正是校准要看的），但不动手。
                    //
                    // **也不武装哨兵。** 哨兵的前提是"我们已经确认这是广告，它可能把用户
                    // 拽走"，而这里恰恰是我们自己判断"这多半不是广告"。照旧武装的话，
                    // 用户接下来自己切个应用就会被回退 + 强停——把一次正确的克制
                    // 变成一次真实的打扰。
                    decision = when (gate) {
                        ClickGate.Reason.INPUT_METHOD -> DECISION_HELD_INPUT_METHOD
                        else -> DECISION_HELD_NO_EVIDENCE
                    }
                    Log.i(TAG, "闸门拦下 $pkg（${gate.name}）：${best.score}分")
                } else if (settings.dryRun) {
                    // **纯观察：只记，不动手——也不武装哨兵。**
                    //
                    // 这里原来是先武装哨兵、再判观察模式，理由是"我们没点，但广告仍可能
                    // 因为用户误触或摇一摇把人拽走，同样需要回退"。那条理由本身站得住，
                    // 但它让「观察模式」名不副实：用户以为只是记录，手机却仍会被返回、
                    // 被强停。2026-10-05 的审计把它列成最影响信任的一条，用户拍板拆开——
                    // **观察模式从此覆盖所有动作入口**，不回退、不强停、不点。
                    //
                    // "只记录、但保留误跳保护"是另一件事，要用**独立开关**表达，
                    // 不该挤在同一个名字底下：一个布尔开关同时管两件事，正是这个项目
                    // 踩过的那类坑（见第七节「一个布尔开关同时管两件事」）。
                    Log.i(TAG, "[观察] $pkg 达标候选：${best.score}分")
                } else {
                    // 只有成功发起辅助点击且用户开启误跳回退时，才会武装哨兵。
                    if (isCoolingDown(pkg, now, best.snapshot.bounds)) {
                        decision = DECISION_COOLDOWN
                    } else {
                        val result = clickExecutor.click(root, best.path, best.snapshot.bounds, screen, expected = best.snapshot)
                        if (result.succeeded) {
                            armSentinel(pkg, now)
                            clickCooldown.record(pkg, now, best.snapshot.bounds)
                            clickMethod = result.method.name
                            decision = DECISION_CLICKED
                            Log.i(TAG, "已点击 $pkg：${best.score}分 via ${result.method}")
                        } else {
                            decision = DECISION_CLICK_FAILED
                            Log.w(TAG, "点击失败 $pkg：${best.score}分")
                        }
                    }
                }
            }
        }

        // L3 兜底：节点树里没有可用候选时，截图做文字识别。
        // 只在 L1/L2 都空手、且处于开屏窗口期或有新窗口弹出时才走这条路——
        // 坐在应用里每次慢扫都截一次图的话，耗电会非常难看。
        //
        // 除了"没有可用候选"，还有一种**更值得截图**的情况：分数够了、
        // 但证据全部来自位置——那正是 `ClickGate` 会拦下的形态，也正是
        // "画出来的关闭按钮"的样子（几何像、树里没有文字）。
        // 2026-10-05 实测踩到：某广告的「跳过」是画在左下角的，几何上拿了 65 分，
        // 闸门按规矩拦下，而 OCR 因为"分数不低"根本没被叫起来，广告一次都没点掉。
        // **几何给出位置、OCR 给出文字，合起来才是证据**——这正是闸门那条
        // "位置不是证据"该走的正道（另一条正道是规则库）。
        val heldByGate = best != null &&
            best.score >= ConfidenceScorer.CLICK_THRESHOLD &&
            !best.hasStrongEvidence
        val treeFoundNothing = best == null || best.score < SUSPECT_FLOOR
        val worthOcr = treeFoundNothing || heldByGate

        // 什么时机值得截图。两条路：
        //   · 窗口切换 / 刚进应用的 8 秒——原来就有，免费（那本来就是全速扫描的时候）
        //   · **低频兜底**：树里没有任何可用候选时，每 20 秒最多一次。
        //     2026-10-05 加的：有一类广告两个条件都不占——画在应用自己的 SurfaceView 上、
        //     又不换窗口（真机实测：某地图应用那一帧的树只剩整屏容器）。
        //     这是拿"能救回这类广告"换"常驻时的耗电"，所以间隔很粗，见 [ScanPolicy]。
        val timingOk = ScanPolicy.allowsOcr(isWindowChange, now - foregroundSince) ||
            now - lastOcrAt >= ScanPolicy.RELAXED_OCR_INTERVAL_MS

        val ocrWorthTrying = worthOcr && timingOk &&
            settings.ocrEnabled &&
            ScreenshotCapturer.isSupported &&
            !isOcrCoolingDown(pkg, now)
        if (ocrWorthTrying) {
            lastOcrPackage = pkg
            lastOcrAt = now
            scope.launch { runOcr(pkg, screen) }
        }

        // 去重判定。真机日志里出现过同一个候选（同位置、同分数、同判定）在 3 秒内
        // 被记录 20 次——慢扫节奏会反复评估同一个静止的浮层，把日志刷得没法看。
        //
        // 2026-10-05 把**纯几何候选**的窗口拉到 60 秒。量过一遍：某次分析里 328 条"疑似"
        // 有 285 条是同一批**常驻**图标（侧边栏、工具栏、应用自己的信息图标，有的重复 200 次），
        // 按 3 秒一次记就是每小时上千条——而日志正是长线分析唯一的输入。
        // **有自带证据的候选仍然按 3 秒记**：那是可能动手的对象，要看它每一刻的样子。
        // 真实广告的关闭叉坐标各不相同，不会因为这个被吞掉。
        val logKey = buildString {
            append(pkg).append('|').append(decision).append('|')
            append(best?.path).append('|').append(best?.snapshot?.bounds).append('|').append(best?.score)
        }
        val dedupWindow = if (best?.hasStrongEvidence == true) LOG_DEDUP_MS else LOG_DEDUP_QUIET_MS
        val duplicate = logKey == lastLogKey && now - lastLogAt < dedupWindow

        // 诊断模式：抓完整节点树。
        //
        // 不能只在窗口切换时抓——真机日志证明那正好错过最需要的一刻：
        // 广告的关闭按钮是在窗口切换**之后**才异步渲染出来的，
        // 结果某个浏览器 `:id/closeIv`（开发者亲手命名为 close 的那个控件）一次都没被抓到。
        // 所以只要发现了疑似以上的候选，也抓一份。
        val worthDumping = isWindowChange || (best != null && best.score >= SUSPECT_FLOOR)
        if (settings.debugMode && worthDumping && !duplicate) {
            dumpTree(pkg, snapshot, screen)
        }

        if (!duplicate) {
            lastLogKey = logKey
            lastLogAt = now
            log.record(
                ObservationEntry(
                    timestamp = System.currentTimeMillis(),
                    packageName = pkg,
                    activityName = currentActivity,
                    screenWidth = screen.width,
                    screenHeight = screen.height,
                    candidates = candidates.take(MAX_LOGGED_CANDIDATES),
                    decision = decision,
                    dryRun = settings.dryRun,
                    clickMethod = clickMethod,
                )
            )
        }
    }

    /**
     * 记一条"判定成广告之后，界面在**原应用内部**换了一页"。
     *
     * 它**不是判定，也不触发任何动作**——纯粹是把一个说不上话的问题变成能被数据回答的问题：
     * 摇一摇/点击穿透把人送到宿主应用自己的落地页时，现在的策略是"什么都不做"，
     * 而这条策略是对是错，取决于这种事到底多常发生。
     *
     * 写进**观察日志**而不是只打 logcat：这台 ROM 的 logcat 缓冲存不下 1 秒，
     * 事后 dump 什么都抓不到（见第七节）。
     */
    private fun recordInAppNavigationAfterAd(pkg: String, activity: String?) {
        val screen = screenRect()
        log.record(
            ObservationEntry(
                timestamp = System.currentTimeMillis(),
                packageName = pkg,
                activityName = activity,
                screenWidth = screen.width,
                screenHeight = screen.height,
                candidates = emptyList(),
                decision = DECISION_IN_APP_NAV_AFTER_AD,
                dryRun = settings.dryRun,
            )
        )
    }

    /**
     * 武装哨兵。
     *
     * 窗口固定用默认值（[Sentinel.WINDOW_MS]，3 秒）：**观察模式下压根走不到这里**——
     * 纯观察不回退、不强停，那个"要留出人的反应时间"的宽窗口随之作废
     * （来历记在 [Sentinel.OBSERVE_WINDOW_MS]）。
     */
    private fun armSentinel(pkg: String, now: Long) {
        if (!settings.enabled || settings.dryRun || !settings.autoRollback) return
        sentinel.arm(pkg, now)
        // 武装本身要留痕。"为什么没保护我" 这个问题如果没有这一条，
        // 事后只能看到"有候选、没回退"，分不清是没武装还是准入拒绝。
        Log.i(TAG, "哨兵武装：$pkg，窗口 ${Sentinel.WINDOW_MS}ms")
    }

    /**
     * 误跳回退：把界面退回原应用，必要时强停被跳转到的应用。
     *
     * 走到这里的包已经通过了 [Sentinel.shouldRollback] 的判定，
     * 即"不在保护名单、且看起来像广告目标"——用户自己常用的软件不会到这里。
     */
    private fun rollback(jumpedTo: String, now: Long) {
        if (!settings.enabled || settings.dryRun || !settings.autoRollback) {
            sentinel.disarm()
            DownloadJanitor.clearAdJump()
            return
        }
        // 送用户回去要用的"原应用"必须先取走——disarm 之后哨兵里就没有它了
        val origin = sentinel.originPackage()
        sentinel.disarm()
        Log.w(TAG, "检测到误跳 → $jumpedTo，执行回退（原应用 $origin）")

        // 刚才点的那一下被证明是错的：那不是关闭按钮，是个会把人带走的陷阱。
        // 记下来，之后十分钟不再点同一个位置——否则会变成
        // "点 → 被带走 → 回退 → 再点"的循环（2026-10-05 用样机复现过）。
        clickCooldown.markLastClickAsJumping(now)

        // 打上时间戳：隔离区只处理这个时间点之后出现的安装包
        DownloadJanitor.noteAdJump(System.currentTimeMillis())

        val screen = screenRect()
        log.record(
            ObservationEntry(
                timestamp = System.currentTimeMillis(),
                packageName = jumpedTo,
                activityName = currentActivity,
                screenWidth = screen.width,
                screenHeight = screen.height,
                candidates = emptyList(),
                decision = DECISION_ROLLBACK,
                dryRun = settings.dryRun,
            )
        )

        scope.launch {
            // 1. 先把界面从广告页退出来。很多广告会吃掉第一次返回，所以按两次
            repeat(BACK_ATTEMPTS) {
                if (!settings.enabled || settings.dryRun || !settings.autoRollback) return@launch
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(BACK_INTERVAL_MS)
            }

            if (!settings.enabled || settings.dryRun || !settings.autoRollback) return@launch

            // 2. 能不能直接把对方清掉，还要单独过一道**强停**准入。
            //
            //    哨兵用的是**回退**准入，它比强停准入松（见
            //    [cn.adcalm.guard.core.ProtectionRegistry.canRollback]）。
            //    松出来的那部分只允许"按返回 + 送回原应用"，**不允许杀别人**——
            //    这条边界不能糊。
            val canStop = settings.enabled && !settings.dryRun && settings.autoForceStop && registry.canForceStop(jumpedTo)
            if (!canStop) {
                Log.i(TAG, "只回退不强停 $jumpedTo（自动强停开关=${settings.autoForceStop}）")
                // 只把界面退出来，再送用户回原应用
                if (foregroundPackage() == jumpedTo) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    delay(BACK_INTERVAL_MS)
                }
                returnToOrigin(origin)
                return@launch
            }

            // 3. 强停广告应用，清掉它的后台。
            //
            //    **先强停，不再无脑回桌面。** 强停本身会把下层的任务带回前台，
            //    而那个任务往往正是用户原来那个应用——他就是从那里被拽过去的。
            //    先按「桌面」的老写法反而把桌面压到顶上，人还得自己找回去。
            if (ShizukuShell.forceStop(jumpedTo)) {
                Log.i(TAG, "已通过 Shizuku 强停 $jumpedTo")
                delay(RETURN_SETTLE_MS)
                returnToOrigin(origin)
                return@launch
            }

            // 4. 没装/没授权 Shizuku，退回无障碍路径：打开应用信息页 → 点强行停止
            //    → 处理确认弹窗。无论界面是否已经退回原应用，都要强停对方——
            //    它很可能已经在后台开始下载了。
            //
            //    这条路是异步的（界面归状态机管），所以返回原应用要等它走完，
            //    先把目的地记下来，见 [returnToOriginIfPending]。
            pendingReturnTo = origin
            neutralizer.start(jumpedTo, SystemClock.uptimeMillis())
        }
    }

    /**
     * 强停状态机刚走完，把用户送回原应用。
     *
     * 只在无 Shizuku 的那条路上用得上：强停走的是 UI 自动化，会把界面带到
     * 「应用信息」页，状态机结束时用户还留在那个系统设置页上。
     * 有 Shizuku 时不等这条——那条路在上面的协程里直接就把人送回去了。
     */
    private fun returnToOriginIfPending() {
        val origin = pendingReturnTo ?: return
        pendingReturnTo = null
        scope.launch {
            delay(RETURN_SETTLE_MS)
            returnToOrigin(origin)
        }
    }


    /**
     * 把用户送回他原本在用的那个应用。
     *
     * 光把广告应用清掉是不够的——用户被拽走之前是在某个应用里，回退应该让他回到那里。
     * 原来的流程止步于「回桌面」，人得自己重新点开刚才那个软件，那不是回退，那是清理现场。
     *
     * @param origin 哨兵武装时所在的包；null 表示不知道（服务刚起来、或已被 disarm），
     *               这时什么都不做——宁可留在原处，也不要猜一个应用拉起来
     */
    private fun returnToOrigin(origin: String?) {
        if (!settings.enabled || settings.dryRun || !settings.autoRollback) return
        if (!settings.returnToOrigin) return
        if (origin.isNullOrEmpty()) return

        // 用户自己已经回去了就别再拉一次——那会把他正在做的事打断
        if (foregroundPackage() == origin) return

        // 包已卸载、或根本没有可启动的入口（纯服务包）时放弃。
        // 清单里申请了 QUERY_ALL_PACKAGES，所以这里查得到任意应用的启动入口。
        val intent = runCatching { packageManager.getLaunchIntentForPackage(origin) }.getOrNull()
        val activity = intent?.component?.className
        if (activity == null) {
            Log.d(TAG, "取不到 $origin 的启动入口，放弃返回")
            return
        }

        if (ShizukuShell.startApp(origin, activity)) {
            Log.i(TAG, "已把用户送回 $origin")
            return
        }

        // 没有 Shizuku 时退化：直接发启动 Intent。
        //
        // Android 10+ 对后台启动 Activity 有硬限制，而本应用的无障碍服务**不在豁免之列**，
        // 所以这一步经常会被系统悄悄拦掉——拦掉了 `startActivity` 也不抛异常，
        // 因此这里不声称成功，只如实记一条日志。不做重试，也不做任何兜底拼接。
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.i(TAG, "后台启动被系统拦下，未能把用户送回 $origin") }
    }

    /** 无 Shizuku 时，等强停状态机走完之后要送回哪个包。见 [returnToOriginIfPending]。 */
    private var pendingReturnTo: String? = null

    /**
     * L3：截屏 + 离线文字识别，找节点树里看不见的关闭按钮。
     *
     * 针对的是"画出来的"关闭按钮——WebView 渲染的、Canvas 自绘的，
     * 它们在节点树里要么不存在，要么是个没有文本、没有 id 的容器。
     *
     * 判定用的是 [OcrCloseScorer]，与 L2 共用同一条 65 分线和同一套关闭词表。
     */
    private suspend fun runOcr(pkg: String, screen: RectSnapshot) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        val bitmap = ScreenshotCapturer.capture(this)
        if (bitmap == null) {
            Log.d(TAG, "OCR：截屏失败 $pkg")
            return
        }

        // 记下"这一帧是哪个界面"。点下去之前要拿它复核一次——见 [OcrFreshness]。
        // 这里用的是截屏那一拍的 Activity，不是调用方那一拍的：两者通常相同，
        // 但截图才是坐标的来源，以它为准。
        val shot = OcrFreshness.Identity(pkg, currentActivity, screen)

        // 截图分辨率未必等于屏幕分辨率，算好缩放比用来把 OCR 坐标换算回屏幕坐标
        val bitmapWidth = bitmap.width
        val bitmapHeight = bitmap.height
        val rawBlocks = runCatching { ocrEngine.recognize(bitmap) }.getOrDefault(emptyList())
        bitmap.recycle()

        if (bitmapWidth <= 0 || bitmapHeight <= 0) return
        val scaleX = screen.width.toDouble() / bitmapWidth
        val scaleY = screen.height.toDouble() / bitmapHeight

        val blocks = rawBlocks.map { raw ->
            OcrBlock(
                text = raw.text,
                bounds = RectSnapshot(
                    (raw.rect.left * scaleX).toInt(),
                    (raw.rect.top * scaleY).toInt(),
                    (raw.rect.right * scaleX).toInt(),
                    (raw.rect.bottom * scaleY).toInt(),
                ),
            )
        }

        val candidate = OcrCloseScorer.evaluate(blocks, screen)
        if (candidate == null) {
            // 文本块**连内容一起**打出来。
            // 「无匹配」这三个字没法拿来找原因：到底是没认出来、被尺寸规则挡掉了，
            // 还是被并进了整行（跳过按钮压在广告文案上时，ML Kit 很容易把两者并成一条）。
            // 2026-10-05 就卡在这一步——只报块数，等于什么都没说。
            Log.d(
                TAG,
                "OCR：无匹配 $pkg（截图 ${bitmapWidth}x$bitmapHeight，屏幕 ${screen.width}x${screen.height}，" +
                    "识别到 ${blocks.size} 块）：" +
                    blocks.joinToString(" | ") { "'${it.text}'${it.bounds}" },
            )
            return
        }

        val now = SystemClock.uptimeMillis()

        // 和节点树路径同一条规矩：生效范围外只记不点。
        // OCR 走的是按坐标的手势点击，点错位置比点错节点更没法挽回。
        if (!settings.isTargeted(pkg)) {
            Log.i(TAG, "生效范围外，OCR 只记不点 $pkg：${candidate.score}分")
            recordOcr(pkg, screen, candidate, DECISION_OUT_OF_SCOPE, null)
            return
        }

        // 输入法窗口在前台时一律不点。这条对 OCR 路径尤其要紧：
        // 2026-10-03 的真机日志里，OCR 把**输入法的「关闭」键**认成了关闭按钮，得了 80 分。
        if (isInputMethodActive()) {
            Log.i(TAG, "输入法在前台，OCR 不点 $pkg：${candidate.score}分")
            recordOcr(pkg, screen, candidate, DECISION_HELD_INPUT_METHOD, null)
            return
        }

        if (!settings.enabled || !settings.ocrEnabled || !settings.isTargeted(pkg)) return
        if (settings.dryRun) {
            Log.i(TAG, "[观察] OCR 命中：${candidate.score}分")
            recordOcr(pkg, screen, candidate, DECISION_OCR_WOULD_CLICK, null)
            return
        }
        if (isCoolingDown(pkg, now, candidate.bounds)) {
            recordOcr(pkg, screen, candidate, DECISION_COOLDOWN, null)
            return
        }

        // 从截屏到这里隔着一次 ML Kit 离线识别（几百毫秒），用户完全可能已经翻页了。
        // 节点树那条路靠 path + bounds 回去找同一个节点，界面换了就找不到、点空而已；
        // **OCR 只有坐标，点哪儿都算"成功"**——所以点之前自己复核一次界面身份。
        val nowIdentity = OcrFreshness.Identity(
            pkg = foregroundPackage().orEmpty(),
            activity = currentActivity,
            screen = screenRect(),
        )
        if (!OcrFreshness.isSameScreen(shot, nowIdentity)) {
            Log.i(
                TAG,
                "OCR 结果已过期，不点 $pkg：截图时 ${shot.pkg}/${shot.activity}，" +
                    "现在 ${nowIdentity.pkg}/${nowIdentity.activity}",
            )
            recordOcr(pkg, screen, candidate, DECISION_OCR_STALE, null)
            return
        }

        if (!settings.enabled || settings.dryRun || !settings.ocrEnabled || !settings.isTargeted(pkg)) return
        // OCR 只接受明确广告专用文案，屏幕身份复核后才允许手势。
        if (clickExecutor.clickAt(candidate.clickX, candidate.clickY)) {
            clickCooldown.record(pkg, now, candidate.bounds)
            armSentinel(pkg, now)
            Log.i(TAG, "OCR 已点击 $pkg：${candidate.score}分")
            recordOcr(pkg, screen, candidate, DECISION_OCR_CLICKED, "GESTURE")
        } else {
            recordOcr(pkg, screen, candidate, DECISION_CLICK_FAILED, null)
        }
    }

    private fun isOcrCoolingDown(pkg: String, now: Long): Boolean =
        pkg == lastOcrPackage && now - lastOcrAt < OCR_COOLDOWN_MS

    /** 把 OCR 结果包成 [Candidate] 写日志——格式与节点树路径保持一致，便于同表比对。 */
    private fun recordOcr(
        pkg: String,
        screen: RectSnapshot,
        candidate: OcrCandidate,
        decision: String,
        clickMethod: String?,
    ) {
        val asCandidate = Candidate(
            path = emptyList(),
            snapshot = NodeSnapshot(
                text = candidate.text,
                className = "ocr",
                bounds = candidate.bounds,
                clickable = true,
            ),
            score = candidate.score,
            reasons = candidate.reasons.map { reason ->
                val parts = reason.split(":", limit = 2)
                ScoreReason(parts.getOrNull(0)?.toIntOrNull() ?: 0, parts.getOrNull(1) ?: reason)
            },
            verdict = Verdict.CLICK,
            // OCR 候选的文本本身就是识别出来的关闭文案（OcrCloseScorer 只放行
            // 完整匹配关闭词表的短文案），所以它天然带证据。
            hasStrongEvidence = true,
        )

        log.record(
            ObservationEntry(
                timestamp = System.currentTimeMillis(),
                packageName = pkg,
                activityName = currentActivity,
                screenWidth = screen.width,
                screenHeight = screen.height,
                candidates = listOf(asCandidate),
                decision = decision,
                dryRun = settings.dryRun,
                clickMethod = clickMethod,
            )
        )
    }

    private fun foregroundPackage(): String? =
        rootInActiveWindow?.packageName?.toString()

    /**
     * 这个包是不是被外部拉起来的。
     *
     * 判据：它进来的界面**不是自己的桌面入口**。
     *
     * 2026-10-04 实测的对照：广告把用户塞进某应用的小程序时，前台界面是一个
     * **外部 scheme 的桩 Activity**（类名里带 `stub`、`scheme` 这类词）；
     * 而用户自己点图标打开时，前台是那个应用的 **Launcher 入口**。
     * 两者一眼分得开，而且这个判据不依赖任何应用特例，对别的应用同样成立。
     *
     * 它换来的放宽只有一档：**行为保护名单让路**，好让广告把用户塞进某通讯应用这类
     * 他天天在用的应用时，仍然能把人拉回来。**不影响强停**——某通讯应用可以被"退出去并送回原处"，
     * 但永远不会被强停（见 [cn.adcalm.guard.core.ProtectionRegistry.canForceStop]）。
     *
     * 取不到桌面入口（包已卸载、或根本没有启动入口）时返回 false：判不出来就**不放宽**。
     * 宁可少拉一次，也不要凭一个拿不准的信号去动用户的某通讯应用。
     */
    private fun isExternallyLaunched(pkg: String, activity: String?): Boolean {
        if (activity.isNullOrEmpty()) return false
        val launcher = runCatching {
            packageManager.getLaunchIntentForPackage(pkg)?.component?.className
        }.getOrNull() ?: return false
        return activity != launcher
    }

    /**
     * 输入法窗口是不是正显示着。
     *
     * 用 [getWindows] 的实时窗口列表判断，而不是看 [currentActivity]。
     * 后者只在窗口切换时更新，输入法收起之后它还停在 `SoftInputWindow`，
     * 会把"不能点"一直挂下去——那等于把整个工具关掉了。
     * 窗口列表反映的是当下真实存在的窗口，输入法一收起它就不在列表里了。
     *
     * 这条闸门的作用见 [ClickGate.Reason.INPUT_METHOD]：
     * 用户正在打字时点到任何东西都是打扰，而关闭按钮不可能长在输入法窗口里。
     *
     * 只在候选真的达到点击线时才会被调用（事件热路径上不该做窗口枚举）。
     */
    private fun isInputMethodActive(): Boolean {
        val wins = runCatching { windows }.getOrNull() ?: return false
        return wins.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }

    /**
     * 诊断模式：把当前窗口的完整节点树写进日志。
     *
     * 开启后才能在本机查看完整结构；默认日志只有候选摘要，
     * 看不到它们的上下文（属于哪个广告 SDK 容器、有哪些兄弟节点）。
     */
    private fun dumpTree(pkg: String, root: cn.adcalm.guard.model.NodeSnapshot, screen: RectSnapshot) {
        val nodes = root.walk().take(MAX_DUMP_NODES).mapNotNull { node ->
            if (!node.bounds.isValid) return@mapNotNull null
            org.json.JSONObject().apply {
                put("id", node.viewId ?: "")
                put("text", node.text ?: "")
                put("desc", node.contentDescription ?: "")
                put("cls", node.className ?: "")
                put("clickable", node.clickable)
                put(
                    "bounds",
                    "[${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}]"
                )
                // 兄弟的文案（有才写）。**打分已经不看它了**（那条「紧邻广告标识」的规则
                // 2026-10-05 撤掉），留着是因为当初撤它的依据就是这个字段：树转储本来就是为
                // "补规则时能看清上下文"存在的，不落盘就没法离线复核任何兄弟邻接的判据。
                if (node.siblingTexts.isNotEmpty()) {
                    put("sibs", org.json.JSONArray(node.siblingTexts))
                }
            }
        }.toList()

        // 时间戳同时决定快照文件名：日志必须同步写完，图随后异步补上。
        val stamp = System.currentTimeMillis()
        val activity = currentActivity
        val generation = windowGeneration
        val snapshotName = snapshotStore.nameFor(stamp).takeIf {
            isExpectedDiagnosticScreen(pkg, activity, generation) && reserveSnapshotSlot()
        }

        log.record(
            ObservationEntry(
                timestamp = stamp,
                packageName = pkg,
                activityName = activity,
                screenWidth = screen.width,
                screenHeight = screen.height,
                candidates = emptyList(),
                decision = DECISION_TREE_DUMP,
                dryRun = settings.dryRun,
                treeDump = org.json.JSONArray(nodes).toString(),
                snapshotName = snapshotName,
            )
        )

        if (snapshotName != null) captureSnapshot(snapshotName, pkg, activity, generation)
    }

    /**
     * 占一个截屏名额：返回 true 表示这次抓、并且位已经占好。
     *
     * 节流是必须的，不是保守起见。`takeScreenshot` 自身有调用频率限制，
     * 连着重试只会失败；而且它和 OCR 那条路共用同一个截屏通道，
     * 不设限的话开屏那几秒里两边会互相把对方的截屏挤掉。
     */
    private fun reserveSnapshotSlot(): Boolean {
        if (!ScreenshotCapturer.isSupported) return false
        if (snapshotInFlight) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastSnapshotAt < SNAPSHOT_MIN_INTERVAL_MS) return false
        snapshotInFlight = true
        lastSnapshotAt = now
        return true
    }

    /**
     * 抓一张快照落盘。
     *
     * 错误一律吞掉：抓快照是诊断辅助，它失败了不该影响识别和点击这条主链路。
     * 对应的图不存在时，日志里那条记录只是少一张配图，不会错位——文件名是时间戳，
     * 每条记录各自唯一。
     */
    private fun isExpectedDiagnosticScreen(pkg: String, activity: String?, generation: Long): Boolean = runCatching {
        instance === this && settings.enabled && settings.debugMode && settings.isTargeted(pkg) &&
            activity != null && currentActivity == activity && currentPackage == pkg &&
            windowGeneration == generation && foregroundPackage() == pkg
    }.getOrDefault(false)

    private fun captureSnapshot(name: String, expectedPackage: String, expectedActivity: String?, generation: Long) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            snapshotInFlight = false
            return
        }
        scope.launch {
            try {
                if (!isExpectedDiagnosticScreen(expectedPackage, expectedActivity, generation)) return@launch
                val bitmap = ScreenshotCapturer.capture(this@AdCalmAccessibilityService) ?: return@launch
                try {
                    if (!isExpectedDiagnosticScreen(expectedPackage, expectedActivity, generation)) return@launch
                    withContext(Dispatchers.IO) {
                        snapshotStore.save(bitmap, name) {
                            isExpectedDiagnosticScreen(expectedPackage, expectedActivity, generation)
                        }
                    }
                } finally { bitmap.recycle() }
            } finally {
                snapshotInFlight = false
            }
        }
    }

    private fun isCoolingDown(pkg: String, now: Long, bounds: RectSnapshot): Boolean =
        !clickCooldown.allows(pkg, now, bounds)

    private fun screenRect(): RectSnapshot {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            RectSnapshot(bounds.left, bounds.top, bounds.right, bounds.bottom)
        } else {
            val dm = resources.displayMetrics
            RectSnapshot(0, 0, dm.widthPixels, dm.heightPixels)
        }
    }

    override fun onInterrupt() {
        Log.i(TAG, "无障碍服务被中断")
        // 被中断之后系统随时可能解绑，别再按拍取根节点、遍历节点树
        stopTicker()
        if (::janitor.isInitialized) janitor.stop()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        stopTicker()
        if (::janitor.isInitialized) janitor.stop()
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        // 这两个都是 lateinit，只在 onServiceConnected 里赋值。
        // 服务从没连上过就被停掉时它们还是未初始化，直接访问会抛
        // UninitializedPropertyAccessException —— 而且是在 onDestroy 里，
        // 表现为「停止服务时崩溃」。真机日志里抓到过一次（2026-10-04 20:24）。
        runCatching { janitor.stop() }
        if (::ocrEngine.isInitialized) runCatching { ocrEngine.close() }
        super.onDestroy()
    }

    companion object {
        const val TAG = "AdCalm"
        const val PREFS_NAME = "adcalm"

        /** OCR 是截屏 + 识别，两次之间必须拉开距离，否则耗电会很难看。 */
        private const val OCR_COOLDOWN_MS = 1_500L

        /** 节拍器"没事可做"时的间隔（服务被关、屏幕关着、前台不在生效范围里）。 */
        private const val TICK_IDLE_MS = 3_000L

        /** 节拍器最小间隔，防止冷却期算成 0 时空转。 */
        private const val TICK_MIN_MS = 50L

        /**
         * 迟到窗口里判定"刚装上不久"的天数。
         *
         * 取 1 天：广告"下载即装"之后的跳转就发生在当下，而用户自己隔了一天才打开的新应用
         * 也基本不会恰好落在我们点过广告之后的十几秒里。
         */
        private const val FRESH_INSTALL_DAYS = 1L
        /** 同一个候选在这个时间窗内不重复写日志。 */
        private const val LOG_DEDUP_MS = 3_000L

        /**
         * 纯几何候选（没有自带证据）的重记间隔。
         *
         * 常驻的导航栏/工具栏图标会一直重复出现，按 3 秒记会淹没日志。
         * 它们本来也不该被点（真机数据：纯几何点击 16 次里 15 次点错），
         * 记一次足够说明"我见过它"。
         */
        private const val LOG_DEDUP_QUIET_MS = 60_000L
        private const val SUSPECT_FLOOR = 40
        private const val MAX_LOGGED_CANDIDATES = 8
        private const val MAX_DUMP_NODES = 300

        /** [probeVisibleTexts] 的遍历深度上限，防止异常深的树把「能力自检」卡住。 */
        private const val PROBE_MAX_DEPTH = 40
        private const val BACK_ATTEMPTS = 2
        private const val BACK_INTERVAL_MS = 350L

        /**
         * 强停之后、把用户送回原应用之前的等待。
         *
         * 强停是异步生效的：命令返回时进程可能还没退干净，前台包名也还没切走。
         * 立刻就去判断"前台是不是原应用"会读到一个正在变的状态，
         * 于是既可能白拉一次，也可能该拉的时候以为不用拉。
         */
        private const val RETURN_SETTLE_MS = 400L

        /**
         * 两张诊断快照之间的最小间隔。
         *
         * 比 OCR 的冷却还宽一点：开屏那几秒窗口会连着报好几次窗口变化，
         * 每次都截屏既费电又会被系统限流，抓到的反而更少。
         */
        private const val SNAPSHOT_MIN_INTERVAL_MS = 1_200L

        private const val DECISION_NO_CANDIDATE = "NO_CANDIDATE"
        private const val DECISION_SUSPECT_ONLY = "SUSPECT_ONLY"
        private const val DECISION_WOULD_CLICK = "WOULD_CLICK"
        private const val DECISION_CLICKED = "CLICKED"
        private const val DECISION_CLICK_FAILED = "CLICK_FAILED"
        private const val DECISION_COOLDOWN = "COOLDOWN"
        private const val DECISION_ROLLBACK = "ROLLBACK"
        private const val DECISION_TREE_DUMP = "TREE_DUMP"
        private const val DECISION_OCR_CLICKED = "OCR_CLICKED"
        private const val DECISION_OCR_WOULD_CLICK = "OCR_WOULD_CLICK"

        /**
         * 候选达标了，但这个包不在生效范围内，所以没点。
         *
         * 异步 OCR 结束前用户取消选择等情况会出现；范围外始终不得执行动作。
         * 单列一个值而不是复用 WOULD_CLICK，是为了让日志能一眼区分
         * 「观察模式下本该点」和「压根不该点」。
         */
        private const val DECISION_OUT_OF_SCOPE = "OUT_OF_SCOPE"

        /**
         * 分数达标，但候选只有位置证据、当前又不是开屏语境，所以没点。
         *
         * 与 [DECISION_OUT_OF_SCOPE] 的区别：那个是"不归我管"，这个是"归我管、
         * 但我认定它多半不是广告"。分开记才能在日志里看出这条闸门拦下了什么。
         */
        private const val DECISION_HELD_NO_EVIDENCE = "HELD_NO_EVIDENCE"

        /** 分数达标，但输入法窗口在前台（用户正在打字），所以没点。 */
        private const val DECISION_HELD_INPUT_METHOD = "HELD_INPUT_METHOD"

        /**
         * OCR 认出了关闭文案，但**从那帧截图到这一刻，界面已经换了**，所以没点。
         *
         * 截图是异步的（截屏 → 离线识别 → 注入坐标，中间几百毫秒），而 OCR 手里只有一个
         * 坐标、没有节点可依托——点错就是点在别的界面上。所以点之前复核一次界面身份，
         * 见 [cn.adcalm.guard.core.OcrFreshness]。
         *
         * 单列一个值是为了让它能在日志里被数出来：这条如果频繁出现，说明 OCR 的往返太慢，
         * 该去优化链路（或者干脆别在这类界面上启动 OCR），而不是放宽判据。
         */
        private const val DECISION_OCR_STALE = "OCR_STALE"

        /**
         * 刚判定成广告，界面就在**原应用内部**换了一页（没有换包），所以什么都没做。
         *
         * 单列一个值是为了让它**能被数出来**：摇一摇和点击穿透最常见的落点就是
         * 宿主应用自己的落地页/WebView，而现在的策略是不动作。这个数决定那条策略
         * 要不要改——见 [recordInAppNavigationAfterAd]。
         * 与 [DECISION_ROLLBACK] 的区别：那个是"换了包、我们动手了"，这个是"没换包、我们没动"。
         */
        private const val DECISION_IN_APP_NAV_AFTER_AD = "IN_APP_NAV_AFTER_AD"

        /** 供界面判断服务是否存活，不参与任何判定逻辑。 */
        @Volatile
        var instance: AdCalmAccessibilityService? = null
            private set
    }
}
