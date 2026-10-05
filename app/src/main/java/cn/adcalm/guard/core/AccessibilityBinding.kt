package cn.adcalm.guard.core

/**
 * 无障碍服务绑定列表（`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`）的纯逻辑。
 *
 * 存在的理由是一个反复踩到的坑：**这台 ROM 上每次 `adb install -r` 都会把
 * `enabled_accessibility_services` 清成 null**，服务绑定掉到 0 而且不会自己恢复。
 * 不恢复的话整个软件是静默失效的——开关看着是开的，一次都不会触发。
 * 2026-10-04 一天之内抓到三次。
 *
 * 恢复的动作只能由 shell 完成（应用自己改不了这个设置），所以走 Shizuku 通道。
 * 这里负责**算**出应该写回去的值，[ShellCommands] 负责**拼**命令，
 * [cn.adcalm.guard.shizuku.ShizukuShell] 负责执行。
 *
 * ## 为什么不是直接覆盖
 *
 * 最省事的写法是 `settings put secure enabled_accessibility_services <我们>`，
 * 但那是**整条覆盖**。用户如果还开着读屏软件、或者别的辅助工具，
 * 这一下就把它们全关了——用一个静默失效换掉另一个静默失效。
 * 所以必须读出来、合并、再写回。
 *
 * 纯函数，可直接单元测试。
 */
object AccessibilityBinding {

    /**
     * `包名/类名` 形式的组件名。
     *
     * 字符集卡得很死——这个值最终会出现在 shell 命令行里，
     * 放松一点就是命令注入口子。允许 `$` 是为了将来可能出现的内嵌类。
     *
     * 类名部分**两种写法都要收**：全限定的 `…service.AdCalmAccessibilityService`，
     * 以及 Android 认可的短形式 `.TalkBackService`（系统里现成的服务大量这么写）。
     * 只认其中一种的后果不是"少写一个服务"，而是 [merge] 判定非法后**整个放弃合并**，
     * 于是用户开着的读屏软件会被当成脏数据一起处理掉。
     */
    private val COMPONENT = Regex(
        "^[a-zA-Z][a-zA-Z0-9_$]*(\\.[a-zA-Z0-9_$]+)+/\\.?[a-zA-Z0-9_$]+(\\.[a-zA-Z0-9_$]+)*$"
    )

    fun isValidComponent(value: String): Boolean =
        value.length in 3..512 && COMPONENT.matches(value)

    /** 拆成条目。系统在没设置过时返回字面量 `null`，那不是组件，要滤掉。 */
    fun entries(raw: String?): List<String> =
        raw?.split(':')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it != "null" }
            ?: emptyList()

    /** 列表里是否已经有这个组件。 */
    fun contains(raw: String?, component: String): Boolean =
        entries(raw).any { it.equals(component, ignoreCase = true) }

    /**
     * 把 [component] 并进 [raw]，返回应该写回去的完整列表。
     *
     * 已经存在时原样返回（不重复添加，也不改变原有顺序）。
     * 组件名非法返回 null——调用方必须放弃，不要兜底拼接。
     */
    fun merge(raw: String?, component: String): String? {
        if (!isValidComponent(component)) return null
        val existing = entries(raw)
        if (existing.any { it.equals(component, ignoreCase = true) }) return existing.joinToString(":")
        return (existing + component).joinToString(":")
    }
}
