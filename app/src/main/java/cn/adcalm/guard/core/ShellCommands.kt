package cn.adcalm.guard.core

/**
 * 构造交给 shell 执行的命令。
 *
 * **包名会被拼进 shell 命令行，所以必须严格校验**——不校验就等于把命令注入的
 * 口子开着。包名的合法字符集是明确的，用白名单卡死。
 *
 * 纯逻辑，可直接单元测试。
 */
object ShellCommands {

    /**
     * Android 包名的合法形式：至少两段，每段以字母开头，只含字母数字下划线。
     * 用完整匹配而不是"包含"——`com.a; rm -rf /` 这种必须被拒。
     */
    private val VALID_PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    /** 文件路径的合法字符集。用于隔离区清理，不允许出现 shell 元字符。 */
    private val UNSAFE_PATH_CHARS = Regex("[\"'`$\\\\;|&<>()\\[\\]{}*?~\\n\\r\\t]")

    /**
     * 全限定类名的合法形式。
     *
     * 和包名一样是白名单——它同样会被拼进命令行。要求至少两段且不以点开头，
     * 因为这里是喂给 `am start -n` 的完整组件，不是 Android 组件名里允许的短形式。
     */
    private val VALID_CLASS = Regex("^[a-zA-Z][a-zA-Z0-9_$]*(\\.[a-zA-Z0-9_$]+)+$")

    fun isValidPackage(pkg: String): Boolean =
        pkg.length in 3..255 && VALID_PACKAGE.matches(pkg)

    /**
     * 强制停止一个应用。
     *
     * 这是 Shizuku 通道最主要的用途——替代"打开应用信息页、找到强行停止按钮、
     * 处理确认弹窗"那套 UI 自动化。瞬时而干净，也不用按 ROM 适配按钮文案。
     *
     * @return 可直接交给 shell 的命令；包名非法时返回 null（调用方必须放弃，不要兜底拼接）
     */
    fun forceStop(pkg: String): String? =
        if (isValidPackage(pkg)) "am force-stop $pkg" else null

    /**
     * 删除文件。
     *
     * 用于清理广告下载的安装包——包括应用商店私有目录里的那些，
     * 那是无障碍路径够不到的地方。
     *
     * @return 命令；路径含 shell 元字符时返回 null
     */
    fun deleteFile(path: String): String? {
        if (!isSafePath(path)) return null
        // 用 -- 终止选项解析，避免以 - 开头的路径被当成参数
        return "rm -f -- '$path'"
    }

    /**
     * 移动文件。
     *
     * 用于把广告下载的安装包从**别的应用的私有目录**移进隔离区。
     * 那条路径普通应用碰不到，只有 shell 抬得动。
     *
     * 同分区内 `mv` 是一次 rename，几百 MB 的文件也是瞬间完成，不是拷贝。
     *
     * @return 命令；任一路径不合法时返回 null（调用方必须放弃）
     */
    fun moveFile(src: String, dest: String): String? {
        if (!isSafePath(src) || !isSafePath(dest)) return null
        return "mv -f -- '$src' '$dest'"
    }

    /**
     * 列出某个目录下所有安装包及其大小与修改时间，每行 `大小 修改时间 路径`。
     *
     * 用法是把输出交给 [AdPackageCleaner.parseStatLine] 逐行解析，
     * 再由它按"误跳之后才落盘"这条判据筛。**筛选不在 shell 里做**——
     * 时间窗口和尺寸门槛是安全边界，得留在能单元测试的纯函数里。
     *
     * 两个坑都在这台 ROM 上实测过：
     * - `find` **不支持 `-exec ... +`**（静默返回空，不报错），所以只能管道 + `while read`
     * - `find` **不支持 `-newermt` / `-newer`**，时间过滤因此必须拿回应用层做
     *
     * @param root 扫描根目录
     */
    fun listPackages(root: String): String? {
        if (!isSafePath(root)) return null
        val names = AdPackageCleaner.SUFFIXES.joinToString(" -o ") { "-name '*.$it'" }
        return "find '$root' \\( $names \\) 2>/dev/null | " +
            "while read -r f; do stat -c '%s %Y %n' \"\$f\"; done"
    }

    /**
     * 找出**改了扩展名的安装包**——按文件名筛的那条命令看不见它们。
     *
     * 2026-10-05 红队验过：把 `.apk` 改名成 `.dat`，[listPackages] 的输出里一条都没有。
     * 这里换一条判据：**文件里有没有 `AndroidManifest.xml`**（APK 本质是个 ZIP，
     * 清单是它的一个条目）。
     *
     * 只看**头 256KB 和尾 256KB**，不整份读。这个范围是量出来的，不是猜的：
     * 一开始只读头部，结果一个 22MB 的真 APK 完全没命中——清单条目的名字在
     * **文件尾部的中央目录**里（实测偏移 18.5M / 21.9M，文件总长 22.0M）。
     * 补上尾部之后：改名的真 APK 命中、同目录的 mp3 不命中，
     * 整趟从 5.4 秒降到 1.0 秒（整份 grep 要 5.4 秒，大头是那些几十兆的音视频）。
     *
     * @param root 扫描根目录
     */
    fun listRenamedPackages(root: String): String? {
        if (!isSafePath(root)) return null
        val known = AdPackageCleaner.SUFFIXES.joinToString("|") { "*.$it" }
        return "find '$root' -type f -size +1M 2>/dev/null | " +
            "while read -r f; do " +
            "case \"\$f\" in $known) continue ;; esac; " +
            "if dd if=\"\$f\" bs=4096 count=64 2>/dev/null | grep -qa 'AndroidManifest.xml' " +
            "|| tail -c 262144 \"\$f\" 2>/dev/null | grep -qa 'AndroidManifest.xml'; " +
            "then stat -c '%s %Y %n' \"\$f\"; fi; " +
            "done"
    }

    /**
     * 文件路径的合法性。
     *
     * 路径和包名一样会被拼进命令行，不校验就是命令注入。这里卡死字符集，
     * 任何含引号、反引号、`$`、`;`、`|`、`&`、换行的路径一律拒绝——
     * 拒绝的后果只是"这个文件不清了"，而放过的后果是执行任意命令。
     */
    fun isSafePath(path: String): Boolean {
        if (path.isEmpty() || path.length > 4096) return false
        if (UNSAFE_PATH_CHARS.containsMatchIn(path)) return false
        return path.startsWith("/")
    }

    /**
     * 查询某个包是否还在运行。强停之后用它确认结果。
     */
    fun isPackageRunning(pkg: String): String? =
        if (isValidPackage(pkg)) "pidof $pkg" else null

    /**
     * 把某个应用拉到前台。
     *
     * 用途是误跳回退的最后一步：广告把用户拽到别的应用之后，光清掉广告应用的后台还不够，
     * 得把用户送回他原本在用的那个应用。应用自己 `startActivity` 会被 Android 10+
     * 的后台启动限制拦住，而 shell 没有这个限制——所以这条路才是可靠的。
     *
     * 类名必须是**全限定**的（`am start -n` 要的就是完整组件），
     * Android 组件名里允许的短形式 `.MainActivity` 在命令行里解析不了。
     *
     * @param pkg      目标包名
     * @param activity 全限定类名，通常取自 `getLaunchIntentForPackage(...).component.className`
     * @return 可直接交给 shell 的命令；任一参数非法时返回 null（调用方必须放弃）
     */
    fun startApp(pkg: String, activity: String): String? {
        if (!isValidPackage(pkg)) return null
        if (!VALID_CLASS.matches(activity)) return null
        // 显式带上 MAIN/LAUNCHER 比裸 -n 稳：部分 ROM 的 am 只认组件时会拒绝启动
        return "am start -a android.intent.action.MAIN " +
            "-c android.intent.category.LAUNCHER -n '$pkg/$activity'"
    }

    /** 查询 Shizuku 通道是否可用（能否拿到 shell 权限）。 */
    fun shellIdentity(): String = "id"

    /**
     * 读出当前的无障碍服务绑定列表。
     *
     * 没设置过时系统返回字面量 `null`，由 [AccessibilityBinding.entries] 过滤。
     */
    fun getAccessibilityList(): String =
        "settings get secure enabled_accessibility_services"

    /**
     * 写回无障碍服务绑定列表。
     *
     * **只接受逐项校验过的组件名列表。** 这个值是拼进命令行的，
     * 而且它和包名不同——它带 `:` 分隔符，看起来"像个列表"就放松校验是错的：
     * 一条 `a/b; rm -rf /` 混进来就是命令注入。所以这里把每一项都过一遍
     * [AccessibilityBinding.isValidComponent]，任何一项不合格就整体拒绝。
     *
     * @return 可直接交给 shell 的命令；列表为空或含非法项时返回 null
     */
    fun setAccessibilityList(list: String): String? {
        if (list.isEmpty() || list.length > MAX_ACCESSIBILITY_LIST) return null
        val parts = list.split(':')
        if (parts.any { !AccessibilityBinding.isValidComponent(it) }) return null
        return "settings put secure enabled_accessibility_services '$list'"
    }

    /** 绑定列表的长度上限。768 个服务已经远超任何真实场景。 */
    private const val MAX_ACCESSIBILITY_LIST = 8192
}
