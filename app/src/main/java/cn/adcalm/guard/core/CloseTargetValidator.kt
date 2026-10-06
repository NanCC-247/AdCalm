package cn.adcalm.guard.core

import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot

/** 点击前的目标身份与直接父控件校验，纯逻辑可在 JVM 上测试。 */
object CloseTargetValidator {
    fun hasActionTarget(node: NodeSnapshot, parent: NodeSnapshot?, screen: RectSnapshot): Boolean =
        node.clickable || (parent != null && isSafeDirectParent(node, parent, screen))

    fun isSameTarget(expected: NodeSnapshot, current: NodeSnapshot, screen: RectSnapshot): Boolean =
        ExplicitClosePolicy.isEligible(expected) && ExplicitClosePolicy.isEligible(current) &&
            ExplicitClosePolicy.isInside(current.bounds, screen) && expected.bounds == current.bounds &&
            minOf(current.bounds.width, current.bounds.height) >= 36 &&
            current.bounds.width <= screen.width * 0.5 && current.bounds.height <= screen.height * 0.15 &&
            current.bounds.area <= screen.area * 0.045 &&
            expected.packageName == current.packageName && expected.viewId == current.viewId &&
            expected.className == current.className &&
            ExplicitClosePolicy.normalize(expected.text.orEmpty()) ==
                ExplicitClosePolicy.normalize(current.text.orEmpty()) &&
            ExplicitClosePolicy.normalize(expected.contentDescription.orEmpty()) ==
                ExplicitClosePolicy.normalize(current.contentDescription.orEmpty()) &&
            !hasCompetingAction(current)

    /** 不跨多层祖先；父热区须紧包明确标签，且不能同时包含其他操作。 */
    fun isSafeDirectParent(child: NodeSnapshot, parent: NodeSnapshot, screen: RectSnapshot): Boolean {
        if (!ExplicitClosePolicy.isEligible(child) || !parent.clickable || !parent.enabled || !parent.visible) {
            return false
        }
        if (child.path.isEmpty() || parent.path != child.path.dropLast(1) ||
            child.packageName != parent.packageName ||
            parent.children.none { it.path == child.path && it.bounds == child.bounds }
        ) return false
        if (!ExplicitClosePolicy.isInside(parent.bounds, screen) ||
            !ExplicitClosePolicy.isInside(child.bounds, parent.bounds) ||
            parent.bounds.area > child.bounds.area * 4 || parent.bounds.width > screen.width * 0.5 ||
            parent.bounds.height > screen.height * 0.15
        ) return false
        if (ExplicitClosePolicy.hasDisallowedAction(parent) || hasCompetingAction(parent)) return false
        val ownLabels = listOfNotNull(parent.text, parent.contentDescription).filter { it.isNotBlank() }
        if (ownLabels.any { !ExplicitClosePolicy.isExplicitCloseText(it) && !ExplicitClosePolicy.isIconText(it) }) {
            return false
        }
        // 父节点中任何可见正文都须仍为同一关闭控件的标签或广告标记。
        return parent.children.asSequence().flatMap { it.walk() }.filter { it.visible }.all { node ->
            listOfNotNull(node.text, node.contentDescription).filter { it.isNotBlank() }.all {
                ExplicitClosePolicy.isExplicitCloseText(it) || ExplicitClosePolicy.isIconText(it) ||
                    ExplicitClosePolicy.isAdMarker(it)
            }
        }
    }

    private fun hasCompetingAction(node: NodeSnapshot): Boolean = node.children.any { child ->
        child.visible && (ExplicitClosePolicy.hasDisallowedAction(child) ||
            (child.clickable && !ExplicitClosePolicy.hasExplicitCloseLabel(child)) || hasCompetingAction(child))
    }
}
