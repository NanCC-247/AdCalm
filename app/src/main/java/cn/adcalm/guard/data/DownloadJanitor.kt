package cn.adcalm.guard.data

import android.content.Context
import android.os.Environment
import android.os.FileObserver
import android.util.Log
import cn.adcalm.guard.core.DownloadFilter
import cn.adcalm.guard.core.GuardSettings
import cn.adcalm.guard.core.Permissions
import cn.adcalm.guard.core.QuarantineDecision
import org.json.JSONObject
import java.io.File

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
 * 只做"移走"，不做"删除"——24 小时后由定时任务真正删除，期间用户可以一键恢复。
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

    fun start() {
        if (running) return
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
                CREATE or CLOSE_WRITE or MOVED_TO,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    onFileAppeared(File(dir, path))
                }
            }
            observer.startWatching()
            observers += observer
        } catch (e: Exception) {
            Log.w(TAG, "无法监控 ${dir.absolutePath}", e)
        }
    }

    /** 文件创建或写入完成时调用。真正动手前要过 [DownloadFilter] 的四道条件。 */
    private fun onFileAppeared(file: File) {
        if (!running) return
        if (!file.isFile) return
        if (!settings.autoQuarantine) return
        // 观察模式 = 纯观察：**一个文件都不动**。
        //
        // 移动文件也是"动手"，而用户开观察模式时的理解是"只记录"。
        // 2026-10-05 的审计把这条一起点了出来（"纯观察覆盖所有动作入口，
        // 不产生点击、返回、强停或文件变化"），用户拍板照办。
        // 隔离本身是可逆的（只移不删、24 小时后才真删），所以暂停它不会丢东西。
        if (settings.dryRun) return
        if (!Permissions.hasAllFilesAccess(context)) {
            Log.d(TAG, "没有所有文件访问权限，跳过隔离")
            return
        }

        val now = System.currentTimeMillis()
        val decision = DownloadFilter.decide(
            path = file.absolutePath,
            // Java 拿不到真正的创建时间，用修改时间近似：
            // 新落盘的文件 mtime ≈ now，偏保守的方向正是我们想要的
            createdAt = file.lastModified(),
            adJumpAt = lastAdJumpAt,
            now = now,
        )

        when (decision) {
            is QuarantineDecision.Quarantine -> quarantine(file, decision.reason)
            is QuarantineDecision.Skip -> Log.d(TAG, "跳过 ${file.name}：${decision.reason}")
        }
    }

    private fun quarantine(file: File, reason: String) {
        val dir = quarantineDir
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建隔离目录 ${dir.absolutePath}")
            return
        }

        val stamp = System.currentTimeMillis()
        val dest = File(dir, "${stamp}_${file.name}")

        val moved = file.renameTo(dest) || runCatching {
            file.copyTo(dest, overwrite = true)
            file.delete()
        }.getOrDefault(false)

        if (!moved) {
            Log.w(TAG, "隔离失败：${file.absolutePath}")
            return
        }

        appendIndex(
            QuarantinedFile(
                originalPath = file.absolutePath,
                quarantinePath = dest.absolutePath,
                fileName = file.name,
                sizeBytes = dest.length(),
                quarantinedAt = stamp,
            )
        )
        Log.i(TAG, "已隔离 ${file.name}（$reason）")
    }

    // ---- 索引读写 ----

    fun listQuarantined(): List<QuarantinedFile> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            indexFile.readLines()
                .mapNotNull { line -> parseEntry(line) }
                .filter { File(it.quarantinePath).exists() }
        }.getOrDefault(emptyList())
    }

    fun restore(entry: QuarantinedFile): Boolean {
        val src = File(entry.quarantinePath)
        if (!src.exists()) return false
        val dest = File(entry.originalPath)
        val ok = runCatching {
            dest.parentFile?.mkdirs()
            src.renameTo(dest) || runCatching {
                src.copyTo(dest, overwrite = true)
                src.delete()
            }.getOrDefault(false)
        }.getOrDefault(false)

        if (ok) removeFromIndex(entry.quarantinePath)
        return ok
    }

    fun deleteNow(entry: QuarantinedFile): Boolean {
        val file = File(entry.quarantinePath)
        val ok = !file.exists() || file.delete()
        if (ok) removeFromIndex(entry.quarantinePath)
        return ok
    }

    /** 删除超过保留期的隔离文件。返回删除数量。 */
    fun purgeExpired(): Int {
        val cutoff = System.currentTimeMillis() - settings.quarantineRetentionHours * 3_600_000L
        val expired = listQuarantined().filter { it.quarantinedAt < cutoff }
        var count = 0
        for (entry in expired) {
            if (File(entry.quarantinePath).let { !it.exists() || it.delete() }) {
                removeFromIndex(entry.quarantinePath)
                count++
            }
        }
        if (count > 0) Log.i(TAG, "已清理 $count 个过期隔离文件")
        return count
    }

    private fun appendIndex(entry: QuarantinedFile) {
        runCatching {
            indexFile.appendText(entry.toJson().toString() + "\n")
        }
    }

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

        fun noteAdJump(at: Long) {
            lastAdJumpAt = at
        }
    }
}
