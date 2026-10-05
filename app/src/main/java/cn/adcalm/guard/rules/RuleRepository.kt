package cn.adcalm.guard.rules

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则库的加载与解析。
 *
 * 规则存为 assets/rules/builtin.json，解析成 [RuleSet] 后缓存。
 * 解析失败时退化为空规则集——宁可没有规则（只靠启发式打分），
 * 也不能因为规则文件损坏导致整个服务不可用。
 */
object RuleRepository {

    private const val TAG = "AdCalm"
    private const val ASSET_NAME = "rules/builtin.json"

    @Volatile
    private var cached: RuleSet? = null

    fun load(context: Context): RuleSet {
        val existing = cached
        if (existing != null) return existing

        return synchronized(this) {
            val set = runCatching {
                val json = context.assets.open(ASSET_NAME)
                    .bufferedReader()
                    .use { it.readText() }
                parse(json)
            }.getOrElse { error ->
                Log.e(TAG, "规则库加载失败，退化为纯启发式模式", error)
                RuleSet(emptyList())
            }
            Log.i(TAG, "规则库已加载：${set.size} 条")
            cached = set
            set
        }
    }

    /** 供单元测试直接注入。 */
    fun installForTest(set: RuleSet) {
        cached = set
    }

    fun parse(json: String): RuleSet {
        val root = JSONObject(json)
        val array = root.optJSONArray("rules") ?: JSONArray()
        val rules = ArrayList<SplashRule>(array.length())

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val rule = parseRule(obj) ?: continue
            rules += rule
        }
        return RuleSet(rules)
    }

    private fun parseRule(obj: JSONObject): SplashRule? {
        val name = obj.optString("name").takeIf { it.isNotEmpty() } ?: return null

        val targets = ArrayList<RuleTarget>()
        obj.optJSONArray("targets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val type = MatchType.parse(t.optString("type")) ?: continue
                val value = t.optString("value").takeIf { it.isNotEmpty() } ?: continue
                val score = t.optInt("score", 0)
                if (score == 0) continue
                targets += RuleTarget(type, value, score)
            }
        }
        // 没有 target 的规则没有意义
        if (targets.isEmpty()) return null

        val blacklist = ArrayList<String>()
        obj.optJSONArray("blacklist")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotEmpty() }?.let { blacklist += it }
            }
        }

        val pkgs = ArrayList<String>()
        obj.optJSONArray("pkgs")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotEmpty() }?.let { pkgs += it }
            }
        }

        return SplashRule(
            name = name,
            pkg = obj.optString("pkg").takeIf { it.isNotEmpty() },
            pkgs = pkgs,
            activity = obj.optString("activity").takeIf { it.isNotEmpty() },
            containerClass = obj.optString("containerClass").takeIf { it.isNotEmpty() },
            targets = targets,
            blacklist = blacklist,
        )
    }
}
