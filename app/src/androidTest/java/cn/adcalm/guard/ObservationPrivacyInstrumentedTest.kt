package cn.adcalm.guard

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.adcalm.guard.core.GuardSettings
import cn.adcalm.guard.data.ObservationEntry
import cn.adcalm.guard.data.ObservationLog
import cn.adcalm.guard.data.SnapshotStore
import cn.adcalm.guard.model.Candidate
import cn.adcalm.guard.model.NodeSnapshot
import cn.adcalm.guard.model.ScoreReason
import cn.adcalm.guard.model.Verdict
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 使用独立目录与设置文件，不清空用户的真实观察记录或诊断图片。 */
@RunWith(AndroidJUnit4::class)
class ObservationPrivacyInstrumentedTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var settings: GuardSettings

    @Before
    fun prepare() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir, "privacy-test-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?): File = File(root, "external").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("privacy-test-$name", mode)
        }
        settings = GuardSettings(context.getSharedPreferences("adcalm", Context.MODE_PRIVATE))
        settings.debugMode = false
    }

    @After
    fun cleanUp() {
        settings.debugMode = false
        root.deleteRecursively()
    }

    @Test
    fun 默认日志不落盘候选文字并保留点击统计() {
        val log = ObservationLog(context)
        log.record(entry().copy(candidates = listOf(Candidate(
            path = emptyList(),
            snapshot = NodeSnapshot(text = PRIVATE_TEXT, contentDescription = PRIVATE_TEXT,
                viewId = PRIVATE_TEXT, ancestorClassNames = listOf(PRIVATE_TEXT)),
            score = 75,
            reasons = listOf(ScoreReason(55, PRIVATE_TEXT)),
            verdict = Verdict.CLICK,
            hasStrongEvidence = true,
        ))))
        assertFalse(log.file.readText().contains(PRIVATE_TEXT))
        assertEquals("com.example.app", JSONObject(log.file.readText()).getString("pkg"))
        assertEquals(1, log.clicksSince(0))
    }

    @Test
    fun 摘要包含轮转日志并过滤旧日志且不破坏源文件() {
        val log = ObservationLog(context)
        val first = """{"ts":1,"pkg":"com.example.app","decision":"CLICKED","text":"$PRIVATE_TEXT","tree":[{"text":"$PRIVATE_TEXT"}]}"""
        val second = """{"ts":2,"pkg":"com.example.app","decision":"OCR_CLICKED","snapshot":"snap_2.jpg","activity":"$PRIVATE_TEXT"}"""
        File(context.filesDir, "observer_log.1.jsonl").writeText("$first\n")
        log.file.writeText("$second\ninvalid $PRIVATE_TEXT\n")
        val before = log.file.readText()
        val dest = File(context.cacheDir, "summary.jsonl")
        assertTrue(log.exportTo(dest))
        val lines = dest.readLines()
        assertEquals(2, lines.size)
        assertEquals(1L, JSONObject(lines[0]).getLong("ts"))
        assertEquals(2L, JSONObject(lines[1]).getLong("ts"))
        assertFalse(dest.readText().contains(PRIVATE_TEXT))
        assertFalse(dest.readText().contains("com.example.app"))
        assertFalse(dest.readText().contains("snap_2.jpg"))
        assertEquals(before, log.file.readText())
        assertFalse(log.exportTo(log.file))
        assertEquals(before, log.file.readText())
    }

    @Test
    fun 完整诊断只有明确开启时保存并始终排除在摘要之外() {
        val log = ObservationLog(context)
        val snapshots = SnapshotStore(context)
        val diagnostic = entry().copy(treeDump = """[{"text":"$PRIVATE_TEXT"}]""")
        log.record(diagnostic)
        assertFalse(log.file.readText().contains(PRIVATE_TEXT))
        settings.debugMode = true
        log.record(diagnostic.copy(timestamp = System.currentTimeMillis() + 1))
        assertTrue(log.file.readText().contains(PRIVATE_TEXT))
        val dest = File(context.cacheDir, "summary.jsonl")
        assertTrue(log.exportTo(dest))
        assertFalse(dest.readText().contains(PRIVATE_TEXT))
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(snapshots.save(bitmap, snapshots.nameFor(System.currentTimeMillis() + 1)))
            settings.debugMode = false
            assertFalse(snapshots.save(bitmap, snapshots.nameFor(System.currentTimeMillis() + 2)))
            assertEquals(1, snapshots.list().size)
        } finally { bitmap.recycle() }
    }

    @Test
    fun 清空会清理诊断和导出并拒绝先前异步快照且不删除其他文件() {
        val log = ObservationLog(context)
        val snapshots = SnapshotStore(context)
        val pendingStamp = System.currentTimeMillis()
        val pendingName = snapshots.nameFor(pendingStamp)
        snapshots.dir.mkdirs()
        snapshots.fileFor("snap_1.jpg").writeText("old image")
        File(snapshots.dir, "snap_2.jpg.part").writeText("partial")
        val otherFile = File(context.filesDir, "user-material.jpg").apply { writeText("keep") }
        val unownedFile = File(snapshots.dir, "unrelated.jpg").apply { writeText("keep") }
        val legacyExport = File(context.getExternalFilesDir(null), "observer_log.jsonl").apply { writeText(PRIVATE_TEXT) }
        val currentExport = File(File(context.cacheDir, ObservationLog.EXPORT_DIR_NAME),
            ObservationLog.EXPORT_FILE_NAME).apply { parentFile!!.mkdirs(); writeText("summary") }
        log.record(entry())
        assertTrue(log.clear())
        assertEquals(0, log.lineCount())
        assertTrue(snapshots.list().isEmpty())
        assertFalse(File(snapshots.dir, "snap_2.jpg.part").exists())
        assertFalse(legacyExport.exists())
        assertFalse(currentExport.exists())
        assertTrue(otherFile.exists())
        assertTrue(unownedFile.exists())
        settings.debugMode = true
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try { assertFalse(snapshots.save(bitmap, pendingName)) } finally { bitmap.recycle() }
        log.record(entry().copy(timestamp = pendingStamp, treeDump = """[{"text":"$PRIVATE_TEXT"}]"""))
        assertFalse(log.file.readText().contains(PRIVATE_TEXT))
    }

    private fun entry(): ObservationEntry = ObservationEntry(
        timestamp = System.currentTimeMillis(),
        packageName = "com.example.app",
        activityName = PRIVATE_TEXT,
        screenWidth = 1080,
        screenHeight = 2400,
        candidates = emptyList(),
        decision = "CLICKED",
        dryRun = false,
    )

    private companion object { const val PRIVATE_TEXT = "alice@example.com 13800138000 token=secret" }
}
