package cn.adcalm.guard.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot

/** 只操作经过重新读取确认的同一关闭控件；目标失效时停止，不按旧坐标补点。 */
class ClickExecutor(private val service: AccessibilityService) {
    enum class Method { ACTION_CLICK, GESTURE, FAILED }

    data class Result(val method: Method, val x: Int = 0, val y: Int = 0) {
        val succeeded: Boolean get() = method != Method.FAILED
    }

    /**
     * [expected] 是识别时的完整节点快照。缺少快照不执行动作。
     * 仅允许目标本身或紧包目标的直接父控件执行 ACTION_CLICK。
     */
    fun click(
        root: AccessibilityNodeInfo,
        path: List<Int>,
        bounds: RectSnapshot,
        screen: RectSnapshot,
        expected: NodeSnapshot? = null,
    ): Result {
        if (expected == null || expected.path != path || expected.bounds != bounds) return Result(Method.FAILED)
        val target = resolveValidated(root, path, expected, screen) ?: return Result(Method.FAILED)
        if (!CloseTargetValidator.hasActionTarget(target.snapshot, target.parentSnapshot, screen)) {
            return Result(Method.FAILED)
        }
        if (target.snapshot.clickable) {
            if (target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return Result(Method.ACTION_CLICK)
        } else {
            val parentSnapshot = target.parentSnapshot
            val parent = target.node.parent
            if (parentSnapshot != null && parent != null &&
                CloseTargetValidator.isSafeDirectParent(target.snapshot, parentSnapshot, screen) &&
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) return Result(Method.ACTION_CLICK)
        }

        // 动作失败后也可能变页，手势前再次核验；不偏移出原控件，不补点未知祖先。
        val refreshed = resolveValidated(root, path, expected, screen) ?: return Result(Method.FAILED)
        if (!CloseTargetValidator.hasActionTarget(refreshed.snapshot, refreshed.parentSnapshot, screen)) {
            return Result(Method.FAILED)
        }
        val x = refreshed.snapshot.bounds.centerX
        val y = refreshed.snapshot.bounds.centerY
        return if (gestureClick(x, y)) Result(Method.GESTURE, x, y) else Result(Method.FAILED)
    }

    /** OCR 调用方负责广告专用文案、截图身份和有效屏幕范围校验。 */
    fun clickAt(x: Int, y: Int): Boolean = gestureClick(x, y)

    private data class ResolvedTarget(
        val node: AccessibilityNodeInfo,
        val snapshot: NodeSnapshot,
        val parentSnapshot: NodeSnapshot?,
    )

    private fun resolveValidated(
        root: AccessibilityNodeInfo,
        path: List<Int>,
        expected: NodeSnapshot,
        screen: RectSnapshot,
    ): ResolvedTarget? {
        if (!root.refresh()) return null
        var current: AccessibilityNodeInfo = root
        for (index in path) current = current.getChild(index) ?: return null
        if (!current.refresh()) return null
        val tree = NodeTreeReader.read(root) ?: return null
        val fresh = tree.walk().firstOrNull { it.path == path } ?: return null
        if (!CloseTargetValidator.isSameTarget(expected, fresh, screen)) return null
        val parent = if (path.isEmpty()) null else tree.walk().firstOrNull { it.path == path.dropLast(1) }
        return ResolvedTarget(current, fresh, parent)
    }

    private fun gestureClick(x: Int, y: Int): Boolean {
        // 带 1px 位移的短笔画保持在已验证的小控件中心附近。
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x + 1f, y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_DURATION_MS))
            .build()
        val dispatched = service.dispatchGesture(gesture, null, null)
        if (!dispatched) Log.w(TAG, "dispatchGesture 被拒绝 ($x, $y)")
        return dispatched
    }

    private companion object {
        const val TAG = "AdCalm"
        const val GESTURE_DURATION_MS = 40L
    }
}
