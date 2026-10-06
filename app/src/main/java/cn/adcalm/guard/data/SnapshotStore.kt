package cn.adcalm.guard.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import cn.adcalm.guard.core.GuardSettings
import cn.adcalm.guard.service.AdCalmAccessibilityService
import java.io.File

/**
 * 诊断模式下的界面快照：**截图 + 节点树**。
 *
 * 这个做法是从 GKD 的「快照」搬过来的**手法**（只借手法，不搬它的规则）：
 * 光有节点树看不出「关闭按钮只存在于像素里」的情况。真机日志里出现过这样的广告——
 * 广告 SDK 把整个广告画在画布上，无障碍树里只剩 66 个空壳节点，
 * viewId 一个没有、clickable 一个没有、文字只有广告文案。
 * 那时唯一还剩线索的地方就是**屏幕上那块图像**，而只存节点树的话，
 * 这个线索在导出物里根本不存在，谁来看都看不出关闭按钮在哪。
 *
 * 图片和节点树分开存：树在 `observer_log.jsonl` 里，图在这个目录，
 * 两边靠 [nameFor] 生成的文件名配对。
 *
 * **隐私**：截图可能包含任意界面内容，属于个人数据。
 * 所以它只存应用私有目录（不需要任何存储权限），只在诊断模式下抓，并且限量保留。
 * 图片不会自动脱敏；应用内的摘要导出不包含这些图片。
 */
class SnapshotStore(private val context: Context) {

    private val settings by lazy {
        GuardSettings(context.getSharedPreferences(AdCalmAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE))
    }

    /** 放在快照目录之外，清理图片时仍保留异步抓取的拒绝边界。 */
    private val clearCutoff: File get() = File(context.filesDir, CLEAR_CUTOFF_FILE)

    val dir: File get() = File(context.filesDir, DIR_NAME)

    /**
     * 生成快照文件名。
     *
     * 名字由时间戳决定，是为了**先写日志、后落图**：截屏是异步的，
     * 日志要同步写完整，所以调用方先拿名字，图随后补上。
     * 截屏失败时对应的图就不存在，日志里那条也只是少一张图，不会串行。
     */
    fun nameFor(timestamp: Long): String = "snap_$timestamp.jpg"

    fun fileFor(name: String): File {
        require(isSnapshotName(name)) { "Invalid diagnostic snapshot name" }
        return File(dir, name)
    }

    /** 完整节点树也使用同一清理边界，拒绝清空前已经开始的诊断工作。 */
    fun acceptsDiagnosticTimestamp(timestamp: Long): Boolean = synchronized(SNAPSHOT_LOCK) {
        timestamp > maxOf(lastClearedAt, runCatching { clearCutoff.readText().toLong() }.getOrDefault(0L))
    }

    /** 保存一张快照并顺带清理旧文件。返回是否成功。 */
    fun save(bitmap: Bitmap, name: String, canPersist: () -> Boolean = { true }): Boolean = synchronized(SNAPSHOT_LOCK) { runCatching {
        val stamp = snapshotTimestamp(name) ?: return@runCatching false
        // 再次读取开关：截屏回调返回前，用户可能已经关闭诊断或清空了日志。
        if (!settings.enabled || !settings.debugMode || !acceptsDiagnosticTimestamp(stamp) || !canPersist()) return@runCatching false
        if (!dir.exists() && !dir.mkdirs()) return@runCatching false
        val target = fileFor(name)
        val partial = File(dir, "$name.part")
        val scaled = scaleDown(bitmap)
        try {
            val compressed = partial.outputStream().use { out ->
                // 压缩仅用于限制空间，不会消除画面里的个人信息。
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (!compressed || !settings.enabled || !settings.debugMode || !canPersist() ||
                !partial.renameTo(target)) return@runCatching false
        } finally {
            partial.delete()
            if (scaled !== bitmap) scaled.recycle()
        }
        prune()
        true
    }.getOrElse {
        // 异常和文件名可能携带本机路径，不把它们继续写入 logcat。
        Log.w(TAG, "诊断快照保存失败")
        false
    } }

    fun list(): List<File> =
        runCatching { dir.listFiles()?.filter { it.isFile && isSnapshotName(it.name) }
            ?.sortedByDescending { it.name } ?: emptyList() }
            .getOrDefault(emptyList())

    /** 只清理本应用生成的诊断图片，包含未完成写入；返回是否完整清理。 */
    fun clear(): Boolean = synchronized(SNAPSHOT_LOCK) {
        lastClearedAt = maxOf(lastClearedAt, System.currentTimeMillis())
        val cutoffSaved = runCatching { clearCutoff.writeText(lastClearedAt.toString()); true }.getOrDefault(false)
        val deleted = runCatching {
            val files = if (dir.exists()) dir.listFiles() ?: return@runCatching false else emptyArray()
            files.filter { item ->
                item.isFile && (isSnapshotName(item.name) ||
                    item.name.removeSuffix(".part").let { item.name.endsWith(".part") && isSnapshotName(it) })
            }.map { it.delete() }.all { it }
        }.getOrDefault(false)
        cutoffSaved && deleted
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= MAX_EDGE) return src
        val ratio = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** 只留最近 [MAX_FILES] 张。诊断模式一天能抓几十上百张，不设上限会把存储写满。 */
    private fun prune() {
        list().drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    companion object {
        private val SNAPSHOT_LOCK = Any()
        private var lastClearedAt = 0L
        private const val CLEAR_CUTOFF_FILE = "snapshot_clear_cutoff.txt"
        private val SNAPSHOT_NAME = Regex("snap_(\\d{1,19})\\.jpg")

        fun isSnapshotName(name: String): Boolean = snapshotTimestamp(name) != null

        private fun snapshotTimestamp(name: String): Long? =
            SNAPSHOT_NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it >= 0 }

        private const val TAG = "AdCalm"

        /** 目录名。导出用：`adb exec-out run-as cn.adcalm.guard tar cf - -C files snapshots`。 */
        const val DIR_NAME = "snapshots"

        /** 缩到这个边长以内。1080x2400 会缩成 576x1280，单张约 150KB。 */
        private const val MAX_EDGE = 1280

        private const val JPEG_QUALITY = 80

        /** 保留张数上限。 */
        private const val MAX_FILES = 40
    }
}
