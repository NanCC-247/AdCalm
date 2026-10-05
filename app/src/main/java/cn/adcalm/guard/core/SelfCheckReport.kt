package cn.adcalm.guard.core

/**
 * 「能力自检」报告的文字格式。
 *
 * ## 这个页面为什么存在
 *
 * 这个项目所有真机结论都来自同一台设备——那是它最大的结构性弱点，而"换台机器验一遍"
 * 之所以一直没做，不是因为它不重要，是因为**成本卡在"要手工逐项试"**：
 * 无障碍事件、截图通道、手势注入、强停的两条路、OCR 引擎……每一项在别的 ROM 上
 * 都可能悄悄不通，而**不通的样子全都是"界面一切正常"**。
 *
 * 所以把"逐项试一遍"做成一个页面：一次跑完，每项给出通过/失败**加原始证据**，
 * 整份可以复制出来。下次换机、或者临时借到任何一台设备，一分钟就能出结论。
 *
 * ## 这里只负责排版
 *
 * 纯函数，不接触任何 Android 类，可直接单元测试。
 * 真正去跑那些检查的是 `SelfCheckActivity`。
 */
object SelfCheckReport {

    /** 一项检查的结论。 */
    enum class Outcome(val label: String) {
        /** 确实跑通了，且拿到了证据。 */
        PASS("通过"),

        /** 确实跑不通。**这一档要带着原始证据**，否则和"没测"分不清。 */
        FAIL("失败"),

        /**
         * 测不了，或者这台机器上无从判断（例如没有 Shizuku，强停的快路就没法验）。
         *
         * 单列一档是刻意的：把"没测"混进"失败"会虚报问题，混进"通过"会虚报能力——
         * 而这个项目最怕的恰恰是**虚报能力**。
         */
        UNKNOWN("未知"),
    }

    /** 一项检查：名字、结论、以及**原始证据**（一行，写清楚看到了什么）。 */
    data class Check(val name: String, val outcome: Outcome, val detail: String)

    /**
     * @param headerLines 表头，逐行原样输出（构建号、时间、机型这类"这份报告是在哪儿生成的"）
     * @param checks      按实际执行顺序排列
     */
    fun render(headerLines: List<String>, checks: List<Check>): String = buildString {
        headerLines.forEach { appendLine(it) }
        appendLine(SEPARATOR)
        checks.forEach { check ->
            appendLine("[${check.outcome.label}] ${check.name}")
            appendLine("       ${check.detail}")
        }
        appendLine(SEPARATOR)
        appendLine(summaryOf(checks))
    }

    /**
     * 末尾那行小结。
     *
     * **三项都报出来**，包括 0 —— "0 项失败"本身就是结论，省略掉反而让人怀疑是没统计。
     */
    fun summaryOf(checks: List<Check>): String {
        val counts = Outcome.entries.associateWith { o -> checks.count { it.outcome == o } }
        val parts = Outcome.entries.joinToString(" / ") { "${counts.getValue(it)} 项${it.label}" }
        return "小结：$parts"
    }

    private const val SEPARATOR = "────────────"
}
