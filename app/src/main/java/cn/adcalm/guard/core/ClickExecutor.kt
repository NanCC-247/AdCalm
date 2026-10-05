package cn.adcalm.guard.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import cn.adcalm.guard.model.RectSnapshot

/**
 * 执行点击。
 *
 * 优先 `performAction(ACTION_CLICK)`——它作用于视图本身，不受浮层遮挡影响。
 * 但很多广告的关闭按钮是自定义 View，没有实现该 Action（返回 false），
 * 这时退回 `dispatchGesture()` 在坐标上做真实手势。
 *
 * 手势点击有个固有风险：如果点击位置上面盖了别的浮层，点到的是浮层。
 * 所以只在 ACTION_CLICK 失败后才用。
 */
class ClickExecutor(private val service: AccessibilityService) {

    enum class Method { ACTION_CLICK, GESTURE, FAILED }

    data class Result(
        val method: Method,
        val x: Int = 0,
        val y: Int = 0,
    ) {
        val succeeded: Boolean get() = method != Method.FAILED
    }

    fun click(
        root: AccessibilityNodeInfo,
        path: List<Int>,
        bounds: RectSnapshot,
        screen: RectSnapshot,
    ): Result {
        val node = resolve(root, path)
        if (node != null) {
            val target = clickableTarget(node)
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return Result(Method.ACTION_CLICK)
            }
        }

        val (x, y) = clickPoint(bounds, screen)
        return if (gestureClick(x, y)) {
            Result(Method.GESTURE, x, y)
        } else {
            Result(Method.FAILED)
        }
    }

    /**
     * 直接在屏幕坐标上做手势点击。
     *
     * OCR 路径没有可依托的节点（关闭按钮是画出来的），只能按坐标点。
     */
    fun clickAt(x: Int, y: Int): Boolean = gestureClick(x, y)

    /** 按子节点索引路径回查真实节点。窗口在此期间变化过就会返回 null。 */
    private fun resolve(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo = root
        for (index in path) {
            current = current.getChild(index) ?: return null
        }
        return current
    }

    /**
     * 节点自己不可点击时向上找可点击的祖先。
     * `跳过 3` 这种按钮经常是外层容器可点击、内层文字不可点击。
     */
    private fun clickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            hops++
        }
        return null
    }

    private fun gestureClick(x: Int, y: Int): Boolean {
        // 路径带 1px 位移，不用零长度的 moveTo。
        // 2026-10-05 用广告样机实测：零长度路径在这台 ROM 上 dispatchGesture 返回 true、
        // 但**点击根本没发生**（同一坐标用 shell 注入却能点中）。带位移的笔画是常见写法。
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

    /**
     * 计算点击坐标。
     *
     * 一般取节点中心。但**极小且贴屏幕边缘**的节点会向屏幕内侧偏移一点：
     * 广告常把伪造的 × 做成紧贴边缘的小热区，真按钮反而稍微内缩。
     */
    private fun clickPoint(bounds: RectSnapshot, screen: RectSnapshot): Pair<Int, Int> {
        var x = bounds.centerX
        var y = bounds.centerY

        val tiny = bounds.width < TINY_NODE_PX || bounds.height < TINY_NODE_PX
        if (tiny && screen.isValid) {
            if (x < screen.left + EDGE_PX) x += EDGE_INSET_PX
            if (x > screen.right - EDGE_PX) x -= EDGE_INSET_PX
            if (y < screen.top + EDGE_PX) y += EDGE_INSET_PX
            if (y > screen.bottom - EDGE_PX) y -= EDGE_INSET_PX
        }

        // 最后兜一道：确保落在屏幕内
        if (screen.isValid) {
            x = x.coerceIn(screen.left, screen.right - 1)
            y = y.coerceIn(screen.top, screen.bottom - 1)
        }
        return x to y
    }

    private companion object {
        const val TAG = "AdCalm"
        const val MAX_ANCESTOR_HOPS = 6
        const val GESTURE_DURATION_MS = 40L
        const val TINY_NODE_PX = 72
        const val EDGE_PX = 24
        const val EDGE_INSET_PX = 12
    }
}
