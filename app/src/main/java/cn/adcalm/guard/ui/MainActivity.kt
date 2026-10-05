package cn.adcalm.guard.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cn.adcalm.guard.R
import cn.adcalm.guard.core.AppRecommender
import cn.adcalm.guard.core.GuardSettings
import cn.adcalm.guard.core.Permissions
import cn.adcalm.guard.core.ProtectionRegistry
import cn.adcalm.guard.core.ScanFreshness
import cn.adcalm.guard.core.UsageBehaviorScanner
import cn.adcalm.guard.data.AdEvidence
import cn.adcalm.guard.data.DownloadJanitor
import cn.adcalm.guard.data.ObservationLog
import cn.adcalm.guard.databinding.ActivityMainBinding
import cn.adcalm.guard.service.AdCalmAccessibilityService
import cn.adcalm.guard.shizuku.ShizukuShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** A quiet overview and a separate settings tab; all state comes from the service. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: GuardSettings
    private lateinit var registry: ProtectionRegistry
    private lateinit var log: ObservationLog
    private lateinit var janitor: DownloadJanitor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentTab = CalmUi.TAB_HOME

    private val settingsBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { showTab(CalmUi.TAB_HOME) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CalmUi.prepare(this, binding.root)
        val prefs = getSharedPreferences(AdCalmAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
        settings = GuardSettings(prefs)
        registry = ProtectionRegistry(this, prefs)
        log = ObservationLog(this)
        janitor = DownloadJanitor(this, settings)
        onBackPressedDispatcher.addCallback(this, settingsBack)

        binding.btnSetup.setOnClickListener { openSetup() }
        binding.rowSetup.setOnClickListener { openSetup() }

        // 用户自己滚动首页时，处理教程页留下的那条滑动引导（见 [onHomeScrolled]）。
        // 参数是 (view, scrollX, scrollY, oldScrollX, oldScrollY)。
        binding.homePage.setOnScrollChangeListener { _, _, _, scrollY, oldScrollY ->
            onHomeScrolled(scrollY - oldScrollY)
        }
        binding.tvDegraded.setOnClickListener { onDegradedClicked() }
        binding.tvBackgroundState.setOnClickListener { openSetup() }
        binding.btnPickApps.setOnClickListener { openApps() }
        binding.btnAutoSelect.setOnClickListener { autoSelectAdApps() }
        binding.btnQuarantine.setOnClickListener {
            startActivity(Intent(this, QuarantineActivity::class.java))
        }
        binding.btnCleanPackages.setOnClickListener {
            startActivity(Intent(this, AdPackageCleanActivity::class.java))
        }
        binding.rowLogs.setOnClickListener {
            startActivity(Intent(this, ObservationLogActivity::class.java))
        }
        binding.rowShizuku.setOnClickListener { onShizukuClicked() }
        binding.rowSelfCheck.setOnClickListener {
            startActivity(Intent(this, SelfCheckActivity::class.java))
        }
        bindSwitch(binding.cbAutoForceStop, settings.autoForceStop) { settings.autoForceStop = it }
        bindSwitch(binding.cbReturnToOrigin, settings.returnToOrigin) { settings.returnToOrigin = it }
        bindSwitch(binding.cbAutoQuarantine, settings.autoQuarantine) { settings.autoQuarantine = it }
        bindSwitch(binding.cbOcr, settings.ocrEnabled) { settings.ocrEnabled = it }
        bindSwitch(binding.cbRestoreAccessibility, settings.restoreAccessibility) { settings.restoreAccessibility = it }
        bindSwitch(binding.cbDebugMode, settings.debugMode) {
            settings.debugMode = it
            if (it) toast("诊断模式已开启，排查结束后记得关闭")
        }
        bindModeSwitch()
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navHome -> { showTab(CalmUi.TAB_HOME); true }
                R.id.navSettings -> { showTab(CalmUi.TAB_SETTINGS); true }
                R.id.navApps -> { openApps(); false }
                else -> false
            }
        }
        showTab(savedInstanceState?.getString(STATE_TAB)
            ?: intent.getStringExtra(CalmUi.EXTRA_START_TAB) ?: CalmUi.TAB_HOME)
        fitHomeHeroToViewport()
        maybeShowQuickStart(savedInstanceState)
    }

    /**
     * 从后台切回来时也铺一次盾牌，和冷启动看到的开屏是同一样子。
     *
     * 只靠 [SplashActivity] 是做不到「每次打开都看到」的——它是启动入口，
     * 只在冷启动时被拉起；应用还在内存里的时候，点图标是系统把已有任务直接拉到前台，
     * 走的是下面这条 onStart，压根不经过那个页面。
     *
     * 但 onStart 也**不能无脑铺**：应用内跳转（去应用管理、去隔离区再回来）
     * 同样会触发它，每次都闪一下就成了干扰。所以隔一段时间没在前台才算
     * 「重新打开」，[BACKGROUND_GAP_MS] 之内回来就不铺。
     *
     * 时长和 [SplashActivity.SHOW_MS] 保持一致，都是 600ms——它的作用是
     * 「打开时看一眼」，不是让用户等。
     */
    private fun maybeShowSplashOverlay() {
        val now = SystemClock.elapsedRealtime()
        val gapsAway = lastStoppedAt != 0L && now - lastStoppedAt >= BACKGROUND_GAP_MS
        if (!gapsAway) return

        val overlay = CalmShieldView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.calm_background))
        }
        // 加到 decorView 而不是 binding.root：后者是 LinearLayout，
        // 塞一个 match_parent 高度的子视图会把它重新排一遍（底部导航被挤到顶上去）。
        // decorView 是 FrameLayout，叠一层正好盖住整个窗口，也不碰原有布局。
        val host = window.decorView as? ViewGroup ?: return
        host.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        host.postDelayed({ host.removeView(overlay) }, SPLASH_MS)
    }

    /** 上次被切到后台的时刻。[maybeShowSplashOverlay] 用它判断「是不是重新打开了」。 */
    private var lastStoppedAt = 0L

    /**
     * 还没配置好，就直接把新手教程推出来。
     *
     * 判据是**「配置好了没有」，不是「弹过没有」**：
     * 新装的应用必然还没配置好，所以「第一次安装必须弹出」是这条规则的必然结果，
     * 不需要额外的标记去记「是否弹过」。反过来，用户中途退出了、下次打开还没配好，
     * 也照样会弹——因为在那之前这软件确实什么都不做，让他干看着主界面没有意义。
     *
     * 一旦两步都齐了（无障碍开着 + 有选中的应用），就再也不打扰。
     */
    private fun maybeShowQuickStart(state: Bundle?) {
        if (state != null) return

        val ready = Permissions.isAccessibilityEnabled(this, AdCalmAccessibilityService::class.java) &&
            settings.targetedPackages.isNotEmpty()
        if (ready) return

        startActivity(Intent(this, QuickStartActivity::class.java))
    }

    /**
     * 让首页首屏那一整组（插画 / 模式 / 状态 / 统计 / 两个按钮）正好占满一屏，
     * 权限提示及后面的内容从第二屏开始。
     *
     * 做法：把 [heroGroup] 的高度撑到视口高度，组内上下各有一个 `weight=1` 的空隙
     * 平分多出来的空间——所以内容是**在这半屏里垂直居中**的，
     * 而不是把空白一股脑堆在底下。
     *
     * 只有内容比一屏**矮**时才撑；本来就更高的机器上保持 `wrap_content`，
     * 不然权重会把内容压扁。自然高度只在第一次量一次并记住，
     * 否则撑高之后再量就成了自己量自己。
     */
    private fun fitHomeHeroToViewport() {
        binding.heroGroup.post {
            val viewport = binding.homePage.height
            if (viewport <= 0) return@post
            if (heroNaturalHeight == 0) heroNaturalHeight = binding.heroGroup.height

            val target = maxOf(heroNaturalHeight, viewport)
            val lp = binding.heroGroup.layoutParams
            if (lp.height == target) return@post
            lp.height = target
            binding.heroGroup.layoutParams = lp
        }
    }

    /** [fitHomeHeroToViewport] 用。首次布局时量到的首屏自然高度。 */
    private var heroNaturalHeight = 0

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showTab(intent.getStringExtra(CalmUi.EXTRA_START_TAB) ?: CalmUi.TAB_HOME)
        if (intent.getBooleanExtra(CalmUi.EXTRA_SETUP_GUIDE, false)) {
            pendingSetupGuide = true
        }
    }

    /**
     * 教程页走的时候留下的那一次引导（见 `QuickStartActivity.leave`）。
     *
     * 这里是"引导"，不是"代劳"：**刻意不做自动滚动**。自动滚过去看着省事，但用户
     * 下次权限掉了照样不知道卡片在下面——他记住的只是"上次它自己跳过去的"。
     * 通行做法也是这样：播一个手势动画、等用户真的滑了再把提示撤掉
     * （USA TODAY 那种"用户一滑就取消提示"的写法）。
     *
     * 为什么不在 [onNewIntent] 里直接显示：紧接着还会走一遍 [refresh]，
     * 它会改降级提示的显隐、卡片位置跟着变，先显示就可能对不上。
     * 所以那里只置位，等 refresh 跑完再显示。
     *
     * **也刻意不做成"回首页就自动判状态"**：2026-10-05 试过那一版，结果 MainActivity
     * 在 onCreate 之后会先短暂 resume 一次（那时教程正被拉起、盖在它上面），
     * 判定当场就通过、引导条在教程背后亮完 8 秒自己淡出，用户回到首页时早就"用掉"了。
     * 所以触发点必须挂在**离开教程**那一刻。
     */
    private var pendingSetupGuide = false

    /** 引导条出现后，累计滑过这么多像素才算"他真的滑了"。太小会被误触吃掉，太大撤不掉。 */
    private val setupGuideSlop by lazy { (8 * resources.displayMetrics.density).toInt() }

    /** 本次引导里用户累计滑了多少。 */
    private var setupGuideScrolled = 0

    /** 本次引导里卡片是否已经闪过——一次引导只闪一次。 */
    private var setupCardPulsed = false

    /** 箭头的循环弹跳，离开前台要停掉。 */
    private var guideArrowAnimator: ObjectAnimator? = null

    /** 超时自动收起。要能 removeCallbacks，所以存成字段。 */
    private val hideGuideRunnable = Runnable { hideSetupGuide() }

    private fun showSetupGuide() {
        setupGuideScrolled = 0
        setupCardPulsed = false

        // 屏幕特别高、卡片本来就在眼前的话不引导，直接闪一下——
        // 让人去滑一个已经看得见的东西，只会显得莫名其妙。
        if (setupCardOnScreen()) {
            pulseSetupCard()
            return
        }

        binding.setupGuide.visibility = View.VISIBLE
        binding.setupGuide.alpha = 0f
        binding.setupGuide.animate().alpha(1f).setDuration(200).start()

        guideArrowAnimator?.cancel()
        guideArrowAnimator = ObjectAnimator.ofFloat(
            binding.ivGuideArrow,
            View.TRANSLATION_Y,
            0f,
            setupGuideSlop.toFloat(),
            0f,
        ).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            start()
        }

        // 弱引导那一档：用户不理会就自己淡出。一直挂着会从"提示"变成"干扰"。
        binding.setupGuide.postDelayed(hideGuideRunnable, SETUP_GUIDE_TIMEOUT_MS)
    }

    private fun hideSetupGuide() {
        binding.setupGuide.removeCallbacks(hideGuideRunnable)
        guideArrowAnimator?.cancel()
        guideArrowAnimator = null
        if (binding.setupGuide.visibility != View.VISIBLE) return
        binding.setupGuide.animate().alpha(0f).setDuration(200)
            .withEndAction { binding.setupGuide.visibility = View.GONE }
            .start()
    }

    /** 「权限配置」卡片是不是已经在可视区里了。 */
    private fun setupCardOnScreen(): Boolean =
        binding.cardSetup.top - binding.homePage.scrollY < binding.homePage.height

    /**
     * 用户自己滑动之后。
     *
     * 滑够一段就撤掉引导条（他要找的东西已经在路上了），卡片进画面就让它闪一下——
     * 既是"这就是那张"的反馈，也省得他在一摞卡片里找。
     */
    private fun onHomeScrolled(deltaY: Int) {
        if (binding.setupGuide.visibility == View.VISIBLE) {
            setupGuideScrolled += kotlin.math.abs(deltaY)
            if (setupGuideScrolled >= setupGuideSlop) hideSetupGuide()
        }
        if (!setupCardPulsed && setupGuideScrolled > 0 && setupCardOnScreen()) pulseSetupCard()
    }

    private fun pulseSetupCard() {
        setupCardPulsed = true
        ObjectAnimator.ofFloat(binding.cardSetup, View.ALPHA, 1f, 0.45f, 1f).setDuration(700).start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TAB, currentTab)
        super.onSaveInstanceState(outState)
    }

    private fun showTab(tab: String) {
        currentTab = if (tab == CalmUi.TAB_SETTINGS) CalmUi.TAB_SETTINGS else CalmUi.TAB_HOME
        val isSettings = currentTab == CalmUi.TAB_SETTINGS
        binding.homePage.visibility = if (isSettings) View.GONE else View.VISIBLE
        binding.settingsPage.visibility = if (isSettings) View.VISIBLE else View.GONE
        settingsBack.isEnabled = isSettings
        // Mark the menu directly: setting selectedItemId would re-enter its listener.
        binding.bottomNavigation.menu.findItem(if (isSettings) R.id.navSettings else R.id.navHome).isChecked = true
    }

    /**
     * 首页所有「权限相关」入口都走这里。
     *
     * 核心两步（无障碍 + 选择应用）没做完时先去**极简教程**——那两件事才是
     * 「不做就一个广告也关不掉」的；一上来丢给用户六个权限步骤，重点就淹掉了。
     * 两步都完成了才进完整向导，去调强停、下载拦截这些可选功能。
     */
    private fun openSetup() {
        val ready = Permissions.isAccessibilityEnabled(this, AdCalmAccessibilityService::class.java) &&
            settings.targetedPackages.isNotEmpty()
        val target = if (ready) SetupWizardActivity::class.java else QuickStartActivity::class.java
        startActivity(Intent(this, target))
    }
    private fun openApps() = startActivity(Intent(this, AppPickerActivity::class.java))

    /**
     * 「一键选择多广告应用」。
     *
     * 两条依据都来自本机：观察日志里的广告判定条数、以及「最近真的用过」的记录。
     * **不查任何外部应用清单**——既是为了不随项目分发按应用点名的清单，
     * 也是因为别处抄来的清单在这台机器上未必成立。
     *
     * 结果是**加入**已选应用，不是替换：用户手动勾过的东西不能被我悄悄抹掉。
     */
    private fun autoSelectAdApps() {
        // 先看核心权限。无障碍服务没开的话，选出来的应用一个广告也关不掉——
        // 让用户选完才发现没反应，比一开始就说清楚要糟得多。
        val missing = missingCorePermissions()
        if (missing.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("还有核心权限没开启")
                .setMessage(
                    "下面这些没启用：\n\n" +
                        missing.joinToString("\n\n") { "· $it" } +
                        "\n\n建议先去配置，否则选完也不会生效。",
                )
                .setPositiveButton("去配置") { _, _ -> openSetup() }
                .setNegativeButton("仍然选择") { _, _ -> runAutoSelect() }
                .show()
            return
        }
        runAutoSelect()
    }

    /**
     * 还没开启的核心权限，已经开启的不列。
     *
     * 「核心」指的是**没启用就关不掉广告**的那两个，也就是配置向导里标为必需的两项。
     * 其余的（通知使用权、所有文件访问、电池优化、Shizuku）都只是让个别功能降级，
     * 不该拿来拦人。
     */
    private fun missingCorePermissions(): List<String> = buildList {
        if (!Permissions.isAccessibilityEnabled(this@MainActivity, AdCalmAccessibilityService::class.java)) {
            add(
                "无障碍服务 —— 唯一一个「没开就什么都做不了」的权限：" +
                    "读不到别的应用界面上的按钮，也就点不了关闭",
            )
        }
        if (!Permissions.hasUsageAccess(this@MainActivity)) {
            add(
                "使用情况访问 —— 不影响点关闭按钮，但推荐会失去「用得多」这条依据，" +
                    "误跳后的自动强停也会一并失效",
            )
        }
    }

    private fun runAutoSelect() {
        binding.btnAutoSelect.isEnabled = false
        scope.launch {
            // finally 是必须的：枚举应用和扫日志都可能抛，一旦抛出去，
            // 按钮就永远停在禁用态，用户只能杀进程重来。
            val loaded = try {
                withContext(Dispatchers.IO) {
                    // 这里刻意用不加载图标的版本：推荐只需要包名和名字，
                    // 而带图标的枚举是整条链路里最慢的一步（见 AppListAdapter.loadInstalledNames）。
                    val apps = AppListAdapter.loadInstalledNames(this@MainActivity, settings.includeHiddenApps)
                    val evidence = AdEvidence.scan(log.file)
                    val usage: List<Pair<String, Long>> = runCatching {
                        UsageBehaviorScanner.rankByForegroundTime(this@MainActivity, USAGE_WINDOW_DAYS)
                    }.getOrDefault(emptyList())
                    RecoInput(apps, evidence, usage)
                }
            } finally {
                binding.btnAutoSelect.isEnabled = true
            }

            val suggestion = AppRecommender.suggest(
                loaded.apps.keys,
                loaded.evidence,
                loaded.usageRanked,
            )
            showSuggestion(suggestion, loaded.apps)
        }
    }

    /** 推荐需要的三份输入。用具名类而不是 Triple，免得解构时靠位置记。 */
    private data class RecoInput(
        /** 包名 → 应用名 */
        val apps: Map<String, String>,
        val evidence: Map<String, Int>,
        /** 包名 → 累计前台毫秒，按时长降序 */
        val usageRanked: List<Pair<String, Long>>,
    )

    private fun showSuggestion(
        suggestion: AppRecommender.Suggestion,
        labelOf: Map<String, String>,
    ) {
        if (suggestion.basis == AppRecommender.Basis.NOTHING) {
            MaterialAlertDialogBuilder(this)
                .setTitle("还推不出来")
                .setMessage(
                    "两条依据现在都是空的：\n\n" +
                        "· 观察日志里还没有任何广告判定记录。" +
                        "可以先打开「诊断模式」用几天，让它真的看过你常用的应用；\n\n" +
                        "· 也拿不到「最近用过」的记录——" +
                        "检查「使用情况访问」权限是否开着。\n\n" +
                        "在那之前，用「管理应用」手动勾几个最常遇到开屏广告的即可。",
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val current = settings.targetedPackages
        val toAdd = suggestion.packages - current
        if (toAdd.isEmpty()) {
            toast("推荐的 ${suggestion.packages.size} 个都已经在已选里了")
            return
        }

        val evidenceOf = suggestion.topEvidence.toMap()
        val names = toAdd.take(8).joinToString("\n") { pkg ->
            val label = labelOf[pkg] ?: pkg
            val n = evidenceOf[pkg]
            when {
                n == null -> "· $label"
                pkg in suggestion.usageBased -> "· $label（这 7 天用了约 ${n} 分钟）"
                else -> "· $label（${n} 次广告判定）"
            }
        }
        val more = if (toAdd.size > 8) "\n…另有 ${toAdd.size - 8} 个" else ""

        val basis = when (suggestion.basis) {
            AppRecommender.Basis.USAGE_AND_ADS ->
                "来自两部分：你常用的应用，加上日志里已经确认有广告的。共 ${suggestion.packages.size} 个："
            else ->
                "本机还没有广告判定记录——刚装上时就是这样，所以先按「这 7 天用得最久」推。" +
                    "用一段时间后，确认有广告的应用会自动并进来。共 ${suggestion.packages.size} 个："
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("广告软件更新")
            .setMessage("$basis\n\n$names$more\n\n会把这 ${toAdd.size} 个加入已选应用（不会取消你已有的选择）。")
            .setPositiveButton("加入") { _, _ ->
                settings.targetedPackages = current + toAdd
                refresh()
                toast("已加入 ${toAdd.size} 个应用")
            }
            .setNegativeButton("取消", null)
            .show()
    }
    private fun bindSwitch(view: CompoundButton, initial: Boolean, write: (Boolean) -> Unit) {
        view.isChecked = initial
        view.setOnCheckedChangeListener { _, checked -> write(checked); refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // 这里原先会启动 GuardForegroundService。它已经被去掉：前台服务在 Android 8+
        // 必须常驻一条通知，而下载监控搬进无障碍服务后就不再需要它了。
        //
        // 用 refreshIfStale 而不是 refreshBehaviorAsync：后者无条件重查一遍
        // UsageStats 全量，而 onResume 走得很勤（切出去看一眼再回来就会调）。
        registry.refreshIfStale(scope) { scope.launch { refresh() } }
        maybeRestoreAccessibility()
    }

    /**
     * 无障碍绑定被系统清掉时，用 Shizuku 自动写回去。
     *
     * 检测方只能是**界面**，不能是无障碍服务自己——绑定掉了那个服务就已经死了，
     * 它没有机会发现自己不见了。所以触发点是「用户打开本应用」。
     * 这恰好也是最需要它的时刻：用户就是因为"怎么没反应"才打开这个界面的。
     *
     * 四个前提缺一不可，任何一个不成立就安静地什么都不做：
     * 1. 用户没把这个开关关掉（默认开启，见 [GuardSettings.restoreAccessibility]）
     * 2. **保护没有被暂停**（`settings.enabled`）——那是最明确的"我不想让它跑"的信号，
     *    这时候再把无障碍绑定写回去就是逆着用户的意思来
     * 3. 绑定确实掉了
     * 4. Shizuku 已授权——没有 shell 就改不了这个设置
     *
     * 加节流是因为它跑在 onResume 上：失败时也不能每次回前台都去开一次 shell。
     */
    private fun maybeRestoreAccessibility() {
        if (!settings.restoreAccessibility) return
        if (!settings.enabled) return
        if (Permissions.isAccessibilityEnabled(this, AdCalmAccessibilityService::class.java)) return
        if (ShizukuShell.status() != ShizukuShell.Status.Authorized) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastRestoreAttemptAt < RESTORE_RETRY_GAP_MS) return
        lastRestoreAttemptAt = now

        scope.launch {
            val component = ComponentName(
                this@MainActivity,
                AdCalmAccessibilityService::class.java,
            ).flattenToString()

            val ok = withContext(Dispatchers.IO) { ShizukuShell.restoreAccessibility(component) }
            if (!ok) {
                // 失败就如实说，不要静默——用户以为修好了、实际没修，比不修更糟。
                toast("自动恢复无障碍失败，请手动开启")
                return@launch
            }

            toast("已用 Shizuku 恢复无障碍绑定")
            // 写进去之后系统重新绑定要一点点时间，立刻刷新会读到还没生效的旧值。
            delay(RESTORE_SETTLE_MS)
            refresh()
        }
    }

    /** [maybeRestoreAccessibility] 的节流时间戳。 */
    private var lastRestoreAttemptAt = 0L

    private fun refresh() {
        val accessibility = Permissions.isAccessibilityEnabled(this, AdCalmAccessibilityService::class.java)
        val usage = Permissions.hasUsageAccess(this)
        val files = Permissions.hasAllFilesAccess(this)
        val notifications = Permissions.hasNotificationAccess(this)
        val requiredMissing = listOf(accessibility, usage).count { !it }
        val optionalMissing = listOf(files, notifications).count { !it }
        val targets = settings.targetedPackages

        // 首页有三个数字要读系统或文件才能算出来，**都不能在主线程上做**：
        //   · 已安装的已选应用 —— 对 123 个包逐个走一次 PackageManager
        //   · 日志行数         —— 把整个日志文件读一遍（实测 8MB 以上）
        //   · 隔离区文件       —— 读索引文件
        // 而 refresh() 在回前台、切开关、权限回调时都会调。所以它们算在 IO 上、
        // 结果缓存一段；没算出来之前显示「—」，不阻塞界面。
        val stats = homeStats
        val installedTargets = stats?.installedTargets

        binding.tvTargetCount.text = installedTargets?.toString() ?: "—"
        binding.tvProtectedCount.text = if (usage) registry.behaviorProtectedCount()?.toString() ?: "—" else "—"
        binding.tvScopeCaption.visibility =
            if ((stats?.staleTargets ?: 0) > 0) View.VISIBLE else View.GONE
        binding.tvScopeCaption.text = "${stats?.staleTargets ?: 0} 个已卸载应用未计入"
        binding.tvLogCount.text = stats?.let { "${it.logLines} 条" } ?: "—"
        binding.tvQuarantineCount.text = stats?.quarantineText ?: "读取中…"
        loadHomeStatsIfStale()

        binding.tvPermissionState.text = if (requiredMissing == 0) "已就绪" else "$requiredMissing 项待配置"
        binding.tvPermissionState.setTextColor(ContextCompat.getColor(this,
            if (requiredMissing == 0) R.color.calm_primary_dark else R.color.calm_warning))
        binding.tvPermissionSummary.text = when {
            requiredMissing > 0 -> "点此完成必需权限配置"
            optionalMissing > 0 -> "必需权限就绪 · 另有 $optionalMissing 项可选"
            else -> "必需权限已全部开启"
        }
        binding.tvSetupSummary.text = when {
            requiredMissing > 0 -> "$requiredMissing 项待配置"
            optionalMissing > 0 -> "$optionalMissing 项可选"
            else -> "已就绪"
        }

        binding.shieldHero.isObserving = settings.dryRun || !settings.enabled || !accessibility || installedTargets == 0
        binding.tvModeBanner.text = when {
            !settings.enabled -> "保护已暂停"
            !accessibility -> "保护等待开启"
            installedTargets == 0 -> "选择你的保护范围"
            settings.dryRun -> "观察模式已开启"
            else -> "自动模式已开启"
        }

        // 后台到底活着没有。
        //
        // 只看「设置里开着」是不够的：无障碍服务有可能被系统或各家 ROM 的后台管理掐掉，
        // 那时开关看着是开的、实际一次都不会触发——**静默失效**，
        // 用户只会觉得「这软件没用」。所以这里额外确认服务实例还在。
        val backgroundUp = settings.enabled && accessibility &&
            AdCalmAccessibilityService.instance != null
        binding.tvBackgroundState.text = if (backgroundUp) {
            "当前状态：后台已挂起"
        } else {
            "当前状态：后台未挂起 · 点此启用"
        }
        binding.tvBackgroundState.setTextColor(
            ContextCompat.getColor(
                this,
                if (backgroundUp) R.color.calm_primary_dark else R.color.calm_warning,
            ),
        )
        binding.tvBackgroundState.isClickable = !backgroundUp
        binding.tvModeDescription.text = when {
            !settings.enabled -> "开启保护后恢复处理已选应用"
            !accessibility -> "完成权限配置，让保护准备就绪"
            installedTargets == 0 -> "从应用管理中选择需要处理的应用"
            settings.dryRun -> "纯观察：只记录，不点击、不回退、不强停"
            else -> "只处理你选择的应用"
        }
        renderDegradedFeatures(usage, files, notifications)
        renderShizuku()
        renderTodayClicks()
        renderLastScan(backgroundUp)

        // 清理安装包那条路完全依赖 Shizuku（别的应用私有目录只有 shell 读得到），
        // 没授权时先说清楚，别让用户点进去才发现扫不了。
        binding.tvCleanPackagesHint.text =
            if (ShizukuShell.status() == ShizukuShell.Status.Authorized) {
                "扫描别的应用缓存里的安装包"
            } else {
                "需要 Shizuku 授权才能扫描"
            }

        // 教程页走的时候留下的那一次（见 QuickStartActivity.leave）。放在 refresh 末尾：
        // 上面那些可见性变化会挪动卡片位置（见 [pendingSetupGuide]）。
        if (pendingSetupGuide) {
            pendingSetupGuide = false
            showSetupGuide()
        }
    }

    /**
     * 首页那行「今天已跳过 N 次」。
     *
     * 这是对**静默失效**的另一半回答：上面那行后台状态说的是"服务还活着吗"，
     * 这一行说的是"活着，但它今天到底干活了没有"。少了它，用户唯一能得到的
     * 反馈就是"我感觉没效果"——而 41% 的点击落在非广告界面那件事，
     * 也正是在有这行数字之后才看得出来的。
     *
     * 数字来自日志文件，要数完整个文件，所以放 IO 线程，并且加节流：
     * [refresh] 在切开关、回前台、权限回调时都会调，没必要每次都全量扫一遍。
     * 过期的那次不刷新旧数字，等下一次 refresh 再取——避免出现"先显示旧值再跳新值"。
     */
    private fun renderTodayClicks() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTodayClicksAt < TODAY_CLICKS_THROTTLE_MS) return
        lastTodayClicksAt = now

        scope.launch {
            val count = withContext(Dispatchers.IO) {
                log.clicksSince(ObservationLog.startOfToday())
            }
            binding.tvTodayClicks.text = if (count > 0) {
                "今天已跳过 $count 次广告"
            } else {
                "今天还没有跳过记录"
            }
            binding.tvTodayClicks.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (count > 0) R.color.calm_primary_dark else R.color.calm_text_secondary,
                ),
            )
        }
    }

    /** [renderTodayClicks] 的节流时间戳。 */
    private var lastTodayClicksAt = 0L

    /**
     * 首页那行「最近一次扫描：N 分钟前」。
     *
     * 和上面那行是配对的：那行是 0 的时候，它同时对应"扫了但一个都没认出"和
     * "**根本一拍都没扫**"两种处境，用户分不出来；这一行把它分开。
     * 后一种真出现过——节拍器每拍都从"前台包名缓存"取当前应用，而服务重启那一刻缓存是空的，
     * 用户不切应用就没有窗口事件，于是**永远不扫**，界面上却一片健康。见 [ScanFreshness]。
     *
     * 读的是服务里的内存值、不碰文件，所以不需要像 [renderTodayClicks] 那样上 IO 线程和节流。
     */
    private fun renderLastScan(serviceUp: Boolean) {
        binding.tvLastScan.text = ScanFreshness.describe(
            msSinceScan = AdCalmAccessibilityService.instance?.msSinceLastScan(),
            serviceUp = serviceUp,
        )
    }

    /**
     * 首页那几个要读系统或文件才能算出来的数字。
     *
     * 为什么要缓存：它们的代价都不小（见 [refresh] 里的说明），
     * 而 refresh() 调得很勤。没算出来之前界面上显示「—」。
     */
    private data class HomeStats(
        val installedTargets: Int,
        val staleTargets: Int,
        val logLines: Int,
        val quarantineText: String,
    )

    private var homeStats: HomeStats? = null
    private var lastHomeStatsAt = 0L

    /**
     * 缓存过期就在 IO 线程上重算一次。
     *
     * 节流不能省：refresh() 在回前台、切开关、权限回调时都会调，
     * 每次都跑 123 次 PackageManager + 读一遍日志文件是不可接受的。
     * 算完只有**真的变了**才重绘，避免 refresh → load → refresh 转圈。
     */
    private fun loadHomeStatsIfStale() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHomeStatsAt < HOME_STATS_TTL_MS) return
        lastHomeStatsAt = now

        scope.launch {
            val targets = settings.targetedPackages
            val fresh = withContext(Dispatchers.IO) {
                val installed = targets.count { isPackageInstalled(it) }
                val quarantined = janitor.listQuarantined()
                HomeStats(
                    installedTargets = installed,
                    staleTargets = targets.size - installed,
                    logLines = log.lineCount(),
                    quarantineText = if (quarantined.isEmpty()) {
                        "暂无隔离文件"
                    } else {
                        "${quarantined.size} 个文件 · " +
                            "${quarantined.sumOf { it.sizeBytes } / 1024 / 1024} MB"
                    },
                )
            }
            if (fresh != homeStats) {
                homeStats = fresh
                refresh()
            }
        }
    }

    private fun renderDegradedFeatures(usage: Boolean, files: Boolean, notifications: Boolean) {
        // 两类问题分开说，因为它们的成因和处置都不一样
        val missing = mutableListOf<String>()
        if (settings.autoForceStop && !usage) missing += "自动强停"
        if (settings.autoQuarantine && !files) missing += "安装包隔离"
        if (!notifications) missing += "下载通知拦截"

        // Shizuku 有**三种**状态，不能混成一句话：
        //   1. 没装 / 没启动 / 没授权 —— 是"还没配好"，依赖它的功能降级或不可用
        //   2. 已授权、通道通      —— 正常
        //   3. 已授权、命令执行不了 —— 通道坏了，**依赖它的功能全都静默失效**
        //
        // 第 3 种是 2026-10-04 那个 bug 的形态（单位写错），界面上曾经完全看不出来。
        // 但**第 1 种不能报成第 3 种**——那会告诉用户"你已授权了"，
        // 而实际上他连授权都还没做。真机上就这么错过一次。
        val shizukuAuthorized = ShizukuShell.status() == ShizukuShell.Status.Authorized
        val channelBroken = shizukuChannelOk == false && shizukuAuthorized

        val shizukuLine = when {
            channelBroken -> {
                val affected = buildList {
                    if (settings.autoForceStop) add("强停")
                    if (settings.returnToOrigin) add("送回原应用")
                }
                "Shizuku 已授权但命令执行不了（通道异常）：" +
                    affected.joinToString("、") + "都会静默失效。点这里自检。"
            }

            !shizukuAuthorized -> {
                val affected = buildList {
                    add("安装包清理")
                    if (settings.returnToOrigin) add("误跳后送回原应用")
                    if (settings.autoForceStop) add("强停会退化成点设置页")
                }
                "Shizuku 未授权或未启动：" + affected.joinToString("、") +
                    "。点这里配置。"
            }

            else -> null
        }

        val lines = buildList {
            if (missing.isNotEmpty()) {
                add("${missing.joinToString("、")}还需配置权限，当前无法生效。")
            }
            shizukuLine?.let { add(it) }
        }
        degradedHasMissingPermission = missing.isNotEmpty() || !shizukuAuthorized
        binding.tvDegraded.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        binding.tvDegraded.text = lines.joinToString("\n\n")
    }

    /** 首页降级提示里有没有"权限没配"这一项。见 [onDegradedClicked]。 */
    private var degradedHasMissingPermission = false

    /**
     * 首页那条降级提示点了之后去哪。
     *
     * 缺权限就走配置向导；权限齐了、只有 Shizuku 通道有问题时直接跑自检——
     * 那时候用户需要的是"看它到底通不通"，再走一遍权限向导没有意义。
     */
    private fun onDegradedClicked() {
        if (degradedHasMissingPermission) openSetup() else onShizukuClicked()
    }

    private fun isPackageInstalled(pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == SHIZUKU_REQUEST_CODE) {
            toast(if (result == PackageManager.PERMISSION_GRANTED) "Shizuku 已授权" else "未授权，继续使用无障碍路径")
            refresh()
        }
    }
    override fun onStart() {
        super.onStart()
        ShizukuShell.addPermissionListener(shizukuPermissionListener)
        maybeShowSplashOverlay()
    }
    override fun onStop() {
        lastStoppedAt = SystemClock.elapsedRealtime()
        ShizukuShell.removePermissionListener(shizukuPermissionListener)
        // 引导条是一次性的：离开前台就收起，回来不再冒出来。
        hideSetupGuide()
        super.onStop()
    }
    private fun renderShizuku() {
        val state = ShizukuShell.status()
        binding.tvShizuku.text = when (state) {
            ShizukuShell.Status.Authorized -> when (shizukuChannelOk) {
                // 「已授权」只说明权限在。真正的通道通不通要跑一条命令才知道，
                // 所以这里宁可先说"检查中"，也不要把"有权限"说成"能用"
                null -> "检查中…"
                true -> "已授权"
                false -> "已授权 · 通道异常"
            }
            ShizukuShell.Status.NotAuthorized -> "待授权"
            ShizukuShell.Status.InstalledNotRunning -> "待启动"
            ShizukuShell.Status.NotInstalled -> "未安装"
        }
        val usable = state == ShizukuShell.Status.Authorized && shizukuChannelOk == true
        binding.tvShizuku.setTextColor(ContextCompat.getColor(this,
            if (usable) R.color.calm_primary_dark else R.color.calm_text_secondary))

        probeShizukuChannel()
    }

    /** 通道实测结果；null = 还没测出来。见 [ShizukuShell.isChannelAlive]。 */
    private var shizukuChannelOk: Boolean? = null

    /** [probeShizukuChannel] 的节流时间戳。 */
    private var lastChannelProbeAt = 0L

    /**
     * 跑一次通道自检。
     *
     * 它要真的执行一条 shell 命令（几十到几百毫秒），所以放 IO 线程并且节流——
     * refresh() 在切开关、回前台、权限回调时都会调，每次都跑一遍 shell 不可接受。
     * 结果和上次不同才触发重绘，避免 refresh → probe → refresh 转圈。
     */
    private fun probeShizukuChannel() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastChannelProbeAt < CHANNEL_PROBE_GAP_MS) return
        lastChannelProbeAt = now

        scope.launch {
            val ok = withContext(Dispatchers.IO) { ShizukuShell.isChannelAlive() }
            if (ok != shizukuChannelOk) {
                shizukuChannelOk = ok
                refresh()
            }
        }
    }

    private fun onShizukuClicked() {
        when (ShizukuShell.status()) {
            ShizukuShell.Status.Authorized -> scope.launch {
                val output = kotlinx.coroutines.withContext(Dispatchers.IO) { ShizukuShell.probe() }
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("Shizuku 通道自检")
                    .setMessage("执行 id 的输出：\n\n$output\n\n包含 uid=2000(shell) 表示通道正常。")
                    .setPositiveButton("知道了", null).show()
            }
            ShizukuShell.Status.NotAuthorized -> if (!ShizukuShell.requestPermission(SHIZUKU_REQUEST_CODE)) {
                toast("请在 Shizuku 应用中手动授权")
            }
            else -> openSetup()
        }
    }

    /**
     * 观察模式开关。它原来在首页做成一个按钮，现在挪进设置页——
     * 首页该回答的是「保护在不在生效」，而不是放一个容易误触的模式开关。
     *
     * **只有关掉它（也就是真的开始点击）才需要确认**：打开观察模式是往安全方向走，
     * 没什么好确认的。
     */
    private fun bindModeSwitch() {
        binding.cbDryRun.isChecked = settings.dryRun
        binding.cbDryRun.setOnCheckedChangeListener { view, checked ->
            if (checked) {
                settings.dryRun = true
                refresh()
                return@setOnCheckedChangeListener
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("开启自动模式？")
                .setMessage(
                    "关掉观察模式后，本工具会真的去点击它认定的关闭按钮。\n\n" +
                        "建议先跑几天观察模式，看过日志确认没有误判再开。",
                )
                .setPositiveButton("开启自动模式") { _, _ ->
                    settings.dryRun = false
                    refresh()
                }
                .setNegativeButton("取消") { _, _ -> view.isChecked = true }
                .setOnCancelListener { view.isChecked = true }
                .show()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private companion object {
        const val STATE_TAB = "selected_tab"
        const val SHIZUKU_REQUEST_CODE = 4001

        /** 「最近用过」的回看窗口，和四层保护里 L2 的口径保持一致。 */
        const val USAGE_WINDOW_DAYS = 7

        /** 开屏那一屏显示多久。和 [SplashActivity.SHOW_MS] 一致。 */
        const val SPLASH_MS = 600L

        /** 离开前台超过这么久，再回来才算「重新打开」，才铺开屏。 */
        const val BACKGROUND_GAP_MS = 5_000L

        /** 滑动引导条挂多久没人理就自己淡出。一直挂着会从"提示"变成"干扰"。 */
        const val SETUP_GUIDE_TIMEOUT_MS = 20_000L

        /** 「今天已跳过 N 次」最短多久重算一次。数它要扫一遍日志文件。 */
        const val TODAY_CLICKS_THROTTLE_MS = 3_000L

        /** 自动恢复无障碍绑定失败后，最短多久再试一次。 */
        const val RESTORE_RETRY_GAP_MS = 10_000L

        /** 写回绑定后等系统重新绑定生效再刷新界面的时间。 */
        const val RESTORE_SETTLE_MS = 600L

        /**
         * 两次 Shizuku 通道自检之间至少隔多久。
         *
         * 它要真的跑一条 shell 命令，不能跟着 refresh() 走。
         * 十秒和 [ShizukuShell] 里那份缓存的 TTL 对齐。
         */
        const val CHANNEL_PROBE_GAP_MS = 10_000L

        /**
         * 首页那几个数字多久重算一次。
         *
         * 十秒：够挡住"回前台、切开关连着刷新几次"，又不至于让刚装/刚卸的应用
         * 迟迟不反映到数字上。
         */
        const val HOME_STATS_TTL_MS = 10_000L
    }
}
