package cn.adcalm.guard.data

import android.content.Context
import cn.adcalm.guard.core.GuardSettings
import cn.adcalm.guard.model.Candidate
import cn.adcalm.guard.service.AdCalmAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 一次窗口观测。默认落盘为摘要；完整节点树仅供明确开启的本机诊断。 */
data class ObservationEntry(
    val timestamp: Long,
    val packageName: String,
    val activityName: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val candidates: List<Candidate>,
    /** NO_CANDIDATE / SUSPECT_ONLY / WOULD_CLICK / CLICKED / CLICK_FAILED / COOLDOWN */
    val decision: String,
    val dryRun: Boolean,
    /** 实际点击时用的手段：ACTION_CLICK / GESTURE；未点击为 null。 */
    val clickMethod: String? = null,
    /** 诊断模式下的完整节点树（JSON 数组字符串）。 */
    val treeDump: String? = null,
    /**
     * 诊断模式下的界面快照文件名（存在 [SnapshotStore] 目录里），与节点树配对。
     *
     * 树看不出关闭按钮在哪时，图是唯一的线索——见 [SnapshotStore] 的类注释。
     */
    val snapshotName: String? = null,
)

/**
 * 观察模式日志。一行一条 JSON（JSONL），追加写入，便于导出后逐行分析。
 *
 * 写文件走的是应用私有目录，不需要任何存储权限，也不会被隔离区逻辑误伤。
 */
class ObservationLog(private val context: Context) {

    private val settings by lazy {
        GuardSettings(context.getSharedPreferences(AdCalmAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE))
    }

    val file: File get() = File(context.filesDir, FILE_NAME)

    /** 轮转出来的上一份。 */
    private val rotatedFile: File get() = File(context.filesDir, ROTATED_FILE_NAME)

    /**
     * 现有日志文件，**按"新的在前"**（当前文件 → 轮转出去的那份）。
     *
     * 所有读取都要覆盖两份：行数、跳过次数、大小、导出。少读一份，
     * 首页那几个数字就会在轮转发生的那一刻凭空变小。
     */
    private fun logFiles(): List<File> = listOf(file, rotatedFile).filter { it.exists() }

    fun record(entry: ObservationEntry) {
        synchronized(LOG_LOCK) {
            runCatching {
                rotateIfNeeded()
                file.appendText(entry.toJson().toString() + "\n")
            }
        }
    }

    /**
     * 超过上限就轮转：当前文件改名成 `observer_log.1.jsonl`，**老的 `.1` 直接覆盖**，
     * 所以磁盘上最多两份、约 2×[MAX_BYTES]。
     *
     * 选轮转而不是截断，是因为日志是校准唯一的输入——截断丢掉的正是
     * "上个月那次广告的完整现场"这种最值钱的东西。轮转一份不丢，
     * 代价只是读取时要多看一份（[logFiles]）。
     */
    private fun rotateIfNeeded() {
        if (file.length() < MAX_BYTES) return
        rotatedFile.delete()
        file.renameTo(rotatedFile)
    }

    /**
     * 日志有多少行。
     *
     * **流式数换行，不把文件读成 `List<String>`。** 原来是 `readLines().size`——
     * 那会把整个文件的行都建成字符串对象；而日志实测能到 8MB 以上，
     * 首页每次回前台都会调它一次。
     *
     * 仍然要读一遍整个文件，所以调用方应该在 IO 线程上跑并缓存结果。
     */
    fun lineCount(): Int = logFiles().sumOf { countLines(it) }

    private fun countLines(target: File): Int = runCatching {
        var count = 0
        target.bufferedReader().use { reader ->
            val buffer = CharArray(64 * 1024)
            while (true) {
                val read = reader.read(buffer)
                if (read <= 0) break
                for (i in 0 until read) if (buffer[i] == '\n') count++
            }
        }
        count
    }.getOrDefault(0)

    /**
     * 从 [sinceMs] 起，"跳过了一次广告"发生了多少次。
     *
     * 走流式读取（`useLines`），不把整个文件读进内存——诊断模式开着的时候这个文件
     * 可以很大，而它会在每次回到首页时被调用一次。**调用方应在 IO 线程上跑。**
     *
     * 数不出来的情况（文件不存在、读失败）返回 0，不抛。
     * 界面上会因此显示"还没有记录"，比崩掉好。
     */
    fun clicksSince(sinceMs: Long): Int = logFiles().sumOf { target ->
        runCatching {
            target.bufferedReader().useLines { LogStats.countClicks(it, sinceMs) }
        }.getOrDefault(0)
    }

    fun sizeBytes(): Long =
        runCatching { logFiles().sumOf { it.length() } }.getOrDefault(0L)

    /** 删除本应用观察记录、诊断截图和已有导出文件；不涉及下载或用户素材。 */
    fun clear(): Boolean = synchronized(LOG_LOCK) {
        // 必须先设置截图清理边界，避免清空前启动的异步截屏稍后重新落盘。
        val snapshotsCleared = SnapshotStore(context).clear()
        val logsCleared = logFiles().map { deleteIfPresent(it) }.all { it }
        val exportsCleared = clearExports()
        snapshotsCleared && logsCleared && exportsCleared
    }

    /** 默认且唯一的应用内导出是安全摘要，涵盖轮转日志。完整诊断只保留在本机。 */
    fun exportTo(dest: File): Boolean = synchronized(LOG_LOCK) { runCatching {
        // 防止误传源文件导致原始日志被截断。
        if (dest.canonicalFile in listOf(file.canonicalFile, rotatedFile.canonicalFile)) {
            return@runCatching false
        }
        val sources = listOf(rotatedFile, file).filter { it.exists() }
        if (sources.isEmpty()) return@runCatching false
        dest.parentFile?.mkdirs()
        val session = ObservationPrivacy.ExportSession()
        var written = 0
        dest.bufferedWriter().use { out ->
            sources.forEach { src -> src.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    session.summarizeLine(line)?.let {
                        out.write(it)
                        out.newLine()
                        written++
                    }
                }
            } }
        }
        if (written == 0) {
            dest.delete()
            return@runCatching false
        }
        true
    }.getOrElse { dest.delete(); false } }

    private fun clearExports(): Boolean = runCatching {
        exportFiles().map { deleteIfPresent(it) }.all { it }
    }.getOrDefault(false)

    fun hasExportedCopies(): Boolean = runCatching { exportFiles().any { it.exists() } }.getOrDefault(false)

    private fun exportFiles(): List<File> {
        val currentExport = File(File(context.cacheDir, EXPORT_DIR_NAME), EXPORT_FILE_NAME)
        // 旧版曾把完整日志复制到 external-files；清空时也处理该应用自己创建的副本。
        val oldExport = context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }
        return listOfNotNull(currentExport, oldExport)
    }

    private fun deleteIfPresent(target: File): Boolean =
        runCatching { !target.exists() || target.delete() }.getOrDefault(false)

    private fun ObservationEntry.toJson(): JSONObject = JSONObject().apply {
        put("ts", timestamp)
        put("time", TIME_FORMAT.format(Date(timestamp)))
        put("pkg", ObservationPrivacy.localPackageName(packageName))
        put("screen", "$screenWidth x $screenHeight")
        put("decision", ObservationPrivacy.safeDecision(decision))
        put("dryRun", dryRun)
        ObservationPrivacy.safeClickMethod(clickMethod)?.let { put("clickMethod", it) }
        val diagnostic = settings.debugMode && (treeDump != null || snapshotName != null) &&
            SnapshotStore(context).acceptsDiagnosticTimestamp(timestamp)
        put("privacy", if (diagnostic) "local_diagnostic" else "summary")
        if (diagnostic) {
            treeDump?.let { dump -> runCatching { JSONArray(dump) }.getOrNull()?.let { put("tree", it) } }
            snapshotName?.takeIf { SnapshotStore.isSnapshotName(it) }?.let { put("snapshot", it) }
        }
        put("candidates", JSONArray().apply {
            candidates.forEach { put(ObservationPrivacy.candidateSummary(it)) }
        })
    }

    companion object {
        private val LOG_LOCK = Any()
        private const val FILE_NAME = "observer_log.jsonl"

        const val EXPORT_DIR_NAME = "observation-exports"
        const val EXPORT_FILE_NAME = "observer_summary.jsonl"

        /** 轮转出来的上一份。见 [rotateIfNeeded]。 */
        private const val ROTATED_FILE_NAME = "observer_log.1.jsonl"

        /**
         * 单份日志的上限。
         *
         * 16MB：诊断模式开着时一晚上能写 8.8MB（实测过），所以这个上限大致够
         * "一周的日常记录"或"两晚的诊断"。超过就轮转，磁盘上最多两份、约 32MB——
         * **是轮转不是截断，一条不丢**。
         */
        private const val MAX_BYTES = 16L * 1024 * 1024
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

        /** 今天零点（本地时区）的毫秒时间戳。首页那行「今日已跳过」用它做起点。 */
        fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
