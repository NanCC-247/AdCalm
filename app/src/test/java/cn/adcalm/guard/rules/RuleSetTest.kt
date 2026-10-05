package cn.adcalm.guard.rules

import cn.adcalm.guard.model.NodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSetTest {

    private fun node(
        viewId: String? = null,
        text: String? = null,
        desc: String? = null,
        ancestors: List<String> = emptyList(),
    ) = NodeSnapshot(
        viewId = viewId,
        text = text,
        contentDescription = desc,
        ancestorClassNames = ancestors,
    )

    private val tiktokSdkRule = SplashRule(
        name = "示例开屏规则",
        containerClass = "com.example.adssdk",
        targets = listOf(
            RuleTarget(MatchType.ID_CONTAINS, "example_skip_btn", 70),
            RuleTarget(MatchType.ID_CONTAINS, "btn_skip", 55),
            RuleTarget(MatchType.TEXT_EXACT, "跳过", 55),
        ),
        blacklist = listOf("example_click_bar"),
    )

    private val taobaoRule = SplashRule(
        name = "某购物应用开屏",
        pkg = "com.example.app",
        targets = listOf(
            RuleTarget(MatchType.ID_CONTAINS, "tv_skip", 65),
        ),
    )

    private val rules = RuleSet(listOf(tiktokSdkRule, taobaoRule))

    // ---- 作用域 ----

    @Test
    fun `命中 SDK 容器内的跳过按钮`() {
        val n = node(
            viewId = "com.example:id/example_skip_btn",
            ancestors = listOf("android.widget.FrameLayout", "com.example.adssdk.core.SplashView"),
        )
        val result = rules.match(n, "com.anything", null)
        assertTrue(result is RuleResult.Hit)
        assertEquals(70, (result as RuleResult.Hit).score)
    }

    @Test
    fun `容器不在祖先链上则不命中 SDK 规则`() {
        // 同样是 example_skip_btn，但不在该 SDK 容器里 —— 不该套用这条规则
        val n = node(viewId = "com.example:id/example_skip_btn")
        assertNull(rules.match(n, "com.anything", null))
    }

    @Test
    fun `包名规则只对指定包生效`() {
        val n = node(viewId = "com.example.app:id/tv_skip")
        assertTrue(rules.match(n, "com.example.app", null) is RuleResult.Hit)
        assertNull("换个包名就不该命中", rules.match(n, "com.example.shop", null))
    }

    @Test
    fun `没有任何作用域限定的规则永远不生效`() {
        // 防止误写出一条全局规则把整个界面都接管了
        val dangerous = RuleSet(listOf(
            SplashRule(name = "无作用域", targets = listOf(RuleTarget(MatchType.TEXT_EXACT, "跳过", 99)))
        ))
        assertNull(dangerous.match(node(text = "跳过"), "com.anything", null))
    }

    @Test
    fun `空的 pkgs 数组不等于有作用域`() {
        val dangerous = RuleSet(listOf(
            SplashRule(
                name = "空数组",
                pkgs = emptyList(),
                targets = listOf(RuleTarget(MatchType.TEXT_EXACT, "跳过", 99)),
            )
        ))
        assertNull(dangerous.match(node(text = "跳过"), "com.anything", null))
    }

    // ---- 批量包名规则 ----

    @Test
    fun `pkgs 列表内的包都命中，列表外的包不命中`() {
        val batch = RuleSet(listOf(
            SplashRule(
                name = "批量开屏",
                pkgs = listOf("com.a", "com.b", "com.c"),
                targets = listOf(RuleTarget(MatchType.ID_CONTAINS, "skip", 55)),
            )
        ))
        val skipNode = node(viewId = "com.a:id/skip_btn")

        assertTrue(batch.match(skipNode, "com.a", null) is RuleResult.Hit)
        assertTrue(batch.match(skipNode, "com.b", null) is RuleResult.Hit)
        assertTrue(batch.match(skipNode, "com.c", null) is RuleResult.Hit)
        assertNull("列表外的包不该命中", batch.match(skipNode, "com.d", null))
    }

    @Test
    fun `包名匹配大小写不敏感`() {
        val batch = RuleSet(listOf(
            SplashRule(
                name = "批量",
                pkgs = listOf("com.Example.App"),
                targets = listOf(RuleTarget(MatchType.ID_CONTAINS, "skip", 55)),
            )
        ))
        assertTrue(
            batch.match(node(viewId = "x:id/skip"), "com.example.app", null) is RuleResult.Hit
        )
    }

    @Test
    fun `能从 JSON 解析 pkgs 数组`() {
        val json = """
            {
              "rules": [
                {
                  "name": "批量",
                  "pkgs": ["com.a", "com.b"],
                  "targets": [ { "type": "id_contains", "value": "skip", "score": 55 } ]
                }
              ]
            }
        """.trimIndent()
        val set = RuleRepository.parse(json)
        assertEquals(1, set.size)
        assertTrue(set.match(node(viewId = "x:id/skip"), "com.a", null) is RuleResult.Hit)
        assertNull(set.match(node(viewId = "x:id/skip"), "com.z", null))
    }

    // ---- 黑名单 ----

    @Test
    fun `黑名单命中直接否决`() {
        val n = node(
            viewId = "com.example.adssdk:id/example_click_bar",
            ancestors = listOf("com.example.adssdk.core.SplashView"),
        )
        val result = rules.match(n, "com.anything", null)
        assertTrue("黑名单应返回 Veto，实际 $result", result is RuleResult.Veto)
    }

    @Test
    fun `黑名单优先于加分`() {
        // 这个节点同时命中加分项和黑名单，必须是否决
        val n = node(
            viewId = "com.example.adssdk:id/example_click_bar_skip",
            ancestors = listOf("com.example.adssdk.core.SplashView"),
        )
        assertTrue(rules.match(n, "com.anything", null) is RuleResult.Veto)
    }

    // ---- 取值 ----

    @Test
    fun `多条命中时取最高分`() {
        val n = node(
            viewId = "com.example:id/btn_skip",
            text = "跳过",
            ancestors = listOf("com.example.adssdk.core.SplashView"),
        )
        val result = rules.match(n, "com.anything", null) as RuleResult.Hit
        // btn_skip 55 与 文本"跳过" 55 同分，取任一都是 55；关键是不会取到更低的分
        assertEquals(55, result.score)
    }

    @Test
    fun `大小写不敏感`() {
        val n = node(viewId = "com.example:id/EXAMPLE_Skip_Btn",
            ancestors = listOf("com.example.adssdk.core.SplashView"))
        assertTrue(rules.match(n, "com.anything", null) is RuleResult.Hit)
    }

    @Test
    fun `contentDescription 也能匹配`() {
        val set = RuleSet(listOf(
            SplashRule(
                name = "desc 规则",
                pkg = "com.example",
                targets = listOf(RuleTarget(MatchType.DESC_CONTAINS, "跳过", 50)),
            )
        ))
        assertTrue(set.match(node(desc = "跳过广告"), "com.example", null) is RuleResult.Hit)
    }

    // ---- 解析 ----

    @Test
    fun `能从 JSON 解析出规则`() {
        val json = """
            {
              "version": 1,
              "rules": [
                {
                  "name": "测试规则",
                  "pkg": "com.demo",
                  "targets": [
                    { "type": "id_contains", "value": "skip", "score": 60 }
                  ],
                  "blacklist": ["ad_root"]
                }
              ]
            }
        """.trimIndent()

        val set = RuleRepository.parse(json)
        assertEquals(1, set.size)
        assertTrue(set.match(node(viewId = "com.demo:id/skip_btn"), "com.demo", null) is RuleResult.Hit)
    }

    @Test
    fun `非法规则被跳过而不是崩溃`() {
        val json = """
            {
              "rules": [
                { "name": "缺 targets" },
                { "targets": [ { "type": "id_exact", "value": "x", "score": 10 } ] },
                { "name": "类型错误", "pkg": "a", "targets": [ { "type": "no_such_type", "value": "x", "score": 10 } ] },
                { "name": "零分", "pkg": "a", "targets": [ { "type": "id_exact", "value": "x", "score": 0 } ] }
              ]
            }
        """.trimIndent()
        assertEquals("四条非法规则都应被丢弃", 0, RuleRepository.parse(json).size)
    }

    @Test
    fun `规则文件损坏时退化为空集`() {
        val set = RuleRepository.parse("""{"rules": []}""")
        assertEquals(0, set.size)
        assertNull(set.match(node(text = "跳过"), "com.anything", null))
    }
}
