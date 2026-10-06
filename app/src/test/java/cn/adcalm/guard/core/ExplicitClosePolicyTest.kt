package cn.adcalm.guard.core

import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ExplicitClosePolicyTest {
    @Test fun `完整文案允许有限包装和计时尾缀但不允许删掉正文`() {
        for (label in listOf("跳过广告 3s", "关闭广告", "(跳过广告", "【关闭广告】", " skip  ad ", "跳 过 广 告")) {
            assertTrue(label, ExplicitClosePolicy.isAdSpecificCloseText(label))
        }
        for (label in listOf("无法关闭广告", "notclose", "skipper", "关闭广告?", "跳过广告3次", "跳过\n广告", "skip\nad")) {
            assertFalse(label, ExplicitClosePolicy.isExplicitCloseText(label))
        }
    }

    @Test fun `大小写归一化不受系统语言影响`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertTrue(ExplicitClosePolicy.isAdSpecificCloseText("SKIP AD"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun `广告上下文不从类名或控件ID猜测`() {
        val generic = NodeSnapshot(text = "关闭", bounds = RectSnapshot(900, 100, 1040, 180),
            viewId = "ad_close", ancestorClassNames = listOf("SplashAd"), siblingClassNames = listOf("SplashView"))
        assertFalse(ExplicitClosePolicy.hasEvidence(generic))
        assertTrue(ExplicitClosePolicy.hasEvidence(generic.copy(siblingTexts = listOf("广告"))))
    }
}
