package cn.adcalm.guard.core

import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import org.junit.Assert.*
import org.junit.Test

class CloseTargetValidatorTest {
    private val screen = RectSnapshot(0, 0, 1080, 2400)
    private val close = NodeSnapshot(
        text = "跳过广告 3", packageName = "com.example", viewId = "com.example:id/skip",
        className = "android.widget.TextView", bounds = RectSnapshot(900, 100, 1040, 180),
        path = listOf(0, 0),
    )
    private fun parent(child: NodeSnapshot = close) = NodeSnapshot(
        packageName = "com.example", clickable = true, path = listOf(0),
        bounds = RectSnapshot(880, 90, 1060, 190), children = listOf(child),
    )

    @Test fun `刷新后同目标可以保留递减数字`() {
        assertTrue(CloseTargetValidator.isSameTarget(close, close.copy(text = "跳过广告 2"), screen))
    }

    @Test fun `移位换页换文本或失效目标不能补点旧坐标`() {
        for (current in listOf(
            close.copy(text = "安装"), close.copy(text = "帮助", contentDescription = "关闭广告"),
            close.copy(bounds = RectSnapshot(800, 100, 940, 180)),
            close.copy(packageName = "com.other"), close.copy(viewId = "com.example:id/install"),
            close.copy(enabled = false), close.copy(visible = false),
        )) assertFalse(current.toString(), CloseTargetValidator.isSameTarget(close, current, screen))
    }

    @Test fun `通用关闭的广告上下文消失时不能沿用旧证据`() {
        val generic = close.copy(text = "关闭", siblingTexts = listOf("广告"))
        assertFalse(CloseTargetValidator.isSameTarget(generic, generic.copy(siblingTexts = emptyList()), screen))
    }

    @Test fun `同一紧包小父控件支持不可点文字`() {
        assertTrue(CloseTargetValidator.isSafeDirectParent(close, parent(), screen))
        assertTrue(CloseTargetValidator.hasActionTarget(close, parent(), screen))
        assertFalse(CloseTargetValidator.hasActionTarget(close, null, screen))
        assertFalse(CloseTargetValidator.hasActionTarget(close, parent().copy(bounds = screen), screen))
    }

    @Test fun `安装或其他操作共享父容器时不能点父控件`() {
        for (other in listOf(
            NodeSnapshot(text = "立即安装", bounds = close.bounds),
            NodeSnapshot(text = "帮助", clickable = true, bounds = close.bounds),
            NodeSnapshot(text = "继续", bounds = close.bounds),
        )) assertFalse(CloseTargetValidator.isSafeDirectParent(close, parent().copy(children = listOf(close, other)), screen))
        assertFalse(CloseTargetValidator.isSafeDirectParent(close, parent().copy(text = "确认"), screen))
    }

    @Test fun `整页祖先禁用或隐藏父节点不能成为关闭动作目标`() {
        for (candidate in listOf(parent().copy(bounds = screen), parent().copy(enabled = false), parent().copy(visible = false))) {
            assertFalse(CloseTargetValidator.isSafeDirectParent(close, candidate, screen))
        }
    }

    @Test fun `关闭节点中的竞争动作也会阻止直接点击`() {
        val unsafe = close.copy(clickable = true, children = listOf(NodeSnapshot(text = "安装")))
        assertFalse(CloseTargetValidator.isSameTarget(unsafe, unsafe, screen))
    }
}
