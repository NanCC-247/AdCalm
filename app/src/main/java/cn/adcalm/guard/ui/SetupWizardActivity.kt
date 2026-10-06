package cn.adcalm.guard.ui

import android.os.Bundle
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import cn.adcalm.guard.core.PermissionIntents
import cn.adcalm.guard.core.Permissions
import cn.adcalm.guard.databinding.ActivitySetupWizardBinding
import cn.adcalm.guard.service.AdCalmAccessibilityService
import cn.adcalm.guard.shizuku.ShizukuInstaller
import cn.adcalm.guard.shizuku.ShizukuShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 权限配置向导。
 *
 * Android 不允许应用直接给自己授权，所以这里做的是"跳到对应的系统设置页 + 回来检测状态"。
 * 每一步都说明了不开启的**具体后果**，而不是笼统地说"建议开启"。
 *
 * Shizuku 通过浏览器访问官方发布页；AdCalm 不再下载或安装外部 APK。
 */
class SetupWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupWizardBinding
    private lateinit var adapter: SetupStepAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Shizuku 授权结果回来后要刷新界面。 */
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_REQUEST_CODE) return@OnRequestPermissionResultListener
            toast(
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    "Shizuku 已授权，强停会走 shell 通道"
                } else {
                    "未授权，继续使用无障碍路径"
                }
            )
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CalmUi.prepare(this, binding.root)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = SetupStepAdapter { step -> step.open() }
        binding.rvSteps.layoutManager = LinearLayoutManager(this)
        binding.rvSteps.adapter = adapter
    }

    override fun onStart() {
        super.onStart()
        ShizukuShell.addPermissionListener(shizukuPermissionListener)
    }

    override fun onStop() {
        ShizukuShell.removePermissionListener(shizukuPermissionListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val steps = buildSteps()
        adapter.submit(steps)

        val done = steps.count { it.granted }
        val requiredLeft = steps.count { it.required && !it.granted }
        binding.tvProgress.text = if (requiredLeft == 0) {
            "必需权限已就绪"
        } else {
            "还有 $requiredLeft 项必需权限待开启"
        }
        binding.tvCompletion.text = "$done / ${steps.size}"
        binding.tvCompletion.contentDescription = "已完成 $done 项，共 ${steps.size} 项权限"
        binding.progressPermissions.max = steps.size
        binding.progressPermissions.setProgressCompat(done, true)
    }

    private fun buildSteps(): List<SetupStep> = listOf(
        SetupStep(
            title = "无障碍服务",
            summary = "识别广告并操作关闭按钮，是自动处理所需的核心权限。",
            description = "开启后，AdCalm 才能读取广告界面并操作关闭按钮。\n\n" +
                "Android 13 及以上会拦截侧载应用的这项权限：勾选时会提示「受限设置」。" +
                "此时先回到「设置 → 应用 → AdCalm → 右上角 ⋮ → 允许受限设置」，再回来开启。\n\n" +
                "注意不要在通话过程中操作（Android 16 会禁止通话中开启无障碍）。",
            granted = Permissions.isAccessibilityEnabled(this, AdCalmAccessibilityService::class.java),
            required = true,
            open = { open(PermissionIntents.accessibility()) },
        ),

        SetupStep(
            title = "使用情况访问",
            summary = "识别你正在使用的应用，避免误停。未开启时不会执行强停。",
            description = "用来判断哪些 App 是你自己在用的（最近 7 天前台停留超过 1 分钟，或从桌面图标启动过）。\n\n" +
                "不开的话保护名单失效，程序会保守地拒绝强停任何应用——等于把强停功能关掉了。",
            granted = Permissions.hasUsageAccess(this),
            required = true,
            open = { open(PermissionIntents.usageAccess()) },
        ),

        SetupStep(
            title = "通知使用权",
            summary = "取消通知栏中的广告下载。未开启时，已开始的下载可能无法拦截。",
            description = "用来在通知栏取消广告触发的下载。\n\n" +
                "不开的话下载拦截只能靠强停应用来打断，已经开始下载的拦不住。",
            granted = Permissions.hasNotificationAccess(this),
            required = false,
            open = { open(PermissionIntents.notificationAccess()) },
        ),

        SetupStep(
            title = "所有文件访问",
            summary = "将广告安装包暂存到隔离区，方便查看、恢复和清理。",
            description = "用来把广告下载的安装包移进隔离区。\n\n" +
                "不开的话退化成「只拦截不清理」，已经落盘的安装包会留在手机里。",
            granted = Permissions.hasAllFilesAccess(this),
            required = false,
            open = { open(PermissionIntents.allFilesAccess(this)) },
        ),

        SetupStep(
            title = "电池优化白名单",
            summary = "让保护在后台持续运行；部分手机还需要单独允许自启动。",
            description = "不加白名单的话后台会被系统杀掉，无法持续工作。\n\n" +
                "各家 ROM 还有自己的自启动管理，需要单独在系统管家类应用里放行。",
            granted = Permissions.isIgnoringBatteryOptimizations(this),
            required = false,
            open = { open(PermissionIntents.batteryOptimization(this)) },
        ),

        SetupStep(
            title = "Shizuku",
            summary = when (ShizukuShell.status()) {
                ShizukuShell.Status.Authorized -> "快速处理通道已就绪，可随时检查连接状态。"
                ShizukuShell.Status.NotAuthorized -> "服务已运行，授权后即可使用快速处理通道。"
                ShizukuShell.Status.InstalledNotRunning -> "请先在 Shizuku 中启动服务，再返回这里授权。"
                ShizukuShell.Status.NotInstalled -> "可选的快速处理通道。未安装时会继续使用无障碍服务。"
            },
            description = shizukuDescription(),
            granted = ShizukuShell.isAuthorized(),
            required = false,
            actionLabel = when (ShizukuShell.status()) {
                ShizukuShell.Status.Authorized -> "通道自检"
                ShizukuShell.Status.NotAuthorized -> "去授权"
                ShizukuShell.Status.InstalledNotRunning -> "如何启动"
                ShizukuShell.Status.NotInstalled -> "打开官网"
            },
            open = { onShizukuStep() },
        ),
    )

    private fun shizukuDescription(): String = when (ShizukuShell.status()) {
        ShizukuShell.Status.Authorized ->
            "已授权。强停其他应用走的是一行 am force-stop，瞬间完成，没有设置页闪烁；" +
                "也能删掉应用商店私有目录里的广告安装包。\n\n" +
                "完全不经过无障碍服务，应用检测不到，也不受 Android 17 对无障碍 API 的限制。\n\n" +
                "点下方按钮可以做一次通道自检。"

        ShizukuShell.Status.NotAuthorized ->
            "Shizuku 已安装，但还没授权。点下方按钮发起授权请求。"

        ShizukuShell.Status.InstalledNotRunning ->
            "Shizuku 已经装好了，但它的服务没在运行。\n\n" +
                "这是它的正常工作方式——Shizuku 每次手机重启后都要重新激活一次，" +
                "激活前它无法把 shell 权限借给别的应用。\n\n" +
                "激活方法：打开 Shizuku 应用，按它的指引操作（通常需要开启无线调试，" +
                "或在电脑上执行一条命令）。激活后再回到这里点「去授权」。\n\n" +
                "点下方按钮会帮你打开 Shizuku 应用。"

        ShizukuShell.Status.NotInstalled ->
            "强烈建议安装。它把 ADB 的 shell 权限借给应用，于是：\n\n" +
                "· 强停其他应用变成一行命令，瞬间完成——不再需要打开设置页点「强行停止」，" +
                "也没有那 1~2 秒的页面闪烁\n" +
                "· 能删掉应用商店私有目录里的广告安装包（无 root 时唯一碰不到的地方）\n" +
                "· 完全不经过无障碍服务，应用检测不到它，也不受 Android 17 对无障碍 API 的限制\n\n" +
                "点下方按钮会在浏览器中打开官方发布页，由你自行下载与安装。" +
                "这是本应用唯一会联网的地方。\n\n" +
                "代价：每次手机重启需要用无线调试重新激活一次 Shizuku。"
    }

    /**
     * Shizuku 这一行被点之后的行为，按当前状态分三种。
     *
     * 「下载并安装」只能做到把系统安装器拉起来——Android 不允许应用静默安装 APK，
     * 最后一步必须由用户在系统对话框里确认。
     */
    private fun onShizukuStep() {
        when (ShizukuShell.status()) {
            ShizukuShell.Status.Authorized -> scope.launch {
                val probe = withContext(Dispatchers.IO) { ShizukuShell.probe() }
                MaterialAlertDialogBuilder(this@SetupWizardActivity)
                    .setTitle("Shizuku 通道自检")
                    .setMessage("执行 `id` 的输出：\n\n$probe\n\n包含 uid=2000(shell) 就说明通道正常。")
                    .setPositiveButton("知道了", null)
                    .show()
            }

            ShizukuShell.Status.NotAuthorized -> {
                if (!ShizukuShell.requestPermission(SHIZUKU_REQUEST_CODE)) {
                    toast("无法发起授权请求，请在 Shizuku 应用里手动授权")
                }
            }

            ShizukuShell.Status.NotInstalled -> confirmDownloadAndInstall()

            ShizukuShell.Status.InstalledNotRunning -> openShizukuApp()
        }
    }

    /**
     * 打开 Shizuku 应用让用户自己激活。
     *
     * 激活必须在 Shizuku 应用里完成（要么开无线调试，要么在电脑上执行它的启动命令），
     * 我们无法代劳——这需要 shell 权限，而拿到 shell 权限正是激活之后的事。
     */
    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent == null) {
            toast("找不到 Shizuku 应用，它可能已被卸载")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("去激活 Shizuku")
            .setMessage(
                "Shizuku 的服务每次手机重启后都要重新激活一次，激活前无法使用。\n\n" +
                    "激活方式有两种：\n" +
                    "· 手机端：在 Shizuku 里开启「无线调试」，按它的指引配对\n" +
                    "· 电脑端：用数据线连上，执行 Shizuku 里给出的那条 adb 命令\n\n" +
                    "激活完成后回到这里，状态会变成「待授权」。"
            )
            .setPositiveButton("打开 Shizuku") { _, _ -> startActivity(intent) }
            .setNegativeButton("稍后", null)
            .show()
    }

    /**
     * 点「下载并安装」之后先查最新版地址，再弹说明。
     *
     * 查一次是为了拿到**确切的文件名**——只给一个笼统的"去官网下"，
     * 用户到了发布页还得自己猜该下哪个 asset。
     */
    private fun confirmDownloadAndInstall() {
        MaterialAlertDialogBuilder(this).setTitle("打开 Shizuku 官网？")
            .setMessage("基础识别无需 Shizuku。可选增强功能需要你自行从官方发布页下载、安装、激活及授权。AdCalm 不下载外部安装包，也不请求安装未知应用权限。")
            .setPositiveButton("打开官方发布页") { _, _ -> ShizukuInstaller.openOfficialPage(this) }
            .setNegativeButton("取消", null).show()
    }

    private fun open(intent: android.content.Intent) {
        PermissionIntents.openOrFallback(this, intent)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val SHIZUKU_REQUEST_CODE = 4002
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val RELEASES_URL = "https://github.com/RikkaApps/Shizuku/releases"
    }
}
