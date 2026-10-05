package cn.adcalm.guard.core

import cn.adcalm.guard.core.ClickGate.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 动手前那道闸门的真值表。
 *
 * 这里的每一条都对应 2026-10-04 晚真机日志里的一个具体点击，不是推演出来的。
 * 那条日志的 27 次点击按"节点里有没有指向关闭按钮的证据"分开，界线很干净：
 *
 * - 有证据的（关闭语义，或观测到倒计时递减）**11 次全中**
 * - 只有位置证据（屏幕角落 20 + 容器角落 45 = 正好 65）的 **16 次里 15 次点错**
 *   （某短视频应用的拍摄页 / 看图片页 / 账号选择页、某游戏社区应用的发帖编辑页和 Flutter 页、某旅行应用首页）
 *
 * 所以本类的断言分两类：**带证据的一律放行**（不能把它们误伤），
 * **纯位置证据的一律拦下**（不管当时是什么语境——试过"只在开屏时放行"，
 * 那两个语境判据实测都不可靠，理由见 [ClickGate] 的类注释）。
 */
class ClickGateTest {

    // ---- 带证据的候选：放行 ----

    @Test
    fun `带关闭语义的候选放行`() {
        // 某浏览器的「跳过」：节点 clickable=false、没有 viewId，
        // 全靠文案本身跨过阈值。它必须照点不误。
        assertEquals(Reason.OK, ClickGate.evaluate(hasStrongEvidence = true, inputMethodActive = false))
    }

    @Test
    fun `倒计时证据的候选放行`() {
        // 某搜索应用的开屏倒计时「04」：文案里没有关闭词，但同一位置的数字在递减——
        // 那是有据可查的时序证据，和"它待在角落里"不是一回事。
        assertEquals(Reason.OK, ClickGate.evaluate(hasStrongEvidence = true, inputMethodActive = false))
    }

    // ---- 纯位置证据：一律拦下 ----

    @Test
    fun `只有位置证据的候选一律拦下`() {
        // 某短视频应用账号选择页那次：节点 text/desc/viewId 三样全空，
        // 靠「屏幕角落 20 + 容器角落 45」凑到 65 分，
        // 点中的是右上角的「帮助」，**页面真的被跳走了**。
        assertEquals(
            Reason.NO_EVIDENCE,
            ClickGate.evaluate(hasStrongEvidence = false, inputMethodActive = false),
        )
    }

    @Test
    fun `有 viewId 但名字里没有关闭语义，仍然算没有证据`() {
        // 某短视频应用那几次点错的节点是有 viewId 的，但叫 uu5 / ns1 这种混淆名，
        // 某游戏社区应用那次叫 ncv_check（一个复选框），某浏览器那次叫 negative_feedback2_layout
        // （「不感兴趣」，不是关闭）。"有 id"本身不是证据，"id 说得出这是什么"才是——
        // 这一点由 ConfidenceScorer 判定并写进 hasStrongEvidence，这里只钉闸门行为。
        assertEquals(
            Reason.NO_EVIDENCE,
            ClickGate.evaluate(hasStrongEvidence = false, inputMethodActive = false),
        )
    }

    // ---- 输入法：无歧义的一票否决 ----

    @Test
    fun `输入法在前台时一律不点——哪怕候选带证据`() {
        // 「关闭按钮不可能长在输入法窗口里」。这条比证据判断更硬，所以排在它前面。
        assertEquals(
            Reason.INPUT_METHOD,
            ClickGate.evaluate(hasStrongEvidence = true, inputMethodActive = true),
        )
    }

    @Test
    fun `输入法优先于证据判断`() {
        // 日志里那两次点击就发生在 SoftInputWindow 作为前台窗口的时刻——
        // 用户当时正在某短视频应用登录页输入手机号。带不带证据都该拦。
        assertEquals(
            Reason.INPUT_METHOD,
            ClickGate.evaluate(hasStrongEvidence = false, inputMethodActive = true),
        )
    }

    @Test
    fun `带证据的候选只会被输入法拦住`() {
        // 防回归：不能为了让误点少一点，就把"有证据也拦"的口子开在别处。
        // 两个条件各走一遍，把这条不变式钉死。
        assertEquals(Reason.OK, ClickGate.evaluate(hasStrongEvidence = true, inputMethodActive = false))
        assertEquals(Reason.INPUT_METHOD, ClickGate.evaluate(hasStrongEvidence = true, inputMethodActive = true))
    }
}
