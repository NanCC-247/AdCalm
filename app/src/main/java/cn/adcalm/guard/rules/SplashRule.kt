package cn.adcalm.guard.rules

import cn.adcalm.guard.model.NodeSnapshot

/** 规则对节点的匹配方式。 */
enum class MatchType {
    ID_EXACT,
    ID_CONTAINS,
    TEXT_EXACT,
    TEXT_CONTAINS,
    DESC_CONTAINS,
    ;

    fun matches(node: NodeSnapshot, value: String): Boolean = when (this) {
        ID_EXACT -> node.viewId.equals(value, ignoreCase = true)
        ID_CONTAINS -> node.viewId?.contains(value, ignoreCase = true) == true
        TEXT_EXACT -> node.text?.trim().equals(value, ignoreCase = true)
        TEXT_CONTAINS -> node.text?.contains(value, ignoreCase = true) == true
        DESC_CONTAINS -> node.contentDescription?.contains(value, ignoreCase = true) == true
    }

    companion object {
        fun parse(raw: String): MatchType? = when (raw.lowercase()) {
            "id_exact" -> ID_EXACT
            "id_contains" -> ID_CONTAINS
            "text_exact" -> TEXT_EXACT
            "text_contains" -> TEXT_CONTAINS
            "desc_contains" -> DESC_CONTAINS
            else -> null
        }
    }
}

data class RuleTarget(val type: MatchType, val value: String, val score: Int)

/**
 * 一条开屏广告规则。
 *
 * 作用域由 [pkg] / [pkgs] / [activity] / [containerClass] 中至少一个限定。
 * [containerClass] 是广告 SDK 的容器类名——按 SDK 特征写规则比按单个 App 写稳定得多，
 * 因为 SDK 升级时 viewId 的改动通常小于各家 App 自己的改动频率。
 *
 * [pkgs] 用于批量覆盖：几十个 App 的关闭按钮形态往往一样，写成一条带包名数组的规则
 * 比复制几十份 JSON 好维护。
 */
data class SplashRule(
    val name: String,
    val pkg: String? = null,
    val pkgs: List<String> = emptyList(),
    val activity: String? = null,
    val containerClass: String? = null,
    val targets: List<RuleTarget> = emptyList(),
    /** 命中即否决：这些 id 指向广告本身，点它等于点广告。 */
    val blacklist: List<String> = emptyList(),
)

/** 规则匹配结果。 */
sealed interface RuleResult {
    data class Hit(val ruleName: String, val score: Int, val detail: String) : RuleResult
    data class Veto(val ruleName: String, val detail: String) : RuleResult
}

/**
 * 一组规则及其匹配逻辑。
 *
 * 纯函数实现，输入 [NodeSnapshot]，不接触 Android 类，可直接单元测试。
 */
class RuleSet(val rules: List<SplashRule>) {

    /**
     * 返回该节点命中的最高分规则。
     *
     * 任一规则的 [SplashRule.blacklist] 命中即返回 [RuleResult.Veto]——
     * 否决优先于加分，因为"点到广告本身"的代价远大于漏点。
     */
    fun match(node: NodeSnapshot, pkg: String, activity: String?): RuleResult? {
        var best: RuleResult.Hit? = null

        for (rule in rules) {
            if (!rule.appliesTo(pkg, activity, node)) continue

            for (blocked in rule.blacklist) {
                if (node.viewId?.contains(blocked, ignoreCase = true) == true) {
                    return RuleResult.Veto(rule.name, "命中黑名单 $blocked")
                }
            }

            for (target in rule.targets) {
                if (target.type.matches(node, target.value)) {
                    if (best == null || target.score > best.score) {
                        best = RuleResult.Hit(
                            rule.name,
                            target.score,
                            "${rule.name} → ${target.type}:${target.value}",
                        )
                    }
                }
            }
        }
        return best
    }

    private fun SplashRule.appliesTo(
        actualPackage: String,
        actualActivity: String?,
        node: NodeSnapshot,
    ): Boolean {
        // 作用域必须至少限定一项，否则规则会全局生效，太危险。
        // 注意这里必须判断规则自身的字段（this.xxx），不能被同名参数遮蔽。
        if (this.pkg == null && this.pkgs.isEmpty() &&
            this.activity == null && this.containerClass == null
        ) {
            return false
        }

        val pkgOk = when {
            this.pkg != null -> this.pkg.equals(actualPackage, ignoreCase = true)
            this.pkgs.isNotEmpty() ->
                this.pkgs.any { it.equals(actualPackage, ignoreCase = true) }
            else -> true
        }

        val activityOk = this.activity?.let { pattern ->
            actualActivity?.contains(pattern, ignoreCase = true) == true
        } ?: true

        val containerOk = this.containerClass?.let { cls ->
            node.ancestorClassNames.any { it.contains(cls, ignoreCase = true) }
        } ?: true

        // 被限定的那些条件必须同时满足
        return pkgOk && activityOk && containerOk
    }

    val size: Int get() = rules.size
}
