package cn.adcalm.guard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 首页那行「今天已跳过 N 次」的计数逻辑。
 *
 * 这条数字是用户判断"这工具到底干活了没有"的唯一依据，所以数错的代价是双向的：
 * 少数了会让一个正常工作的工具看起来是坏的，多数了（把"打算点"算成"点掉了"）
 * 则会让一个静默失效的工具看起来是好的——后者更糟。
 */
class LogStatsTest {

    /** 照 [ObservationLog] 里 toJson 的字段顺序拼一行，保证测试和真实格式一致。 */
    private fun line(
        ts: Long,
        decision: String,
        tree: String? = null,
        candidates: String = "[]",
    ): String = buildString {
        append("""{"ts":$ts,"time":"10-04 22:12:51","pkg":"com.example.app",""")
        append(""""activity":"com.example.app.MainActivity","screen":"1080 x 2400",""")
        append(""""decision":"$decision","dryRun":false""")
        if (tree != null) append(""","tree":$tree""")
        append(""","candidates":$candidates}""")
    }

    @Test
    fun `只数真正点下去的`() {
        val lines = sequenceOf(
            line(1000, "CLICKED"),
            line(1001, "OCR_CLICKED"),
            line(1002, "WOULD_CLICK"),
            line(1003, "SUSPECT_ONLY"),
            line(1004, "COOLDOWN"),
            line(1005, "CLICK_FAILED"),
            line(1006, "NO_CANDIDATE"),
        )
        assertEquals(2, LogStats.countClicks(lines, sinceMs = 0))
    }

    @Test
    fun `被闸门拦下的不算点过`() {
        // 界面上要回答的是"今天跳过了几次"。被闸门拦下意味着**一次都没点**，
        // 把它们算进去就是虚报。
        val lines = sequenceOf(
            line(1000, "HELD_NO_EVIDENCE"),
            line(1001, "HELD_INPUT_METHOD"),
            line(1002, "OUT_OF_SCOPE"),
            line(1003, "ROLLBACK"),
        )
        assertEquals(0, LogStats.countClicks(lines, sinceMs = 0))
    }

    @Test
    fun `只数起点之后的`() {
        val lines = sequenceOf(
            line(1000, "CLICKED"),
            line(5000, "CLICKED"),
            line(9000, "CLICKED"),
        )
        assertEquals(2, LogStats.countClicks(lines, sinceMs = 5000))
    }

    @Test
    fun `起点那一毫秒算在内`() {
        // 边界：用户 0 点整点开的记录不该被漏掉
        assertEquals(1, LogStats.countClicks(sequenceOf(line(5000, "CLICKED")), sinceMs = 5000))
    }

    @Test
    fun `诊断模式那种上万字符的行不会把 decision 挤出去`() {
        // TREE_DUMP 行带着整棵节点树，是日志体积的大头。
        // 计数只看行首固定长度，所以必须先确认 decision 一定落在那个窗口里。
        val bigTree = "[" + (1..4000).joinToString(",") { """{"id":"x$it","text":"","cls":"android.view.View"}""" } + "]"
        val lines = sequenceOf(
            line(1000, "TREE_DUMP", tree = bigTree),
            line(1001, "CLICKED", tree = bigTree),
        )
        assertEquals(1, LogStats.countClicks(lines, sinceMs = 0))
    }

    @Test
    fun `脏行不会让计数崩掉`() {
        val lines = sequenceOf(
            "",
            "{",
            """{"ts":notanumber,"decision":"CLICKED"}""",
            "not json at all",
            line(1000, "CLICKED"),
        )
        assertEquals(1, LogStats.countClicks(lines, sinceMs = 0))
    }

    @Test
    fun `取不出字段时返回 null 而不是抛异常`() {
        assertNull(LogStats.timestampOf(""))
        assertNull(LogStats.timestampOf("""{"decision":"CLICKED"}"""))
        assertNull(LogStats.timestampOf("""{"ts":}"""))
        assertNull(LogStats.decisionOf("""{"ts":1}"""))
    }

    @Test
    fun `同一行里先出现的 ts 不会被 candidates 里的数字顶掉`() {
        // 候选里也有一堆数字（score、bounds），字段名必须精确匹配。
        val withCandidates = line(
            ts = 1234,
            decision = "CLICKED",
            candidates = """[{"score":65,"bounds":"[1,2,3,4]","ts":99999}]""",
        )
        assertEquals(1234L, LogStats.timestampOf(withCandidates))
        assertEquals("CLICKED", LogStats.decisionOf(withCandidates))
    }
}
