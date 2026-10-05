package cn.adcalm.guard.data

/**
 * 从观察日志里数出"跳过了一次广告"的次数。
 *
 * 为什么需要它：这个工具最致命的一种坏法不是点错，而是**静默失效**——
 * 无障碍服务被 ROM 掐掉之后，开关看着是开的，实际一次都不触发。
 * 用户唯一的感受是"这软件没用"，而界面上没有任何东西能反驳他。
 * （2026-10-04 实测：`adb install -r` 每次都会把无障碍绑定清成 null，一天踩了三次。）
 *
 * 首页那行「后台已挂起 / 未挂起」解决的是"服务还活着吗"，
 * 这里解决的是"活着，但它到底干活了没有"。
 *
 * 纯函数，输入是一行行日志文本，可单元测试。
 */
object LogStats {

    /**
     * 计入"跳过了一次"的 decision 值。
     *
     * 只算真的点下去并且成功的。`WOULD_CLICK`（观察模式）、`COOLDOWN`、
     * `CLICK_FAILED`、`HELD_*`（被闸门拦下）都不算——把它们算进来，
     * 用户会看到一个"跳过了 50 次"的数字，而实际上一次都没点。
     */
    val CLICK_DECISIONS = setOf("CLICKED", "OCR_CLICKED")

    /**
     * 只看每行开头这么多字符。
     *
     * 字段顺序是固定的（见 [ObservationLog] 的 toJson），`decision` 一定排在
     * `candidates` 和 `tree` 之前，实测在 200 字符以内。而诊断模式写下的
     * TREE_DUMP 行可以有上万字符——那些才是日志体积的大头，
     * 不截断的话每次刷新首页都要把整个文件逐字节扫一遍。
     */
    private const val HEADER_PROBE_CHARS = 600

    /**
     * 数出 [sinceMs] 之后发生的点击次数。
     *
     * @param lines 逐行的 JSONL 文本，惰性求值（`useLines` 那种），
     *              这样即便日志有几百 MB 也不会一次性读进内存
     */
    fun countClicks(lines: Sequence<String>, sinceMs: Long): Int {
        var n = 0
        for (line in lines) {
            val ts = timestampOf(line) ?: continue
            if (ts < sinceMs) continue
            if (decisionOf(line) in CLICK_DECISIONS) n++
        }
        return n
    }

    /** 从一行 JSONL 里取出 `ts`。取不到（截断、脏行）返回 null。 */
    fun timestampOf(line: String): Long? {
        val head = line.take(HEADER_PROBE_CHARS)
        val key = head.indexOf("\"ts\":")
        if (key < 0) return null
        val start = key + 5
        var end = start
        while (end < head.length && head[end].isDigit()) end++
        if (end == start) return null
        return head.substring(start, end).toLongOrNull()
    }

    /** 从一行 JSONL 里取出 `decision`。 */
    fun decisionOf(line: String): String? {
        val head = line.take(HEADER_PROBE_CHARS)
        val key = head.indexOf("\"decision\":\"")
        if (key < 0) return null
        val start = key + 12
        val end = head.indexOf('"', start)
        if (end < 0) return null
        return head.substring(start, end)
    }
}
