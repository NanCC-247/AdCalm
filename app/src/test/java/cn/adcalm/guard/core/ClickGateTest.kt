package cn.adcalm.guard.core

import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ClickGateTest {
    private val close = NodeSnapshot(text = "关闭广告", bounds = RectSnapshot(900, 100, 1040, 180))

    @Test fun `明确可用关闭放行`() {
        assertEquals(ClickGate.Reason.OK, ClickGate.evaluate(true, false, close))
    }

    @Test fun `输入法覆盖所有证据和动作`() {
        assertEquals(ClickGate.Reason.INPUT_METHOD, ClickGate.evaluate(true, true, close))
        assertEquals(ClickGate.Reason.INPUT_METHOD, ClickGate.evaluate(false, true))
    }

    @Test fun `不接受伪造的规则或倒计时证据标志`() {
        for (snapshot in listOf(
            close.copy(text = "3"), close.copy(text = "×"), close.copy(text = "关闭"),
            close.copy(text = null, viewId = "com.example:id/notclose"),
        )) {
            assertEquals(ClickGate.Reason.NO_EVIDENCE, ClickGate.evaluate(true, false, snapshot))
        }
    }

    @Test fun `原证据为假不能因文本复核而自动晋升`() {
        assertEquals(ClickGate.Reason.NO_EVIDENCE, ClickGate.evaluate(false, false, close))
    }

    @Test fun `付费安装会员确认继续一票否决`() {
        for (label in listOf("会员跳过", "关闭并安装", "确认关闭", "继续", "支付")) {
            assertEquals(label, ClickGate.Reason.DISALLOWED_ACTION, ClickGate.evaluate(true, false, close.copy(text = label)))
        }
    }

    @Test fun `隐藏禁用或空目标失效`() {
        for (snapshot in listOf(close.copy(visible = false), close.copy(enabled = false), close.copy(bounds = RectSnapshot.EMPTY))) {
            assertEquals(ClickGate.Reason.UNAVAILABLE, ClickGate.evaluate(true, false, snapshot))
        }
    }

    @Test fun `两个原参数仍兼容只记录结论的调用`() {
        assertEquals(ClickGate.Reason.OK, ClickGate.evaluate(true, false))
        assertEquals(ClickGate.Reason.NO_EVIDENCE, ClickGate.evaluate(false, false))
    }
}
