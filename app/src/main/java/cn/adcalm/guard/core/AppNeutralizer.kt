package cn.adcalm.guard.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无 root 强停其他应用。
 *
 * 唯一可行路径是 UI 自动化：打开该应用的「应用信息」页 → 找到「强行停止」按钮点掉
 * → 处理确认弹窗。全程约 1~2 秒，期间能看到设置页一闪而过，这是平台限制不是实现缺陷。
 *
 * 安全约束（这个组件比其余部分更需要克制）：
 * - 每一步都有超时，超时即放弃
 * - 只在疑似系统设置页的窗口里动作，用户中途切走就立即放弃
 * - 文案匹配不到就什么都不做，**绝不做猜测性点击**——在设置页乱点可能关掉别的应用
 */
class AppNeutralizer(private val service: AccessibilityService) {

    enum class Phase { IDLE, OPENING, FORCE_STOP, CONFIRMING }

    var phase: Phase = Phase.IDLE
        private set

    var targetPackage: String? = null
        private set

    private var phaseStartedAt = 0L

    val isActive: Boolean get() = phase != Phase.IDLE

    /** 是否已经点掉了「强行停止」——用于向日志说明结果。 */
    var forceStopClicked: Boolean = false
        private set

    fun start(pkg: String, now: Long) {
        targetPackage = pkg
        forceStopClicked = false
        phase = Phase.OPENING
        phaseStartedAt = now

        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", pkg, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = runCatching { service.startActivity(intent) }.isSuccess
        if (!opened) {
            Log.w(TAG, "打不开 $pkg 的应用信息页，放弃强停")
            reset()
        }
    }

    fun reset() {
        phase = Phase.IDLE
        targetPackage = null
        forceStopClicked = false
    }

    /**
     * 把窗口事件交给状态机推进。
     *
     * @return true 表示事件已被本流程消费，调用方不应再走广告识别逻辑
     */
    fun onEvent(eventPackage: String, root: AccessibilityNodeInfo?, now: Long): Boolean {
        if (phase == Phase.IDLE) return false

        if (now - phaseStartedAt > PHASE_TIMEOUT_MS) {
            Log.w(TAG, "强停 $targetPackage 在 $phase 阶段超时，放弃")
            reset()
            return true
        }

        // 用户中途切到别的应用 → 立即放弃，不在无关界面上乱点。
        // 例外：确认弹窗的宿主也算"还在流程里"，见 [looksLikeConfirmDialogHost]。
        if (phase != Phase.OPENING && !isSettingsLike(eventPackage) &&
            !looksLikeConfirmDialogHost(eventPackage)
        ) {
            Log.w(TAG, "强停过程中前台变为 $eventPackage，放弃")
            reset()
            return true
        }

        when (phase) {
            Phase.OPENING -> {
                if (isSettingsLike(eventPackage)) {
                    phase = Phase.FORCE_STOP
                    phaseStartedAt = now
                }
            }

            Phase.FORCE_STOP -> {
                if (clickFirstMatch(root, FORCE_STOP_TEXTS)) {
                    forceStopClicked = true
                    phase = Phase.CONFIRMING
                    phaseStartedAt = now
                }
            }

            Phase.CONFIRMING -> {
                if (clickFirstMatch(root, CONFIRM_TEXTS)) {
                    Log.i(TAG, "已强停 $targetPackage")
                    // 逗留一下等操作生效，再由调用方把界面退掉
                    reset()
                }
            }

            Phase.IDLE -> Unit
        }
        return true
    }

    /** 在节点树里按文案找按钮并点击。找不到返回 false，不猜。 */
    private fun clickFirstMatch(root: AccessibilityNodeInfo?, texts: List<String>): Boolean {
        if (root == null) return false
        for (text in texts) {
            val node = findByText(root, text) ?: continue
            val target = clickableSelfOrAncestor(node) ?: continue
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "强停流程：已点击「$text」")
                return true
            }
        }
        return false
    }

    private fun findByText(
        root: AccessibilityNodeInfo,
        text: String,
        depth: Int = 0,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        val own = root.text?.toString()?.trim() ?: root.contentDescription?.toString()?.trim()
        if (own == text) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            findByText(child, text, depth + 1)?.let { return it }
        }
        return null
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            hops++
        }
        return null
    }

    /**
     * 是否是「应用信息」页所在的包。
     *
     * 各家 ROM 把应用信息页放在不同包里——小米放在安全中心，华为放在系统管理，
     * 不能只认 com.android.settings。
     */
    private fun isSettingsLike(pkg: String): Boolean =
        pkg in SETTINGS_PACKAGES || pkg.contains("settings", ignoreCase = true) ||
            pkg.contains("safecenter", ignoreCase = true) ||
            pkg.contains("securitycenter", ignoreCase = true) ||
            pkg.contains("systemmanager", ignoreCase = true)

    internal companion object {
        private const val TAG = "AdCalm"
        private const val PHASE_TIMEOUT_MS = 2_500L
        private const val MAX_DEPTH = 40
        private const val MAX_ANCESTOR_HOPS = 4

        /**
         * 「强行停止」确认弹窗的宿主是不是也算"还在流程里"。
         *
         * 2026-10-05 用广告样机实测踩到：这台 ROM 的确认弹窗挂在 `com.android.systemui` 下，
         * 而 [isSettingsLike] 不认它——于是刚点完「强行停止」，状态机就以为"用户切走了"、
         * 当场放弃，**强停从来没成功过**（日志里「强停过程中前台变为 com.android.systemui，放弃」）。
         *
         * 只在确认阶段真正用到它（调用点是状态机那道守卫），而且确认阶段仍然**只按文案找按钮、
         * 找不到就什么都不做**，所以放开这一档不会在 systemui 上乱点。
         *
         * 抽成 companion 里的纯函数是为了能单测——这个 bug 值得钉住。
         */
        fun looksLikeConfirmDialogHost(pkg: String): Boolean =
            pkg in CONFIRM_DIALOG_HOSTS || pkg.contains("systemui", ignoreCase = true)

        /** 各 ROM 把系统级确认弹窗放在这些包里。 */
        val CONFIRM_DIALOG_HOSTS = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
        )

        /** 各 ROM 的「强行停止」文案。 */
        val FORCE_STOP_TEXTS = listOf(
            "强行停止", "强制停止", "强制结束", "结束运行", "停止运行",
            "Force stop", "FORCE STOP", "Force Stop",
        )

        /**
         * 确认弹窗的文案。刻意不收「强行停止」——某些 ROM 的确认按钮与入口按钮同名，
         * 收进去会导致把入口按钮再点一次，点不出确认效果。
         */
        val CONFIRM_TEXTS = listOf("确定", "确认", "确定停止", "OK", "好")

        val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.miui.securitycenter",
            "com.huawei.systemmanager",
            "com.hihonor.systemmanager",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.oppo.safecenter",
            "com.vivo.settings",
            "com.bbk.settings",
            "com.samsung.android.settings",
            "com.meizu.settings",
            "com.lenovo.settings",
        )
    }
}
