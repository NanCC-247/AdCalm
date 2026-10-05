package cn.adcalm.guard.shizuku

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.util.Log
import cn.adcalm.guard.core.AccessibilityBinding
import cn.adcalm.guard.core.ShellCommands
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * 借 Shizuku 拿 shell 权限执行命令。
 *
 * **为什么需要它**：无 root 的强停只能靠无障碍去「应用信息页」点「强行停止」，
 * 有 1~2 秒页面闪烁，且各家 ROM 的按钮文案不同得逐个适配。有 shell 权限之后
 * 就是一行 `am force-stop`，瞬时而干净。
 *
 * 顺带还有两个好处：完全不经过 AccessibilityService（应用检测不到，也不受
 * Android 17 Advanced Protection Mode 对无障碍 API 的限制），以及能够到
 * 应用商店私有目录——那是无障碍路径删不了广告安装包的地方。
 *
 * **没装 Shizuku 时整体降级**：所有方法返回 [Result.Unavailable]，调用方回退到
 * 无障碍路径。这个类不抛异常，也不会静默假装成功。
 *
 * 实现说明：Shizuku 从某个版本起把 `Shizuku.newProcess` 改成了私有，官方的
 * `ShizukuRemoteProcess` 构造函数也是包级私有。所以这里直接拿
 * [IShizukuService] 的 binder 调 `newProcess`，自己处理 [moe.shizuku.server.IRemoteProcess]
 * 给的流。
 */
object ShizukuShell {

    private const val TAG = "AdCalm"
    private const val DEFAULT_TIMEOUT_MS = 3_000L
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /**
     * `IRemoteProcess.waitForTimeout` 的时间单位。
     *
     * 服务端拿它做 `TimeUnit.valueOf(unit)`，所以必须是枚举常量名。
     * 写成 "ms" 会让**每一次 exec() 都抛异常**——见 [exec] 里的说明。
     */
    private const val TIMEOUT_UNIT = "MILLISECONDS"

    /**
     * [isChannelAlive] 的结果缓存多久。
     *
     * 十秒：够挡住"界面连续刷新几次就跑几次 shell"，
     * 又短到用户刚在 Shizuku 里点了授权、切回来就能看到新结果。
     */
    private const val VERIFY_TTL_MS = 10_000L

    /**
     * Application context，用于查包是否安装。
     *
     * ShizukuShell 是个 object，拿不到 Context 参数，所以在 Application.onCreate
     * 时注入一次。没注入时 [isPackageInstalled] 返回 false（退化成"未安装"，
     * 最坏情况就是多提示一次下载，不会误判成可用）。
     */
    @Volatile
    var appContext: android.content.Context? = null

    /** 命令执行结果。 */
    sealed interface Result {
        /** Shizuku 没装或没授权。调用方应降级。 */
        data object Unavailable : Result

        /** 执行完成。[exitCode] 为 0 表示成功。 */
        data class Done(val exitCode: Int, val output: String) : Result

        /** 执行超时或抛异常。 */
        data class Failed(val reason: String) : Result
    }

    /** Shizuku 服务是否在运行。 */
    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 是否已经拿到 shell 权限。 */
    fun isAuthorized(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 通道是不是**真的能用**。
     *
     * **和 [isAuthorized] 是两件事，别混。** 那个只回答"权限有没有"，
     * 这个真的跑一条命令看结果。2026-10-04 的教训：应用显示「已授权」、
     * 自检却每条命令都抛异常（单位写错），整条通道是死的，
     * 而**界面上一切正常**——用户只会觉得"这软件没用"。
     *
     * 一次真命令要几十到几百毫秒，所以结果缓存 [VERIFY_TTL_MS]：
     * 界面每刷新一次就跑一遍 shell 是不可接受的。
     *
     * **必须在 IO 线程调用。**
     */
    fun isChannelAlive(): Boolean {
        val now = System.currentTimeMillis()
        cachedVerify?.let { (at, ok) -> if (now - at < VERIFY_TTL_MS) return ok }

        if (!isAuthorized()) {
            cachedVerify = now to false
            return false
        }
        val ok = when (val result = exec(ShellCommands.shellIdentity())) {
            is Result.Done -> result.exitCode == 0 && result.output.isNotBlank()
            else -> false
        }
        cachedVerify = now to ok
        return ok
    }

    /** [isChannelAlive] 的缓存，见那里的说明。 */
    @Volatile
    private var cachedVerify: Pair<Long, Boolean>? = null

    /** 一次性把状态读出来给界面用。 */
    fun status(): Status = when {
        isAuthorized() -> Status.Authorized
        !isBinderAlive() && isPackageInstalled() -> Status.InstalledNotRunning
        !isBinderAlive() -> Status.NotInstalled
        else -> Status.NotAuthorized
    }

    enum class Status {
        /** Shizuku 压根没装。 */
        NotInstalled,

        /**
         * 装了，但服务没在跑。
         *
         * 这是**必须单独区分**的一种状态：Shizuku 每次手机重启后都要用 ADB
         * 或无线调试重新激活一次。如果把它和"没装"混为一谈，界面就会让用户
         * 去下载一个已经装好的东西——真机上就这么犯过一次。
         */
        InstalledNotRunning,

        /** 服务在跑，就差授权。 */
        NotAuthorized,

        /** 可用。 */
        Authorized,
    }

    /** Shizuku 应用装了没有（不看服务是否在跑）。 */
    private fun isPackageInstalled(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        val info = appContext?.packageManager?.getPackageInfo(SHIZUKU_PACKAGE, 0)
        info != null
    }.getOrDefault(false)

    /**
     * 发起授权请求。Shizuku 会弹它自己的对话框，
     * 结果通过 [Shizuku.addRequestPermissionResultListener] 回调。
     */
    fun requestPermission(requestCode: Int): Boolean = runCatching {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.isPreV11()) return false
        if (Shizuku.shouldShowRequestPermissionRationale()) return false
        Shizuku.requestPermission(requestCode)
        true
    }.getOrDefault(false)

    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    /**
     * 执行一条命令。
     *
     * 动态部分（包名、路径）必须先用 [ShellCommands] 构造——那里做了字符白名单校验。
     */
    fun exec(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        val service = service() ?: return Result.Unavailable

        return try {
            val remote = service.newProcess(arrayOf("sh", "-c", command), null, null)

            // stdout 和 stderr 都要读干，否则缓冲区满会把子进程堵死
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outReader = drain(remote.inputStream, stdout)
            val errReader = drain(remote.errorStream, stderr)

            // ⚠ 这里的单位必须是 **TimeUnit 的枚举常量名**，不是 "ms"。
            //
            // Shizuku 服务端实现是 `process.waitFor(timeout, TimeUnit.valueOf(unit))`，
            // 所以传 "ms" 会抛 `IllegalArgumentException: No enum constant
            // java.util.concurrent.TimeUnit.ms`。
            //
            // 这个错误 2026-10-04 一直藏着没被发现，代价很大：**每一次 exec() 都在这一行抛异常**，
            // 于是强停和"把用户送回原应用"两条路全都是静默失效的——回退逻辑明明触发了、
            // 日志里也记了「检测到误跳」，却什么都没做。因为异常被下面的 catch 吞成
            // Result.Failed，上层就安静地降级到了无障碍路径，而那条路又被系统拦着。
            //
            // 排查时如果看到 `No enum constant ... TimeUnit.xxx`，就是这里传错了单位名。
            val finished = remote.waitForTimeout(timeoutMs, TIMEOUT_UNIT)
            if (!finished) {
                runCatching { remote.destroy() }
                outReader.join(200)
                errReader.join(200)
                return Result.Failed("命令超时（${timeoutMs}ms）：$command")
            }

            outReader.join(500)
            errReader.join(500)
            val code = runCatching { remote.exitValue() }.getOrDefault(-1)
            val text = stdout.toString().trim().ifEmpty { stderr.toString().trim() }
            if (code != 0) Log.d(TAG, "shell 退出码 $code：$command ${stderr.toString().trim()}")
            Result.Done(code, text)
        } catch (e: Exception) {
            // 用 error 级别：这条通道挂掉时上层会安静地降级，界面上一切正常，
            // 用户只会觉得"保护没生效"。日志里必须显眼。
            Log.e(TAG, "shell 执行异常，Shizuku 通道不可用：$command", e)
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 强停一个应用。
     *
     * @return true 表示命令确实执行且退出码为 0。其余情况（没授权、包名非法、
     *         执行失败）一律 false，调用方应回退到无障碍路径。
     */
    fun forceStop(pkg: String): Boolean {
        val command = ShellCommands.forceStop(pkg) ?: run {
            Log.w(TAG, "包名不合法，拒绝强停：$pkg")
            return false
        }
        return when (val r = exec(command)) {
            is Result.Done -> r.exitCode == 0
            else -> false
        }
    }

    /**
     * 删除文件。用于清理广告下载的安装包，包括应用商店私有目录里的那些——
     * 那是无障碍路径够不到的地方。
     */
    fun deleteFile(path: String): Boolean {
        val command = ShellCommands.deleteFile(path) ?: run {
            Log.w(TAG, "路径不合法，拒绝删除：$path")
            return false
        }
        return when (val r = exec(command)) {
            is Result.Done -> r.exitCode == 0
            else -> false
        }
    }

    /**
     * 把某个应用拉到前台。
     *
     * 误跳回退的最后一步。应用自己发启动 Intent 会被 Android 10+ 的后台启动限制拦住
     * （本应用的无障碍服务并没有豁免），而 shell 有权限，所以有 Shizuku 时这一步才可靠。
     *
     * @return true 只在命令确实执行且退出码为 0 时返回。调用方可据此决定要不要退化到
     *         直接发 Intent——**不要重试、不要兜底拼接**。
     */
    fun startApp(pkg: String, activity: String): Boolean {
        val command = ShellCommands.startApp(pkg, activity) ?: run {
            Log.w(TAG, "启动参数不合法，拒绝执行：$pkg/$activity")
            return false
        }
        return when (val r = exec(command)) {
            is Result.Done -> r.exitCode == 0
            else -> false
        }
    }

    /** 跑一条命令并把输出原样返回，用于界面上的自检。 */
    fun probe(): String = when (val r = exec(ShellCommands.shellIdentity())) {
        is Result.Done -> r.output.ifEmpty { "(无输出)" }
        is Result.Failed -> "失败：${r.reason}"
        Result.Unavailable -> "Shizuku 不可用"
    }

    /**
     * 把本应用的无障碍服务重新写进系统绑定列表。
     *
     * 治的是这个项目上最隐蔽也最常踩的一个坑：**每次 `adb install -r` 都会把
     * `enabled_accessibility_services` 清成 null**，服务绑定掉到 0 而且不会自己恢复。
     * 不恢复的话整个软件是静默失效的——开关看着是开的，一次都不会触发，
     * 用户只会觉得「这软件没用」。2026-10-04 一天之内抓到三次。
     *
     * **读—合并—写，不是覆盖。** 用户可能还开着读屏软件或别的辅助工具，
     * 直接 `put` 一个只有我们的值会把它们一起关掉。见 [AccessibilityBinding]。
     *
     * @return true 只在「读到了当前值、合并成功、写回退出码为 0」时返回。
     *         任何一步不成立都返回 false，调用方**不要重试、不要兜底**——
     *         写这个设置要么成功要么让用户手动开，猜着写只会更糟。
     */
    fun restoreAccessibility(component: String): Boolean {
        if (!AccessibilityBinding.isValidComponent(component)) {
            Log.w(TAG, "组件名不合法，拒绝写入无障碍绑定：$component")
            return false
        }

        // 先读当前值。读不到就不要写——盲写等于覆盖掉别人的服务。
        val current = when (val r = exec(ShellCommands.getAccessibilityList())) {
            is Result.Done -> r.output
            else -> {
                Log.w(TAG, "读不到无障碍绑定列表，放弃恢复")
                return false
            }
        }

        val merged = AccessibilityBinding.merge(current, component) ?: return false
        val command = ShellCommands.setAccessibilityList(merged) ?: return false

        return when (val r = exec(command)) {
            is Result.Done -> r.exitCode == 0
            else -> false
        }
    }

    private fun service(): IShizukuService? {
        if (!isAuthorized()) return null
        return runCatching { IShizukuService.Stub.asInterface(Shizuku.getBinder()) }.getOrNull()
    }

    /** 把 [ParcelFileDescriptor] 的内容读到 [into]，返回执行读取的线程。 */
    private fun drain(pfd: ParcelFileDescriptor?, into: StringBuilder): Thread =
        Thread {
            val stream = pfd?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
            runCatching { stream?.bufferedReader()?.forEachLine { into.appendLine(it) } }
            runCatching { stream?.close() }
        }.apply { isDaemon = true; start() }
}
