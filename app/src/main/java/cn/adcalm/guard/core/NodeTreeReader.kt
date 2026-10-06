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
        return convert(root, emptyList(), emptyList(), null, emptyList(), emptyList(), 0, budget)
    }

    private fun convert(
        node: AccessibilityNodeInfo,
        path: List<Int>,
        ancestors: List<String>,
        parentBounds: RectSnapshot?,
        siblingClassNames: List<String>,
        siblingTexts: List<String>,
        depth: Int,
        budget: IntArray,
    ): NodeSnapshot? {
        if (depth > MAX_DEPTH || budget[0] <= 0) return null
        budget[0]--

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = RectSnapshot(rect.left, rect.top, rect.right, rect.bottom)

        val childCount = node.childCount
        val rawChildren = (0 until childCount).map { node.getChild(it) }

        val ownClassName = node.className?.toString() ?: ""
        val childAncestors = ancestors + ownClassName

        val children = ArrayList<NodeSnapshot>(childCount)
        for (i in 0 until childCount) {
            val child = rawChildren[i] ?: continue
            // 仅使用紧邻、可见且有实际面积的同层控件；不将自身/后代正文冒充兄弟证据。
            val adjacent = listOfNotNull(rawChildren.getOrNull(i - 1), rawChildren.getOrNull(i + 1))
                .filter { sibling ->
                    val siblingRect = Rect()
                    sibling.getBoundsInScreen(siblingRect)
                    sibling.isVisibleToUser && siblingRect.width() > 0 && siblingRect.height() > 0
                }
            val classes = adjacent.mapNotNull { it.className?.toString() }
            val labels = adjacent.mapNotNull { sibling ->
                // 语境只用实际可见文字，不用别的控件看不见的描述创造广告标记。
                sibling.text?.toString()?.takeIf { it.isNotBlank() }
            }
            convert(child, path + i, childAncestors, bounds, classes, labels, depth + 1, budget)
                ?.let { children += it }
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
            siblingClassNames = siblingClassNames,
            siblingTexts = siblingTexts,
            children = children,
        )
    }
}
