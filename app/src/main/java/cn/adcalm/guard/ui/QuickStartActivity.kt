package cn.adcalm.guard.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cn.adcalm.guard.R
import cn.adcalm.guard.core.AppRecommender
import cn.adcalm.guard.core.GuardSettings
import cn.adcalm.guard.core.PermissionIntents
import cn.adcalm.guard.core.Permissions
import cn.adcalm.guard.core.UsageBehaviorScanner
import cn.adcalm.guard.data.AdEvidence
import cn.adcalm.guard.data.ObservationLog
import cn.adcalm.guard.databinding.ActivityQuickStartBinding
import cn.adcalm.guard.service.AdCalmAccessibilityService
import cn.adcalm.guard.shizuku.ShizukuShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 极简教程：只有两步。
 *
 * 页面上刻意只放**不做就一个广告也关不掉**的两件事——
 * 无障碍服务（唯一的眼睛和手指）和选择应用（决定管哪些 App）。
 * 其余权限都只是让个别功能降级（强停、下载拦截、保活），放在「更多权限」里，不拦人。
 *
 * 参照同类工具（GKD 等）的做法：一步一个动作、每步写清「现在什么状态 → 点哪个」、
 * 并且**明确告诉用户做完就生效**——不然用户不知道做到哪算完。
 */
class QuickStartActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuickStartBinding
    private lateinit var settings: GuardSettings
    private lateinit var log: ObservationLog
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickStartBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = GuardSettings(
            getSharedPreferences(AdCalmAccessibilityService.PREFS_NAME, MODE_PRIVATE)
        )
        log = ObservationLog(this)

        binding.btnBack.setOnClickListener { confirmLeave() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = confirmLeave()
            },
        )
        binding.btnStep1.setOnClickListener {
            PermissionIntents.openOrFallback(this, PermissionIntents.accessibility())
        }
        binding.btnStep2Auto.setOnClickListener { autoSelect() }
        binding.btnStep2Manual.setOnClickListener { openPicker() }
        binding.btnGoSetup.setOnClickListener { leave() }
        // 整张卡片也可点：按钮有可能落在折叠线以下，见布局里那段说明。
        binding.rowNext.setOnClickListener { leave() }

        binding.rowShizuku.setOnClickListener { showShizukuPrompt(onDecline = {}) }
        binding.rowUsage.setOnClickListener {
            PermissionIntents.openOrFallback(this, PermissionIntents.usageAccess())
        }
        binding.rowNotifications.setOnClickListener {
            PermissionIntents.openOrFallback(this, PermissionIntents.notificationAccess())
        }
        binding.rowAllFiles.setOnClickListener {
            PermissionIntents.openOrFallback(this, PermissionIntents.allFilesAccess(this))
        }
        binding.rowBattery.setOnClickListener {
            PermissionIntents.openOrFallback(this, PermissionIntents.batteryOptimization(this))
        }
    }

    private fun openWizard() {
        startActivity(Intent(this, SetupWizardActivity::class.java))
    }

    /**
     * 离开教程页。**所有出口都走这里**（返回键、返回时那个 Shizuku 弹窗、
     * 「去首页配置」按钮）。
     *
     * 两步齐了的话顺手把首页那条「往下滑」的引导点起来：用 [CalmUi.EXTRA_SETUP_GUIDE]
     * 只把"该引导了"传过去——**首页不会替他滚**，目的是让用户自己记住「权限配置」
     * 在首页往下滑的位置，自动滚过去他就学不会了。
     *
     * 为什么必须挂在出口而不是「首页回前台就判状态」：2026-10-05 实测过后者——
     * MainActivity 在 onCreate 后会先短暂 resume 一次（那时教程正被拉起、盖在上面），
     * 判定当场通过、引导条在教程背后亮完就淡出，用户回到首页时它早没了。
     *
     * `CLEAR_TOP + SINGLE_TOP` 是为了复用栈里那个 MainActivity 实例（走 onNewIntent
     * 而不是重建），否则首页会从头闪一下，引导条也就没了落脚的地方。
     */
    private fun leave() {
        val ready = Permissions.isAccessibilityEnabled(this, AdCalmAccessibilityService::class.java) &&
            settings.targetedPackages.count { isInstalled(it) } > 0
        if (ready) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(CalmUi.EXTRA_SETUP_GUIDE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
    }

    /**
     * 返回时，如果 Shizuku 还没到位就提醒一次。
     *
     * **这里刻意不写「效率降低 N%」那类数字。** Shizuku 和「关广告」毫无关系——
     * 识别和点击完全用不到它，写个百分比是编的，用户装完发现关广告没变快，
     * 反而会觉得这软件在骗人。它真正影响的是「广告把你带走之后」的收尾，
     * 而且只有那一步：1~2 秒 vs 瞬间。文案就按这一条写，一句话说完。
     *
     * 只提醒一次（[leavePromptShown]），不要每次都拦——那就成了骚扰。
     */
    private fun confirmLeave() {
        if (ShizukuShell.status() == ShizukuShell.Status.Authorized || leavePromptShown) {
            leave()
            return
        }
        leavePromptShown = true
        showShizukuPrompt(onDecline = { leave() })
    }

    /**
     * Shizuku 的说明弹窗。两个地方共用：
     * · 教程里点「Shizuku」那一行
     * · 返回时提醒一次
     *
     * 抽出来是为了两边是**同一段话**——解释口径只该有一处，
     * 不然改了一边忘了另一边，就成了两套说法。
     */
    private fun showShizukuPrompt(onDecline: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("建议装一下 Shizuku")
            .setMessage("广告把你带走后的收尾：1~2 秒 → 瞬间。配置约 1 分钟。")
            .setPositiveButton("去配置") { _, _ -> openWizard() }
            .setNegativeButton("暂时不用") { _, _ -> onDecline() }
            .setOnCancelListener { onDecline() }
            .show()
    }

    /** 「返回时提醒一次」是否已经弹过。 */
    private var leavePromptShown = false

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val accessibility = Permissions.isAccessibilityEnabled(
            this,
            AdCalmAccessibilityService::class.java,
        )
        val selected = settings.targetedPackages.count { isInstalled(it) }

        mark(binding.tvStep1State, accessibility, "已开启")
        binding.btnStep1.visibility = if (accessibility) View.GONE else View.VISIBLE

        mark(binding.tvStep2State, selected > 0, "已选 $selected 个")
        // 一键选择**不随状态隐藏**：已经选过之后仍然可能想再挑一批，
        // 藏起来等于把这条路堵死。它始终是这一步的主按钮。
        binding.btnStep2Manual.text = if (selected > 0) "调整选择" else "或者自己挑"

        val left = listOf(accessibility, selected > 0).count { !it }
        binding.tvDone.text = if (left == 0) {
            "两步都完成了 —— 打开应用时的广告会被自动关闭"
        } else {
            "还差 $left 步"
        }
        binding.tvDone.setTextColor(
            ContextCompat.getColor(
                this,
                if (left == 0) R.color.calm_primary_dark else R.color.calm_text_secondary,
            ),
        )

        // 「接下来」只在两步都做完之后才出现。没做完的人该盯的是上面那两步，
        // 这里再堆一屏可选权限，重点就淹掉了——这条正是这一页只讲两步的理由。
        binding.cardNext.visibility = if (left == 0) View.VISIBLE else View.GONE

        renderOptional()
    }

    /**
     * 可选权限那几行。
     *
     * Shizuku 单独对待：它是**最值得装的那个**（强停不再闪设置页、能删应用商店私有目录里的
     * 安装包），装完收益最大，所以要醒目——**只要不是「已授权」就标警示色**
     * （未装 / 装了没激活 / 没授权，三种都用不了），文案里"强烈建议安装"只在真没装时出现。
     * 其余的按普通的开启/未开启显示，开了就是正常色。
     */
    private fun renderOptional() {
        val status = ShizukuShell.status()
        binding.tvShizukuState.text = when (status) {
            ShizukuShell.Status.Authorized -> "已授权"
            ShizukuShell.Status.NotAuthorized -> "待授权"
            ShizukuShell.Status.InstalledNotRunning -> "待激活"
            ShizukuShell.Status.NotInstalled -> "强烈建议安装"
        }
        binding.tvShizukuState.setTextColor(
            ContextCompat.getColor(
                this,
                if (status == ShizukuShell.Status.Authorized) R.color.calm_primary_dark else R.color.calm_warning,
            ),
        )

        markOptional(binding.tvUsageState, Permissions.hasUsageAccess(this))
        markOptional(binding.tvNotificationState, Permissions.hasNotificationAccess(this))
        markOptional(binding.tvAllFilesState, Permissions.hasAllFilesAccess(this))
        markOptional(binding.tvBatteryState, Permissions.isIgnoringBatteryOptimizations(this))
    }

    private fun markOptional(view: android.widget.TextView, on: Boolean) {
        view.text = if (on) "已开启" else "未开启"
        view.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.calm_primary_dark else R.color.calm_text_secondary),
        )
    }

    private fun mark(view: android.widget.TextView, done: Boolean, doneText: String) {
        view.text = if (done) doneText else "待完成"
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (done) R.color.calm_primary_dark else R.color.calm_warning,
            ),
        )
    }

    private fun isInstalled(pkg: String): Boolean =
        runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun openPicker() {
        startActivity(Intent(this, AppPickerActivity::class.java))
    }

    /**
     * 第 ② 步的「一键选择」。判据和首页那个按钮**完全同源**
     * （[AppRecommender] + [AdEvidence] + [UsageBehaviorScanner]），
     * 只是这里少一层设置页的跳转。
     */
    private fun autoSelect() {
        binding.btnStep2Auto.isEnabled = false
        scope.launch {
            // finally 是必须的：枚举应用和扫日志都可能抛，一旦抛出去，
            // 按钮就永远停在禁用态，用户只能杀进程重来。
            val result = try {
                withContext(Dispatchers.IO) {
                    // 用不加载图标的版本：推荐只需要包名和名字（见 AppListAdapter.loadInstalledNames）。
                    val apps = AppListAdapter.loadInstalledNames(this@QuickStartActivity, settings.includeHiddenApps)
                    val evidence = AdEvidence.scan(log.file)
                    val usage = runCatching {
                        UsageBehaviorScanner.rankByForegroundTime(this@QuickStartActivity, USAGE_WINDOW_DAYS)
                    }.getOrDefault(emptyList())
                    val suggestion = AppRecommender.suggest(apps.keys, evidence, usage)
                    suggestion to apps
                }
            } finally {
                binding.btnStep2Auto.isEnabled = true
            }

            val (suggestion, labels) = result
            if (suggestion.basis == AppRecommender.Basis.NOTHING) {
                MaterialAlertDialogBuilder(this@QuickStartActivity)
                    .setTitle("还推不出来")
                    .setMessage(
                        "本机还没有任何广告记录，也拿不到「最近用过」的数据。\n\n" +
                            "可以直接进应用管理，手动勾几个最常遇到开屏广告的——勾上就生效。",
                    )
                    .setPositiveButton("去选择") { _, _ -> openPicker() }
                    .setNegativeButton("取消", null)
                    .show()
                return@launch
            }

            val names = suggestion.packages.take(6)
                .joinToString("\n") { "· ${labels[it] ?: it}" }
            val basis = if (suggestion.basis == AppRecommender.Basis.USAGE_AND_ADS) {
                "来自两部分：你常用的应用，加上日志里已经确认有广告的。共 ${suggestion.packages.size} 个："
            } else {
                "还没有广告记录，先按「这 7 天用得最久」挑了这 ${suggestion.packages.size} 个；" +
                    "用一段时间后，确认有广告的应用会自动并进来。"
            }
            MaterialAlertDialogBuilder(this@QuickStartActivity)
                .setTitle("广告软件更新")
                .setMessage("$basis\n\n$names\n\n选完还可以在应用管理里增删。")
                .setPositiveButton("就用这些") { _, _ ->
                    settings.targetedPackages = settings.targetedPackages + suggestion.packages
                    refresh()
                }
                .setNegativeButton("我自己挑") { _, _ -> openPicker() }
                .show()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        /** 和四层保护里 L2 的口径保持一致。 */
        const val USAGE_WINDOW_DAYS = 7
    }
}
