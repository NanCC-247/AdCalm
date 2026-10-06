package cn.adcalm.guard.core

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class GuardSettingsMigrationTest {
    @Test
    fun `旧安装升级会关闭主动操作与原始诊断但保留用户范围和暂停状态`() {
        val selected = setOf("com.example.app", "com.example.other")
        val memory = MemoryPreferences(mapOf(
            "enabled" to false,
            "targeted_packages" to selected,
            "dry_run" to false,
            "auto_force_stop" to true,
            "auto_quarantine" to true,
            "restore_accessibility" to true,
            "return_to_origin" to true,
            "auto_rollback" to true,
            "debug_mode" to true,
        ))
        val settings = GuardSettings(memory.preferences)
        assertObserveDefaults(settings)
        assertFalse(settings.enabled)
        assertEquals(selected, memory.preferences.getStringSet("targeted_packages", emptySet()))
        assertEquals(selected, settings.targetedPackages)
        assertEquals(1, memory.preferences.getInt("public_safety_policy", 0))
    }

    @Test
    fun `重新确认的设置在下次创建设置实例时不会再次被迁移关闭`() {
        val memory = MemoryPreferences(mapOf("enabled" to true))
        val first = GuardSettings(memory.preferences)
        assertObserveDefaults(first)
        first.dryRun = false
        first.autoForceStop = true
        first.autoQuarantine = true
        first.restoreAccessibility = true
        first.returnToOrigin = true
        first.autoRollback = true
        first.debugMode = true
        first.targetedPackages = setOf("com.example.confirmed")

        val next = GuardSettings(memory.preferences)
        assertFalse(next.dryRun)
        assertTrue(next.autoForceStop)
        assertTrue(next.autoQuarantine)
        assertTrue(next.restoreAccessibility)
        assertTrue(next.returnToOrigin)
        assertTrue(next.autoRollback)
        assertTrue(next.debugMode)
        assertTrue(next.enabled)
        assertTrue(next.isTargeted("com.example.confirmed"))
        assertEquals(1, memory.preferences.getInt("public_safety_policy", 0))
    }

    @Test
    fun `首次安装以观察模式开始且主动权限功能默认关闭`() {
        val settings = GuardSettings(MemoryPreferences().preferences)
        assertObserveDefaults(settings)
        assertTrue(settings.enabled)
        assertTrue(settings.targetedPackages.isEmpty())
    }

    private fun assertObserveDefaults(settings: GuardSettings) {
        assertTrue(settings.dryRun)
        assertFalse(settings.autoForceStop)
        assertFalse(settings.autoQuarantine)
        assertFalse(settings.restoreAccessibility)
        assertFalse(settings.returnToOrigin)
        assertFalse(settings.autoRollback)
        assertFalse(settings.debugMode)
    }

    /** 真实的内存写入与监听通知；不依赖 Android JVM 桩方法或复制迁移实现。 */
    private class MemoryPreferences(initial: Map<String, Any> = emptyMap()) {
        private val values = initial.toMutableMap()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        val preferences: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, rawArgs ->
            val args = rawArgs.orEmpty()
            when (method.name) {
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args[0] as String)
                "getBoolean", "getInt", "getLong", "getFloat", "getString" -> values[args[0] as String] ?: args[1]
                "getStringSet" -> (values[args[0] as String] as? Set<*>)?.toSet() ?: args[1]
                "edit" -> newEditor()
                "registerOnSharedPreferenceChangeListener" -> {
                    listeners += args[0] as SharedPreferences.OnSharedPreferenceChangeListener
                    null
                }
                "unregisterOnSharedPreferenceChangeListener" -> {
                    listeners -= args[0] as SharedPreferences.OnSharedPreferenceChangeListener
                    null
                }
                "toString" -> "MemoryPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args.firstOrNull()
                else -> error("Unexpected preferences method ${method.name}")
            }
        } as SharedPreferences

        private fun newEditor(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, Any?>()
            var clearRequested = false
            fun flush() {
                val affected = if (clearRequested) (values.keys + changes.keys).toSet() else changes.keys.toSet()
                if (clearRequested) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                affected.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(preferences, key) } }
            }
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { proxy, method, rawArgs ->
                val args = rawArgs.orEmpty()
                when (method.name) {
                    "putBoolean", "putInt", "putLong", "putFloat", "putString", "putStringSet" -> {
                        changes[args[0] as String] = (args[1] as? Set<*>)?.toSet() ?: args[1]
                        proxy
                    }
                    "remove" -> { changes[args[0] as String] = null; proxy }
                    "clear" -> { clearRequested = true; proxy }
                    "apply" -> { flush(); null }
                    "commit" -> { flush(); true }
                    "toString" -> "MemoryEditor"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args.firstOrNull()
                    else -> error("Unexpected editor method ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
