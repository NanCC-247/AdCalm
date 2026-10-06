package cn.adcalm.guard.data

import cn.adcalm.guard.model.Candidate
import org.json.JSONArray
import org.json.JSONObject

/**
 * 日志隐私边界：只构造允许公开的字段，不尝试用正则从整页文字中找出所有个人信息。
 *
 * 这里不接触 Android 或文件系统。旧日志和新日志都经过同一白名单再导出；未知字段、
 * 原文、节点树、截图名和评分原因的自由文本都不会进入摘要。
 */
object ObservationPrivacy {
    private val decisions = setOf(
        "NO_CANDIDATE", "SUSPECT_ONLY", "WOULD_CLICK", "CLICKED", "CLICK_FAILED",
        "COOLDOWN", "ROLLBACK", "TREE_DUMP", "OCR_CLICKED", "OCR_WOULD_CLICK",
        "OUT_OF_SCOPE", "HELD_NO_EVIDENCE", "HELD_INPUT_METHOD", "OCR_STALE",
        "IN_APP_NAV_AFTER_AD",
    )
    private val methods = setOf("ACTION_CLICK", "GESTURE")
    private val verdicts = setOf("CLICK", "SUSPECT", "IGNORE")
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val screenPattern = Regex("(\\d{1,6}) x (\\d{1,6})")
    private val boundsPattern = Regex("\\[(-?\\d{1,6}),(-?\\d{1,6}),(-?\\d{1,6}),(-?\\d{1,6})]")
    private val reasonDeltaPattern = Regex("^(-?\\d{1,6}):")

    fun safeDecision(value: String): String = value.takeIf { it in decisions } ?: "UNKNOWN"
    fun safeClickMethod(value: String?): String? = value?.takeIf { it in methods }

    /** 包名只用于本机统计。活动名、资源 ID、类名都不需要保存在默认日志里。 */
    fun localPackageName(value: String): String =
        value.takeIf { it.length <= 255 && packagePattern.matches(it) } ?: "unknown"

    fun candidateSummary(candidate: Candidate): JSONObject = JSONObject().apply {
        put("score", candidate.score)
        put("verdict", candidate.verdict.name)
        put("clickable", candidate.snapshot.clickable)
        put("bounds", JSONArray(listOf(
            candidate.snapshot.bounds.left, candidate.snapshot.bounds.top,
            candidate.snapshot.bounds.right, candidate.snapshot.bounds.bottom,
        )))
        put("hasStrongEvidence", candidate.hasStrongEvidence)
        put("hasTextualClose", candidate.hasTextualClose)
        put("textPresent", !candidate.snapshot.text.isNullOrEmpty())
        put("descriptionPresent", !candidate.snapshot.contentDescription.isNullOrEmpty())
        put("viewIdPresent", !candidate.snapshot.viewId.isNullOrEmpty())
        // 原因标签可能插入原始页面文案或自定义规则内容；只留下数值贡献。
        put("reasonDeltas", JSONArray(candidate.reasons.take(MAX_REASONS).map { it.delta }))
    }

    /** 每次导出单独分配 app_1 等代号；不导出映射表，也不使用可跨导出追踪的包名哈希。 */
    class ExportSession {
        private val appAliases = linkedMapOf<String, String>()

        /** 无法解析的旧行直接跳过，绝不回退成复制原文。 */
        fun summarizeLine(line: String): String? = runCatching {
            val source = JSONObject(line)
            val timestamp = (source.opt("ts") as? Number)?.toLong()
                ?.takeIf { it >= 0 } ?: return@runCatching null
            val rawPackage = source.opt("pkg") as? String ?: ""
            val alias = appAliases.getOrPut(rawPackage) { "app_${appAliases.size + 1}" }
            JSONObject().apply {
                put("schema", 1)
                put("privacy", "summary")
                put("ts", timestamp)
                put("app", alias)
                put("decision", safeDecision(source.optString("decision")))
                (source.opt("dryRun") as? Boolean)?.let { put("dryRun", it) }
                safeClickMethod(source.opt("clickMethod") as? String)?.let { put("clickMethod", it) }
                screenNumbers(source.opt("screen"))?.let { put("screen", JSONArray(it)) }
                put("diagnosticRecorded", source.has("tree") || source.has("snapshot"))
                put("candidates", JSONArray().apply {
                    val candidates = source.optJSONArray("candidates") ?: JSONArray()
                    for (i in 0 until minOf(candidates.length(), MAX_CANDIDATES)) {
                        candidates.optJSONObject(i)?.let { put(exportCandidate(it)) }
                    }
                })
            }.toString()
        }.getOrNull()
    }

    private fun exportCandidate(source: JSONObject): JSONObject = JSONObject().apply {
        (source.opt("score") as? Number)?.let { put("score", it.toInt()) }
        source.optString("verdict").takeIf { it in verdicts }?.let { put("verdict", it) }
        listOf("clickable", "hasStrongEvidence", "hasTextualClose", "textPresent",
            "descriptionPresent", "viewIdPresent").forEach { key ->
            (source.opt(key) as? Boolean)?.let { put(key, it) }
        }
        boundsNumbers(source.opt("bounds"))?.let { put("bounds", JSONArray(it)) }
        // 支持旧的 "55:短文本…" 格式，但从不保留冒号后面的自由文本。
        val deltas = source.optJSONArray("reasonDeltas") ?: source.optJSONArray("reasons")
        if (deltas != null) put("reasonDeltas", JSONArray().apply {
            for (i in 0 until minOf(deltas.length(), MAX_REASONS)) {
                val value = deltas.opt(i)
                val delta = when (value) {
                    is Number -> value.toInt()
                    is String -> reasonDeltaPattern.find(value)?.groupValues?.get(1)?.toIntOrNull()
                    else -> null
                }
                delta?.takeIf { it in -100_000..100_000 }?.let { put(it) }
            }
        })
    }

    private fun screenNumbers(value: Any?): List<Int>? {
        val numbers = if (value is String) {
            screenPattern.matchEntire(value)?.groupValues?.drop(1)?.map { it.toInt() }
        } else numericArray(value, 2)
        return numbers?.takeIf { it.all { n -> n in 1..100_000 } }
    }

    private fun boundsNumbers(value: Any?): List<Int>? {
        val numbers = if (value is String) {
            boundsPattern.matchEntire(value)?.groupValues?.drop(1)?.map { it.toInt() }
        } else numericArray(value, 4)
        return numbers?.takeIf { it.all { n -> n in -100_000..100_000 } }
    }

    private fun numericArray(value: Any?, count: Int): List<Int>? {
        val array = value as? JSONArray ?: return null
        if (array.length() != count) return null
        return (0 until count).map { (array.opt(it) as? Number)?.toInt() ?: return null }
    }

    private const val MAX_CANDIDATES = 20
    private const val MAX_REASONS = 30
}
