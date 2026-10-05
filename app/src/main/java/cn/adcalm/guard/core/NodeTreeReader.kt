package cn.adcalm.guard.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot

/**
 * 把 AccessibilityNodeInfo 树转换成纯数据的 [NodeSnapshot] 树。
 *
 * 所有对 Android 类的依赖都收在这一层，判定逻辑因此可以脱离真机做单元测试。
 */
object NodeTreeReader {

    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 1500

    fun read(root: AccessibilityNodeInfo?): NodeSnapshot? {
        if (root == null) return null
        val budget = intArrayOf(MAX_NODES)
        return convert(root, emptyList(), emptyList(), null, 0, budget)
    }

    private fun convert(
        node: AccessibilityNodeInfo,
        path: List<Int>,
        ancestors: List<String>,
        parentBounds: RectSnapshot?,
        depth: Int,
        budget: IntArray,
    ): NodeSnapshot? {
        if (depth > MAX_DEPTH || budget[0] <= 0) return null
        budget[0]--

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = RectSnapshot(rect.left, rect.top, rect.right, rect.bottom)

        val childCount = node.childCount
        val childClassNames = ArrayList<String>(childCount)
        for (i in 0 until childCount) {
            node.getChild(i)?.className?.toString()?.let { childClassNames += it }
        }

        // 兄弟的文案（text 优先，没有就看 contentDescription）——**打分已经不看它了**
        // （「紧邻广告标识」那条规则 2026-10-05 撤掉了），但采集照旧：撤它的依据就是靠这个
        // 字段离线量出来的，留着才能复核"广告标识到底是不是关闭按钮的兄弟"这类形状。
        // 和 childClassNames 一样，是"我这层的所有子节点"，对每个子节点来说就是它的兄弟。
        val childTexts = ArrayList<String>(childCount)
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val label = child.text?.toString()?.takeIf { it.isNotBlank() }
                ?: child.contentDescription?.toString()
            if (!label.isNullOrBlank()) childTexts += label
        }

        val ownClassName = node.className?.toString() ?: ""
        val childAncestors = ancestors + ownClassName

        val children = ArrayList<NodeSnapshot>(childCount)
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            convert(child, path + i, childAncestors, bounds, depth + 1, budget)?.let { children += it }
        }

        return NodeSnapshot(
            viewId = node.viewIdResourceName,
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = ownClassName,
            packageName = node.packageName?.toString(),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            bounds = bounds,
            parentBounds = parentBounds,
            path = path,
            ancestorClassNames = ancestors,
            siblingClassNames = childClassNames,
            siblingTexts = childTexts,
            children = children,
        )
    }
}
