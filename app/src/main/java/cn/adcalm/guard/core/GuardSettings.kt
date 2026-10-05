package cn.adcalm.guard.core

import android.content.SharedPreferences

/**
 * 运行参数。
 *
 * 两个默认值刻意设成保守档：
 * - [dryRun] 默认 true，装好后先只记录不点击
 * - [targetedPackages] 默认为空，即不接管任何应用，必须由用户显式勾选
 */
class GuardSettings(private val prefs: SharedPreferences) {

    /** 注册在前的监听器：设置被别的组件改动时让缓存失效。 */
    private val changeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_TARGETED) targetedCache = null
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(changeListener)
    }

    /** 总开关。关闭后无障碍服务收到任何事件都直接返回。 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * 观察模式：只记录候选和判定，不执行点击。
     *
     * **默认关闭（即默认就是自动点击）。**
     *
     * 早先默认是开启的——装好后先跑一段观察期，确认不会误判再手动开自动模式。
     * 那对第一次使用的人更安全，但代价是"装完什么都不做"，容易让人以为工具坏了。
     * 现在改成默认自动，观察模式作为排查手段保留（主界面可随时切换）。
     *
     * 风险由另外三道闸门兜底：只有已勾选的应用会被介入、跨不过 65 分不点、
     * 误跳后有哨兵回退。真出问题时把这里切回 true 即可。
     */
    var dryRun: Boolean
        get() = prefs.getBoolean(KEY_DRY_RUN, false)
        set(value) = prefs.edit().putBoolean(KEY_DRY_RUN, value).apply()

    /**
     * 生效范围。只有列表内的包才会被处理，空集合表示不接管任何应用。
     * 这是保护用户软件的第一道闸门——不勾选的 App，本工具完全不介入其窗口事件。
     *
     * 值缓存在内存里：这个方法在无障碍事件的热路径上，每秒可能被调用十几次，
     * 每次都去读 SharedPreferences 会白白产生一堆 HashSet 分配。
     * 缓存由 [changeListener] 在设置页改动时失效。
     */
    var targetedPackages: Set<String>
        get() {
            targetedCache?.let { return it }
            val loaded = prefs.getStringSet(KEY_TARGETED, emptySet()).orEmpty().toSet()
            targetedCache = loaded
            return loaded
        }
        set(value) {
            val copy = value.toSet()
            targetedCache = copy
            prefs.edit().putStringSet(KEY_TARGETED, copy).apply()
        }

    fun isTargeted(pkg: String): Boolean = pkg in targetedPackages

    /** 隔离区文件的保留时长，超过后由定时任务真正删除。 */
    var quarantineRetentionHours: Int
        get() = prefs.getInt(KEY_RETENTION_HOURS, DEFAULT_RETENTION_HOURS)
        set(value) = prefs.edit().putInt(KEY_RETENTION_HOURS, value).apply()

    /**
     * 检测到误跳时是否自动强停目标应用。
     *
     * 强停走的是「应用信息页 → 强行停止」的 UI 自动化，会有 1~2 秒的设置页闪烁。
     * 觉得这个行为本身比广告还烦的话可以关掉——关掉后仍然会按返回键退回原应用。
     */
    var autoForceStop: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FORCE_STOP, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FORCE_STOP, value).apply()

    /**
     * 是否把广告下载的安装包移进隔离区。
     *
     * 需要「所有文件访问」权限；没授权时这项自动无效（只拦不删）。
     */
    var autoQuarantine: Boolean
        get() = prefs.getBoolean(KEY_AUTO_QUARANTINE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_QUARANTINE, value).apply()

    /**
     * 诊断模式：记录**所有**前台应用的完整节点树，而不仅是勾选过的应用。
     *
     * 用于抓取广告出现那一刻的真实界面结构，是补规则的主要依据。
     * 日志量很大，只在需要排查时临时打开。
     */
    var debugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()

    /**
     * App 选择列表里是否额外纳入"没有桌面图标的应用"。
     *
     * 正常情况下有桌面图标的应用就是用户认知里的"全部软件"；这个开关是兜底，
     * 万一某个应用没被列出来可以打开它（代价是会混入一些后台服务包）。
     */
    var includeHiddenApps: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_HIDDEN, false)
        set(value) = prefs.edit().putBoolean(KEY_INCLUDE_HIDDEN, value).apply()

    /**
     * OCR 兜底：节点树里找不到关闭按钮时，截图做文字识别。
     *
     * 针对画出来的关闭按钮（WebView 渲染、Canvas 自绘）。代价是每次要截屏
     * 加跑一次文字识别，CPU 和耗电都明显高于节点树路径，所以有节流。
     * Android 11 以下没有截屏 API，自动失效。
     */
    var ocrEnabled: Boolean
        get() = prefs.getBoolean(KEY_OCR_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_OCR_ENABLED, value).apply()

    /**
     * 无障碍绑定被系统清掉时，自动用 Shizuku 写回去。
     *
     * **默认开启**（2026-10-05 由用户拍板，从原来的"默认关闭"改过来）。
     *
     * 它治的是这类工具的**行业第一号死因**：绑定被清掉之后整套东西静默失效——
     * 开关看着是开的、一次都不触发，而用户只会觉得「这软件没用」。
     * 原来默认关的理由是"这实质上是让应用给自己续无障碍权限，该由用户点头"，
     * 那条理由本身站得住，但代价是把一个**已经决定要用的**工具留在了最容易死的位置上：
     * 用户根本不知道要来这里把它打开。改成默认开之后，"续权限"这件事仍然由用户掌控——
     * 它在界面上是一个明写的开关，随时能关。
     *
     * 另外两层保险，免得它去跟用户对着干：
     * - **保护被暂停时不恢复**（[enabled] 为 false）：那是最明确的"我不想让它跑"的信号。
     * - 恢复的动作**不静默**：成功、失败各弹一条提示，用户看得见。
     *
     * 打开的前提是 Shizuku 已授权（shell 有 WRITE_SECURE_SETTINGS）。
     * 没授权时这个开关不起作用，界面会说明。
     *
     * 治的问题：这台 ROM 上每次 `adb install -r` 都会把
     * `enabled_accessibility_services` 清成 null，服务绑定掉到 0 且不会自己恢复。
     */
    var restoreAccessibility: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_ACCESSIBILITY, true)
        set(value) = prefs.edit().putBoolean(KEY_RESTORE_ACCESSIBILITY, value).apply()

    /**
     * 误跳回退之后，把用户送回他原本在用的那个应用。
     *
     * 起因：原来的回退流程是「返回键×2 → 回桌面 → 强停」，用户最后被扔在**桌面**上，
     * 得自己重新点开刚才那个应用。而他要的只是继续用刚才的软件。
     *
     * **默认开启。** 和 [autoForceStop] 是两件事：那个决定要不要清掉广告应用的后台，
     * 这个决定清完之后人回到哪里。可以只关其中一个。
     *
     * 优先走 Shizuku 的 `am start`（shell 有权限，不受 Android 10+ 后台启动限制）；
     * 没有 Shizuku 时退化成直接发启动 Intent，被系统拦住就留在原处——
     * **不做任何兜底拼接，也不反复重试**。
     */
    var returnToOrigin: Boolean
        get() = prefs.getBoolean(KEY_RETURN_TO_ORIGIN, true)
        set(value) = prefs.edit().putBoolean(KEY_RETURN_TO_ORIGIN, value).apply()

    fun addTarget(pkg: String) {
        targetedPackages = targetedPackages + pkg
    }

    fun removeTarget(pkg: String) {
        targetedPackages = targetedPackages - pkg
    }

    private companion object {
        /**
         * 生效范围的进程内缓存。
         *
         * 放在伴生对象里而不是实例字段上，是因为无障碍服务和界面各自持有
         * 一个 GuardSettings 实例；放在实例上会出现"界面改了设置、服务还读旧值"。
         * 值变更由注册在 SharedPreferences 上的监听器置空。
         *
         * ⚠ 这个缓存不区分是哪个 SharedPreferences 文件。当前应用只用一份
         * （AdCalmAccessibilityService.PREFS_NAME = "adcalm"），所以是安全的；
         * 一旦引入第二份设置文件，这里必须改成按文件区分，否则两份设置会互相串。
         * SharedPreferences 没有公开 API 能拿到自己的文件名，届时需要由构造方传入。
         */
        @Volatile
        var targetedCache: Set<String>? = null

        const val KEY_ENABLED = "enabled"
        const val KEY_DRY_RUN = "dry_run"
        const val KEY_TARGETED = "targeted_packages"
        const val KEY_RETENTION_HOURS = "quarantine_retention_hours"
        const val KEY_AUTO_FORCE_STOP = "auto_force_stop"
        const val KEY_AUTO_QUARANTINE = "auto_quarantine"
        const val KEY_DEBUG_MODE = "debug_mode"
        const val KEY_INCLUDE_HIDDEN = "include_hidden_apps"
        const val KEY_OCR_ENABLED = "ocr_enabled"
        const val KEY_RESTORE_ACCESSIBILITY = "restore_accessibility"
        const val KEY_RETURN_TO_ORIGIN = "return_to_origin"
        const val DEFAULT_RETENTION_HOURS = 24
    }
}
