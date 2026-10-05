package cn.adcalm.guard.model

/** 屏幕坐标矩形。纯数据，不依赖 Android 类，便于单元测试。 */
data class RectSnapshot(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong().coerceAtLeast(0) * height.toLong().coerceAtLeast(0)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isValid: Boolean get() = width > 0 && height > 0

    companion object {
        val EMPTY = RectSnapshot(0, 0, 0, 0)
    }
}

/**
 * 节点树的纯数据快照。
 *
 * 打分逻辑只依赖这个结构，不直接接触 AccessibilityNodeInfo，
 * 因此可以用构造出来的假数据做纯 JVM 单元测试，不需要真机。
 */
data class NodeSnapshot(
    /** 完整的 view id，例如 `com.example.app:id/tv_skip` */
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val visible: Boolean = true,
    val bounds: RectSnapshot = RectSnapshot.EMPTY,
    /**
     * 父节点的位置。用于判断"这个小节点是不是待在某个较大容器的角上"——
     * 横幅广告的关闭叉通常没有任何文本或 id，只能靠这个相对位置识别。
     */
    val parentBounds: RectSnapshot? = null,
    /** 从根节点到这个节点的子节点索引路径，用于回查真实节点执行点击 */
    val path: List<Int> = emptyList(),
    /** 祖先链上的类名，用于判断节点是否位于 WebView 内部 */
    val ancestorClassNames: List<String> = emptyList(),
    /** 同一父节点下的兄弟类名，用于发现广告容器（类名里带 Splash / Ad 这类通用词的） */
    val siblingClassNames: List<String> = emptyList(),
    /**
     * 同一父节点下**紧邻兄弟的文案**（text 或 contentDescription）。
     *
     * 用来认「广告」标识：合规的国内广告都会在跳过按钮旁边挂一个「广告」/「AD」字样，
     * 这是**广告专有**的证据。**必须按结构取，不能按屏幕距离取**——
     * 2026-10-05 实测某浏览器里那个「广告」标签和底部导航栏的图标位置是重叠的，
     * 按距离判断会把导航栏图标也一起抬起来。
     */
    val siblingTexts: List<String> = emptyList(),
    val children: List<NodeSnapshot> = emptyList(),
) {
    /** 前序遍历整棵树，父节点先于子节点。 */
    fun walk(): Sequence<NodeSnapshot> = sequence {
        yield(this@NodeSnapshot)
        for (child in children) yieldAll(child.walk())
    }
}
