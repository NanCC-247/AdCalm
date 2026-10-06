package cn.adcalm.guard.data

import android.content.Context
import android.os.Environment
import android.os.FileObserver
import android.util.Log
import cn.adcalm.guard.core.DownloadFilter
import cn.adcalm.guard.core.DownloadActionPolicy
import cn.adcalm.guard.core.GuardSettings
import cn.adcalm.guard.core.Permissions
import cn.adcalm.guard.core.QuarantineDecision
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

data class QuarantinedFile(
    val originalPath: String,
    val quarantinePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val quarantinedAt: Long,
)

/**
 * 广告下载文件的隔离区。
 *
 * 自动处理只做可恢复的隔离；不会按保留时长永久删除，删除须用户逐项确认。
 * 之所以不直接删：误判的代价不对称，误留一个 apk 只是占几 MB 空间，
 * 误删用户正在下载的东西是数据丢失。
 *
 * 拦截下载本身主要靠"下载开始前就强停目标应用"（见 AdCalmAccessibilityService.rollback），
 * 这个组件负责清理已经落盘的残渣。
 */
class DownloadJanitor(
    private val context: Context,
    private val settings: GuardSettings,
) {

    private val observers = mutableListOf<FileObserver>()

    @Volatile
    private var running = false

    private val quarantineDir: File
        get() = File(Environment.getExternalStorageDirectory(), QUARANTINE_DIR_NAME)

    private val indexFile: File
        get() = File(context.filesDir, INDEX_NAME)

    /** 周期检查让设置变更立即作用于监控，无需重新连接无障碍服务。 */
    fun syncWithSettings() {
        if (isMonitoringAllowed()) start() else stop()
    }

    private fun isMonitoringAllowed(): Boolean = settings.enabled && !settings.dryRun &&
        settings.autoQuarantine && Permissions.hasAllFilesAccess(context)

    @Synchronized
    fun start() {
        if (running || !isMonitoringAllowed()) return
        running = true

        var subdirBudget = MAX_SUBDIR_OBSERVERS
        for (dir in watchDirectories()) {
            if (!dir.isDirectory) continue
            attachObserver(dir)

            // 再往下看一层，很多下载器会建自己的子目录。
            //
            // **但要限量。** 每个 FileObserver 自带一个线程，2026-10-05 实测这里
            // 挂出了 26 个——对一个常驻后台的服务来说太多了。按"最近改动过"优先取，
            // 因为活跃的下载目录才是新文件会出现的地方，久没动过的多半不会有东西落进去。
            val subdirs = dir.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.lastModified() }
                ?.take(subdirBudget)
                .orEmpty()
            subdirs.forEach { attachObserver(it) }
            subdirBudget -= subdirs.size
            if (subdirBudget <= 0) break
        }
        Log.i(TAG, "下载监控已启动，覆盖 ${observers.size} 个目录")
    }

    @Synchronized
    fun stop() {
        running = false
        observers.forEach { runCatching { it.stopWatching() } }
        observers.clear()
    }

    private fun attachObserver(dir: File) {
        try {
            @Suppress("DEPRECATION")
            val observer = object : FileObserver(
                dir.absolutePath,
                CLOSE_WRITE or MOVED_TO,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null || path.contains('/') || path.contains('\\')) return
                    onFileAppeared(File(dir, path))
                }
            }
            observer.startWatching()
            observers += observer
        } catch (_: Exception) {
            Log.w(TAG, "无法监控下载目录")
        }
    }

    /** 文件创建或写入完成时调用。真正动手前要过 [DownloadFilter] 的四道条件。 */
    private fun onFileAppeared(file: File) {
        if (!running) return
        if (!file.isFile) return
        if (!isMonitoringAllowed()) return
        // 观察模式 = 纯观察：**一个文件都不动**。
        //
        // 移动文件也是"动手"，而用户开观察模式时的理解是"只记录"。
        // 2026-10-05 的审计把这条一起点了出来（"纯观察覆盖所有动作入口，
        // 不产生点击、返回、强停或文件变化"），用户拍板照办。
        // 隔离文件一直保留到用户恢复或明确删除。
        if (settings.dryRun) return
        if (!Permissions.hasAllFilesAccess(context)) {
            Log.d(TAG, "没有所有文件访问权限，跳过隔离")
            return
        }

        val source = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (!isInWatchedDirectory(source)) return
        val now = System.currentTimeMillis()
        if (!DownloadActionPolicy.allows(settings.enabled, settings.dryRun, settings.autoQuarantine, lastAdJumpAt, now)) return
        val decision = DownloadFilter.decide(
            path = source.absolutePath,
            // Java 拿不到真正的创建时间，用修改时间近似：
            // 新落盘的文件 mtime ≈ now，偏保守的方向正是我们想要的
            createdAt = source.lastModified(),
            adJumpAt = lastAdJumpAt,
            now = now,
        )

        when (decision) {
            is QuarantineDecision.Quarantine -> quarantine(source)
            is QuarantineDecision.Skip -> Unit
        }
    }

    private fun quarantine(file: File) = synchronized(FILE_LOCK) {
        if (!canQuarantineNow(file)) return@synchronized
        val dir = quarantineDir
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建隔离目录")
            return@synchronized
        }

        val stamp = System.currentTimeMillis()
        // 不包含原文件名，避免同毫秒的同名下载互相覆盖。
        val dest = File(dir, "${stamp}_${UUID.randomUUID()}")
        val entry = QuarantinedFile(
            originalPath = file.absolutePath,
            quarantinePath = dest.absolutePath,
            fileName = file.name,
            sizeBytes = file.length(),
            quarantinedAt = stamp,
        )
        // 索引写不进去就不移动，保证已经隔离的文件有可恢复记录。
        if (!appendIndex(entry)) return@synchronized
        val moved = runCatching {
            // 从文件事件到移动之间，权限、模式和误跳窗口都可能发生变化。
            if (!canQuarantineNow(file)) return@runCatching false
            Files.move(file.toPath(), dest.toPath()) // 不提供 REPLACE_EXISTING。
            true
        }.getOrDefault(false)
        if (!moved) { removeFromIndex(dest.absolutePath); return@synchronized }
        Log.i(TAG, "已隔离一个下载文件，可在隔离区恢复")
    }

    private fun canQuarantineNow(file: File): Boolean {
        if (!running || !isMonitoringAllowed() || !file.isFile || !isInWatchedDirectory(file)) return false
        val now = System.currentTimeMillis()
        if (!DownloadActionPolicy.allows(settings.enabled, settings.dryRun, settings.autoQuarantine, lastAdJumpAt, now)) return false
        return DownloadFilter.decide(file.absolutePath, file.lastModified(), lastAdJumpAt, now) is QuarantineDecision.Quarantine
    }

    private fun isInWatchedDirectory(file: File): Boolean = runCatching {
        val parent = file.canonicalFile.parentFile
        watchDirectories().any { root ->
            val canonicalRoot = root.canonicalFile
            parent == canonicalRoot || parent?.parentFile == canonicalRoot
        }
    }.getOrDefault(false)

    // ---- 索引读写 ----

    fun listQuarantined(): List<QuarantinedFile> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            indexFile.readLines()
                .mapNotNull { line -> parseEntry(line) }
                .filter { ownedQuarantineFile(it)?.isFile == true }
        }.getOrDefault(emptyList())
    }

    fun restore(entry: QuarantinedFile): Boolean = synchronized(FILE_LOCK) {
        val src = ownedQuarantineFile(entry) ?: return@synchronized false
        if (!src.isFile || !Permissions.hasAllFilesAccess(context)) return@synchronized false
        val dest = runCatching { File(entry.originalPath).canonicalFile }.getOrNull() ?: return@synchronized false
        // 用户可能已下载了新的同名文件；失败时完整保留隔离文件及索引。
        if (dest.exists() || !isInWatchedDirectory(dest)) return@synchronized false
        val ok = runCatching {
            dest.parentFile?.mkdirs()
            if (dest.exists()) return@runCatching false
            Files.move(src.toPath(), dest.toPath())
            true
        }.getOrDefault(false)

        if (ok) removeFromIndex(entry.quarantinePath)
        ok
    }

    fun deleteNow(entry: QuarantinedFile): Boolean = synchronized(FILE_LOCK) {
        if (!Permissions.hasAllFilesAccess(context)) return@synchronized false
        val file = ownedQuarantineFile(entry) ?: return@synchronized false
        val ok = !file.exists() || file.delete()
        if (ok) removeFromIndex(entry.quarantinePath)
        ok
    }

    private fun ownedQuarantineFile(entry: QuarantinedFile): File? = runCatching {
        File(entry.quarantinePath).canonicalFile.takeIf { it.parentFile == quarantineDir.canonicalFile }
    }.getOrNull()

    /** Public beta requires manual review and confirmation for permanent deletion. */
    fun purgeExpired(): Int = 0

    private fun appendIndex(entry: QuarantinedFile): Boolean =
        runCatching {
            indexFile.appendText(entry.toJson().toString() + "\n")
            true
        }.getOrDefault(false)

    private fun removeFromIndex(quarantinePath: String) {
        runCatching {
            if (!indexFile.exists()) return@runCatching
            val kept = indexFile.readLines().filter { line ->
                parseEntry(line)?.quarantinePath != quarantinePath
            }
            indexFile.writeText(kept.joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" })
        }
    }

    private fun parseEntry(line: String): QuarantinedFile? = runCatching {
        if (line.isBlank()) return@runCatching null
        val obj = JSONObject(line)
        QuarantinedFile(
            originalPath = obj.getString("originalPath"),
            quarantinePath = obj.getString("quarantinePath"),
            fileName = obj.getString("fileName"),
            sizeBytes = obj.optLong("sizeBytes"),
            quarantinedAt = obj.getLong("quarantinedAt"),
        )
    }.getOrNull()

    private fun QuarantinedFile.toJson(): JSONObject = JSONObject().apply {
        put("originalPath", originalPath)
        put("quarantinePath", quarantinePath)
        put("fileName", fileName)
        put("sizeBytes", sizeBytes)
        put("quarantinedAt", quarantinedAt)
    }

    private fun watchDirectories(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return listOf(
            File(root, "Download"),
            File(root, "Browser"),
            File(root, "download"),
        )
    }

    companion object {
        private val FILE_LOCK = Any()
        private const val TAG = "AdCalm"
        private const val QUARANTINE_DIR_NAME = ".AdCalmQuarantine"
        private const val INDEX_NAME = "quarantine_index.jsonl"

        /**
         * 三个根目录之外，最多再给子目录挂几个观察者。
         *
         * 每个 FileObserver 自带一个线程。不加限的话实测能挂出 26 个，
         * 对一个常驻后台的服务来说太重了。十二个够覆盖常见的浏览器/下载器子目录，
         * 而且按"最近改动过"优先取，活跃的那个一定在名单里。
         */
        private const val MAX_SUBDIR_OBSERVERS = 12

        /**
         * 最近一次误跳的时间戳。
         *
         * 无障碍服务和前台服务跑在同一个进程里，所以用一个进程内的静态变量传递就够了。
         * 0 表示没有记录——此时 [DownloadFilter] 会拒绝隔离任何文件。
         */
        @Volatile
        var lastAdJumpAt: Long = 0L
            private set

        fun clearAdJump() { lastAdJumpAt = 0L }

        fun noteAdJump(at: Long) {
            lastAdJumpAt = at
        }
    }
}
