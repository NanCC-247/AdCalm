package cn.adcalm.guard

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.adcalm.guard.core.GuardSettings
import cn.adcalm.guard.data.ObservationEntry
import cn.adcalm.guard.data.ObservationLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 持久化层的验证：设置项真的落盘了吗、日志文件真的能写能读吗。
 *
 * 这一类问题在 JVM 单测里完全测不到，而一旦出问题就是"设置保存不了"
 * 或者"日志导出来是空的"——用户侧表现为功能莫名其妙不生效。
 *
 * ⚠ 这些测试会写真实的应用私有目录。只应在模拟器或专用测试机上跑，
 * `connectedAndroidTest` 会清空现有观察日志。
 */
@RunWith(AndroidJUnit4::class)
class PersistenceInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 生效范围会真正写入SharedPreferences() {
        val prefs = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val settings = GuardSettings(prefs)
        assertTrue("默认不接管任何应用", settings.targetedPackages.isEmpty())
        assertFalse(settings.isTargeted("com.example"))

        settings.targetedPackages = setOf("com.a", "com.b")

        // 绕过内存缓存直接读底层，验证确实落盘了
        val stored = prefs.getStringSet("targeted_packages", emptySet())
        assertEquals(setOf("com.a", "com.b"), stored)

        settings.addTarget("com.c")
        assertTrue(settings.isTargeted("com.c"))

        settings.removeTarget("com.a")
        assertFalse(settings.isTargeted("com.a"))
    }

    @Test
    fun 布尔开关能正确往返() {
        val prefs = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val settings = GuardSettings(prefs)

        // 默认值：装好即自动点击；总开关和 OCR 默认开启
        assertFalse("默认应为自动模式（dryRun=false）", settings.dryRun)
        assertTrue("总开关应默认开启", settings.enabled)
        assertTrue("OCR 应默认开启", settings.ocrEnabled)

        settings.dryRun = true
        settings.ocrEnabled = false
        assertEquals(true, prefs.getBoolean("dry_run", false))
        assertEquals(false, prefs.getBoolean("ocr_enabled", true))
    }

    @Test
    fun 观察日志能写入并读回() {
        val log = ObservationLog(context)
        log.clear()
        assertEquals(0, log.lineCount())

        log.record(
            ObservationEntry(
                timestamp = 1_700_000_000_000L,
                packageName = "com.example.app",
                activityName = "com.example.app.MainActivity",
                screenWidth = 1080,
                screenHeight = 2400,
                candidates = emptyList(),
                decision = "NO_CANDIDATE",
                dryRun = true,
            )
        )

        assertEquals("应写入一条记录", 1, log.lineCount())
        assertTrue("文件应有内容", log.file.length() > 0)

        // 内容应是合法 JSON，且字段齐全——导出后要能直接解析
        val line = log.file.readLines().first()
        val obj = org.json.JSONObject(line)
        assertEquals("com.example.app", obj.getString("pkg"))
        assertEquals("NO_CANDIDATE", obj.getString("decision"))
        assertTrue(obj.getBoolean("dryRun"))

        log.clear()
        assertEquals(0, log.lineCount())
    }

    private companion object {
        /** 用独立的设置文件，避免污染应用真实设置。 */
        const val TEST_PREFS = "adcalm_instrumented_test"
    }
}
