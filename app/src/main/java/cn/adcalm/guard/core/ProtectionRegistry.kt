package cn.adcalm.guard.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 四层保护名单。
 *
 * 需求里"其余用户自用的软件和后台不得操作"这条，最终就落在
 * [canForceStop] 这一个判断上——所有强停动作都必须先过它。
 * 任何绕过它直接调用强停的代码都是缺陷。
 */
class ProtectionRegistry(
    private val context: Context,
    private val prefs: SharedPreferences,
) {

    /** null 表示行为名单尚未加载完成，此时一律按"受保护"处理。 */
    @Volatile
    private var behaviorProtected: Set<String>? = null

    @Volatile
    private var behaviorScannedAt: Long = 0L

    /** 桌面包名。查询要走 PackageManager，很慢，缓存住。 */
    @Volatile
    private var cachedLaunchers: Set<String>? = null

    private fun launchers(): Set<String> {
        cachedLaunchers?.let { return it }
        val value = AppClassifier.launcherPackages(context)
        cachedLaunchers = value
        return value
    }

    /** L0 硬保护：系统关键包、桌面、输入法、以及本应用自身。绝不触碰。 */
    fun isHardProtected(pkg: String): Boolean {
        if (pkg == context.packageName) return true
        // 先查静态集合——它们是常数时间，能挡掉绝大多数调用，
        // 避免每次事件都去做一次 PackageManager 查询
        if (AppClassifier.isSystemCritical(pkg)) return true
        if (AppClassifier.isInputMethod(pkg)) return true
        if (pkg.startsWith("android")) return true
        return pkg in launchers()
    }

    /** L1 用户显式指定的保护包。 */
    fun isUserProtected(pkg: String): Boolean =
        pkg in prefs.getStringSet(KEY_USER_PROTECTED, emptySet()).orEmpty()

    /**
     * L2 行为保护：最近 7 天用户在真正使用的应用。
     *
     * 名单尚未加载完成时返回 true。这是刻意的 fail-safe：
     * 宁可漏掉一次该强停的广告包，也不能因为名单没准备好就误杀用户的应用。
     */
    fun isBehaviorProtected(pkg: String): Boolean {
        val snapshot = behaviorProtected ?: return true
        return pkg in snapshot
    }

    fun isProtected(pkg: String): Boolean =
        isHardProtected(pkg) || isUserProtected(pkg) || isBehaviorProtected(pkg)

    /**
     * 回退准入：要不要把用户从这个包拉回来（按返回键 + 送回原应用）。
     *
     * **和 [canForceStop] 分开**，因为"拉回来"和"杀掉对方"是两件事，
     * 后果差着一个量级。合在一起会出问题：回退的准入要求太高，就会出现
     * 「广告把用户弹到安装器，而我们因为安装器在行为保护名单里而不敢动」——
     * 2026-10-04 实测就是这么漏的。
     *
     * 三档放宽，一档比一档弱：
     * 1. **广告落地面**（浏览器/应用市场/安装器）不享受行为保护。
     *    你对它们的"使用时长"很可能是广告自己刷出来的，见 [AppClassifier.isAdLandingSurface]
     * 2. **[externallyLaunched] 为真**时，行为保护也让路。判据是"这个包进来时用的界面
     *    不是它自己的桌面入口"——广告把你塞进某应用的小程序，走的是一个**外部 scheme
     *    的桩 Activity**（类名里带 `stub` / `scheme` 这类词）；而你自己点图标打开时，
     *    前台是那个应用的 **Launcher 入口**。那个应用确实是你天天在用的，
     *    但那一刻是广告在动，不是你
     * 3. **L0 / L1 永远不让路**：系统包、桌面、输入法、本应用，以及用户显式保护的包
     *
     * @param externallyLaunched 见上。**只放宽回退，不影响 [canForceStop]**——
     *   被你天天在用的那个应用可以被"退出去并把你送回去"，但永远不会被强停
     */
    fun canRollback(pkg: String, externallyLaunched: Boolean = false): Boolean {
        if (pkg.isEmpty()) return false
        if (isHardProtected(pkg)) return false
        if (isUserProtected(pkg)) return false
        val relaxed = externallyLaunched || AppClassifier.isAdLandingSurface(pkg)
        if (isBehaviorProtected(pkg) && !relaxed) return false
        return true
    }

    /**
     * 强停准入。四层全过才允许强停，任一层命中即拒绝。
     *
     * 在 [canRollback] 的基础上再加一道"像不像广告目标"的收窄：
     * 你真正在用的应用（有桌面图标、不是浏览器/市场、不是刚装的）过不了这一关，
     * 所以绝不会被强停。
     *
     * **注意这里刻意传 `externallyLaunched = false`**：被外部拉起这条理由
     * 只够格"把用户拉回来"，不够格"杀掉对方"。
     *
     * L3 触发条件（"必须是本次点击后 3 秒内新出现的包"）由调用方
     * [cn.adcalm.guard.core.Sentinel] 负责，这里只管静态名单。
     */
    fun canForceStop(pkg: String): Boolean {
        if (!canRollback(pkg, externallyLaunched = false)) return false
        // 只有看起来像广告目标的包才允许强停，进一步收窄作用面
        return AppClassifier.looksLikeAdTarget(context, pkg)
    }

    /** 在后台线程加载行为名单，避免阻塞无障碍服务的主线程。 */
    fun refreshBehaviorAsync(scope: CoroutineScope, onLoaded: (() -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            behaviorProtected = UsageBehaviorScanner.scanUserLaunched(context, BEHAVIOR_WINDOW_DAYS)
            behaviorScannedAt = System.currentTimeMillis()
            onLoaded?.invoke()
        }
    }

    /** 已加载的行为保护包数量；返回 null 表示尚未加载完成。 */
    fun behaviorProtectedCount(): Int? = behaviorProtected?.size

    fun addUserProtected(pkg: String) {
        val set = prefs.getStringSet(KEY_USER_PROTECTED, emptySet()).orEmpty().toMutableSet()
        set += pkg
        prefs.edit().putStringSet(KEY_USER_PROTECTED, set).apply()
    }

    fun removeUserProtected(pkg: String) {
        val set = prefs.getStringSet(KEY_USER_PROTECTED, emptySet()).orEmpty().toMutableSet()
        set -= pkg
        prefs.edit().putStringSet(KEY_USER_PROTECTED, set).apply()
    }

    fun userProtectedPackages(): Set<String> =
        prefs.getStringSet(KEY_USER_PROTECTED, emptySet()).orEmpty()

    /**
     * 名单过期或用户改动了使用习惯后重新加载。
     *
     * **界面回前台时该用这个，不是 [refreshBehaviorAsync]。** 后者无条件重查——
     * 那是一遍 UsageStats 全量扫描，而 onResume 会走得很勤；
     * 这里带 [CACHE_TTL_MS] 的 TTL，十秒内回来一次不会白扫。
     */
    fun refreshIfStale(scope: CoroutineScope, onLoaded: (() -> Unit)? = null) {
        val age = System.currentTimeMillis() - behaviorScannedAt
        if (behaviorProtected == null || age > CACHE_TTL_MS) {
            refreshBehaviorAsync(scope, onLoaded)
        }
    }

    private companion object {
        const val KEY_USER_PROTECTED = "user_protected_packages"
        const val BEHAVIOR_WINDOW_DAYS = 7
        const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
