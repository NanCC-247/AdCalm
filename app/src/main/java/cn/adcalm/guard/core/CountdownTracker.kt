package cn.adcalm.guard.core

import cn.adcalm.guard.model.NodeSnapshot

/**
 * 倒计时识别。
 *
 * 开屏广告的跳过按钮通常长这样："跳过 3" → "跳过 2" → "跳过 1"，
 * 或者按钮文字本身就是裸数字在递减。同一个节点在短时间内数值变小，
 * 是"这是倒计时跳过按钮"的强信号——广告主几乎不会在诱导下载按钮上做倒计时。
 */
class CountdownTracker(private val windowMs: Long = 1_500L) {

    private data class Sample(val value: Int, val atMs: Long)

    private val last = HashMap<String, Sample>()

    /** 返回 true 表示相对上一次样本发生了数值递减。 */
    fun observe(signature: String, text: String?, nowMs: Long): Boolean {
        val value = parseValue(text) ?: return false
        val previous = last[signature]
        last[signature] = Sample(value, nowMs)

        if (last.size > MAX_TRACKED) {
            val cutoff = nowMs - windowMs * 4
            last.entries.removeAll { it.value.atMs < cutoff }
        }

        if (previous == null) return false
        val fresh = nowMs - previous.atMs <= windowMs
        return fresh && value < previous.value
    }

    fun reset() = last.clear()

    /**
     * 节点在多次观测之间的身份标识。
     *
     * **必须带位置。** 只按 viewId 认会出事：列表（RecyclerView）里的条目共用同一个
     * viewId。2026-10-03 的真机日志里，一棵树内 `…:id/rank_item_game_index` 出现
     * **4 次**，值分别是 1 / 2 / 3 / 4；扫描顺序一变，同一签名就给出"数值变小"，
     * 于是普通榜单序号被当成倒计时跳过按钮。
     *
     * 那次日志里这个误判贡献了 **5 次错误点击**（点的是浏览器和榜单里的普通数字），
     * 而真广告一次都没靠它点中。带上位置之后，只有**同一位置**的数字变小才算数——
     * 那才是倒计时真正的样子。
     */
    fun signatureOf(node: NodeSnapshot): String =
        "${node.viewId.orEmpty()}@${node.className.orEmpty()}@${node.bounds}"

    private fun parseValue(text: String?): Int? {
        val trimmed = text?.trim() ?: return null
        val match = PATTERN.matchEntire(trimmed) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    companion object {
        /** 匹配 "3" / "3s" / "3秒" 这类倒计时文案。 */
        private val PATTERN = Regex("^(\\d{1,2})\\s*[sS秒]?$")
        private const val MAX_TRACKED = 256
    }
}
