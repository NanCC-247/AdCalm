package cn.adcalm.guard.model

/** 打分过程中单条加减分的依据，用于日志里解释"为什么点/为什么不点"。 */
data class ScoreReason(val delta: Int, val label: String)

/** 对某个候选节点的处置结论。 */
enum class Verdict {
    /** 分数达标，执行点击 */
    CLICK,

    /** 分数不够，只记录不点击 */
    SUSPECT,

    /** 分数过低，忽略 */
    IGNORE,
}

/** 一个候选关闭按钮及其打分结果。 */
data class Candidate(
    /** 在节点树中的索引路径，用于回查真实节点 */
    val path: List<Int>,
    val snapshot: NodeSnapshot,
    val score: Int,
    val reasons: List<ScoreReason>,
    val verdict: Verdict,
    /**
     * 这个候选是否**自带**指向"关闭按钮"的证据，而不是只靠位置猜。
     *
     * 具体指：文本或描述含关闭语义、viewId 含 close/skip 这类词、观测到倒计时递减，
     * 或规则库命中。这四样都是"这个节点是什么"的证据。
     *
     * 与之相对的是**纯位置证据**——屏幕角落 + 位于较大容器的角上，共 65 分，
     * 正好等于点击线。这类候选的全部依据是"它待在一个角落里"，
     * 而任何界面里都有待在这个位置的控件。2026-10-04 的日志里它点中了某短视频应用登录页的
     * 「帮助」按钮并真的跳转了页面，详见 [cn.adcalm.guard.core.ClickGate]。
     */
    val hasStrongEvidence: Boolean,
    /**
     * 节点**自己写着**关闭语义：可见文案，或无障碍描述。
     *
     * 比 [hasStrongEvidence] 更窄——那里面还包含 viewId 关键词，而 id 是开发者随手起的，
     * 广告真拿它做过诱饵（`fl_skip_wrong` 这种"错误的跳过"）。
     * 用途是**同分时的排序依据**：一页摆两个关闭按钮时，文案是要给人看的、更难造假。
     * 2026-10-05 的红队用例把这个场景钉住了。
     */
    /**
     * 节点的**可见文案**里有明确的关闭语义（「跳过」「关闭」这类）。
     *
     * 用途是**同分时的排序依据**：一页摆两个关闭按钮时，谁在前谁中——那是运气，
     * 得挑一个更可信的。可见文案是要给人看的、最难造假，所以排最前。
     *
     * **刻意不含 `contentDescription`**，虽然那也是"节点自己说的"。
     * 2026-10-05 用广告样机实测踩到：诱饵把描述写成「关闭」（可见文案是空的）
     * 就骗过了第一版口径（那时描述也算），于是同分之下还是按树序选，诱饵照样中选。
     * 无障碍描述看不见、可以随便写，不能和"用户能看到的那几个字"同等对待。
     */
    val hasTextualClose: Boolean = false,
) {
    /** 日志用的可读摘要。 */
    fun describe(): String = buildString {
        append("score=").append(score).append(' ').append(verdict)
        append(" id=").append(snapshot.viewId ?: "-")
        append(" text=").append(snapshot.text ?: "-")
        append(" bounds=").append(snapshot.bounds)
        append(" [").append(reasons.joinToString(", ") { "${it.delta}:${it.label}" }).append(']')
    }
}
