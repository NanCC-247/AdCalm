package cn.adcalm.guard.data

import cn.adcalm.guard.model.Candidate
import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.RectSnapshot
import cn.adcalm.guard.model.ScoreReason
import cn.adcalm.guard.model.Verdict
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationPrivacyTest {
    @Test
    fun `默认候选摘要不保留任何自由文本入口`() {
        val candidate = Candidate(
            path = listOf(1),
            snapshot = NodeSnapshot(
                text = "账户 alice_2026 手机 +86 13800138000",
                contentDescription = "alice@example.com https://example.com/?token=secret",
                viewId = "com.private.app:id/alice_2026",
                ancestorClassNames = listOf("C:\\Users\\Alice\\Downloads\\invoice.pdf"),
                bounds = RectSnapshot(0, 20, 60, 80),
                clickable = true,
            ),
            score = 75,
            reasons = listOf(ScoreReason(55, "文本为 ${"alice_2026"}"), ScoreReason(20, "角落")),
            verdict = Verdict.CLICK,
            hasStrongEvidence = true,
            hasTextualClose = false,
        )
        val summary = ObservationPrivacy.candidateSummary(candidate)
        assertNoPrivateText(summary.toString())
        assertFalse(summary.has("text"))
        assertFalse(summary.has("desc"))
        assertFalse(summary.has("viewId"))
        assertFalse(summary.has("ancestors"))
        assertFalse(summary.has("reasons"))
        assertEquals(75, summary.getInt("score"))
        assertEquals(listOf(55, 20), summary.getJSONArray("reasonDeltas").let {
            (0 until it.length()).map(it::getInt)
        })
        assertTrue(summary.getBoolean("hasStrongEvidence"))
        assertTrue(summary.getBoolean("textPresent"))
    }

    @Test
    fun `旧原始日志也经过白名单并删除树截图标识和未知嵌套字段`() {
        val source = legacyRow("com.private.app")
        val line = ObservationPrivacy.ExportSession().summarizeLine(source.toString())
        assertNotNull(line)
        assertNoPrivateText(line!!)
        val summary = JSONObject(line)
        assertEquals("app_1", summary.getString("app"))
        assertEquals("CLICKED", summary.getString("decision"))
        assertEquals(1_700_000_000_000L, summary.getLong("ts"))
        assertFalse(summary.has("pkg"))
        assertFalse(summary.has("activity"))
        assertFalse(summary.has("tree"))
        assertFalse(summary.has("snapshot"))
        assertFalse(summary.has("unknownDiagnostic"))
        assertTrue(summary.getBoolean("diagnosticRecorded"))
        val candidate = summary.getJSONArray("candidates").getJSONObject(0)
        assertEquals(55, candidate.getJSONArray("reasonDeltas").getInt(0))
        assertEquals(4, candidate.getJSONArray("bounds").length())
        assertEquals(1, LogStats.countClicks(sequenceOf(line), sinceMs = 0))
    }

    @Test
    fun `应用代号只在本次导出内保持一致且没有可恢复映射表`() {
        val session = ObservationPrivacy.ExportSession()
        fun alias(pkg: String) = JSONObject(session.summarizeLine(legacyRow(pkg).toString())!!).getString("app")
        assertEquals("app_1", alias("com.private.app"))
        assertEquals("app_2", alias("com.other.app"))
        assertEquals("app_1", alias("com.private.app"))
        val fresh = ObservationPrivacy.ExportSession().summarizeLine(legacyRow("com.other.app").toString())!!
        assertEquals("app_1", JSONObject(fresh).getString("app"))
        assertFalse(fresh.contains("com.other.app"))
    }

    @Test
    fun `无法解析的行不会以原文回退导出`() {
        val session = ObservationPrivacy.ExportSession()
        listOf("", "alice@example.com", "{\"tree\":\"secret\"", "{}",
            "{\"ts\":\"13800138000\",\"text\":\"secret\"}").forEach {
            assertNull(session.summarizeLine(it))
        }
        assertNotNull(session.summarizeLine(legacyRow("com.private.app").toString()))
    }

    @Test
    fun `看似元数据的字段无法带出账户URL或路径`() {
        val row = legacyRow("com.private.app")
            .put("decision", "alice_2026")
            .put("clickMethod", "https://example.com/?token=secret")
            .put("screen", "C:\\Users\\Alice\\invoice.pdf")
        row.getJSONArray("candidates").getJSONObject(0)
            .put("verdict", "alice@example.com")
            .put("bounds", "[0,20,60,80] token=secret")
            .put("reasonDeltas", JSONArray().put("alice_2026").put("55:alice@example.com"))
        val summary = JSONObject(ObservationPrivacy.ExportSession().summarizeLine(row.toString())!!)
        assertNoPrivateText(summary.toString())
        assertEquals("UNKNOWN", summary.getString("decision"))
        assertFalse(summary.has("clickMethod"))
        assertFalse(summary.has("screen"))
        assertFalse(summary.getJSONArray("candidates").getJSONObject(0).has("bounds"))
        assertFalse(summary.getJSONArray("candidates").getJSONObject(0).has("verdict"))
    }

    @Test
    fun `本机合法包名仍保留并拒绝含路径或账号的伪包名`() {
        assertEquals("com.example.app", ObservationPrivacy.localPackageName("com.example.app"))
        assertEquals("unknown", ObservationPrivacy.localPackageName("alice@example.com"))
        assertEquals("unknown", ObservationPrivacy.localPackageName("C:\\Users\\Alice\\app.apk"))
    }

    private fun legacyRow(pkg: String): JSONObject = JSONObject().apply {
        put("ts", 1_700_000_000_000L)
        put("pkg", pkg)
        put("activity", "alice_2026")
        put("decision", "CLICKED")
        put("dryRun", false)
        put("screen", "1080 x 2400")
        put("tree", JSONArray().put(JSONObject().put("text", "alice@example.com")))
        put("snapshot", "snap_1700000000000.jpg")
        put("unknownDiagnostic", JSONObject().put("path", "C:\\Users\\Alice\\invoice.pdf"))
        put("candidates", JSONArray().put(JSONObject().apply {
            put("score", 75)
            put("verdict", "CLICK")
            put("text", "alice_2026 13800138000")
            put("desc", "alice@example.com")
            put("viewId", "com.private.app:id/alice_2026")
            put("reasons", JSONArray().put("55:短文本 https://example.com/?token=secret"))
            put("bounds", "[0,20,60,80]")
            put("clickable", true)
        }))
    }

    private fun assertNoPrivateText(output: String) {
        listOf("alice_2026", "13800138000", "alice@example.com", "token=secret", "Users",
            "invoice.pdf", "com.private.app", "snap_1700000000000").forEach {
            assertFalse("摘要泄漏了 $it", output.contains(it))
        }
    }
}
