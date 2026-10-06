package cn.adcalm.guard.core

import cn.adcalm.guard.model.NodeSnapshot

/** 执行动作前的语义复核。高分、规则、倒计时均不能替代完整关闭文案。 */
object ClickGate {
    enum class Reason {
        OK,
        INPUT_METHOD,
        NO_EVIDENCE,
        DISALLOWED_ACTION,
        UNAVAILABLE,
    }

    /**
     * [hasStrongEvidence] 必须来自 [ExplicitClosePolicy]；服务应传入 [snapshot] 再核验。
     * 保留原有两个参数，供只记录识别结论的调用方兼容。
     */
    fun evaluate(
        hasStrongEvidence: Boolean,
        inputMethodActive: Boolean,
        snapshot: NodeSnapshot? = null,
    ): Reason = when {
        inputMethodActive -> Reason.INPUT_METHOD
        snapshot != null && (!snapshot.visible || !snapshot.enabled || !snapshot.bounds.isValid) ->
            Reason.UNAVAILABLE
        snapshot != null && ExplicitClosePolicy.hasDisallowedAction(snapshot) -> Reason.DISALLOWED_ACTION
        !hasStrongEvidence -> Reason.NO_EVIDENCE
        snapshot != null && !ExplicitClosePolicy.hasEvidence(snapshot) -> Reason.NO_EVIDENCE
        else -> Reason.OK
    }
}
