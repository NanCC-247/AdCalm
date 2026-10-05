package cn.adcalm.guard.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
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
 */
class SnapshotStore(private val context: Context) {

    val dir: File get() = File(context.filesDir, DIR_NAME)

    /**
     * 生成快照文件名。
     *
     * 名字由时间戳决定，是为了**先写日志、后落图**：截屏是异步的，
     * 日志要同步写完整，所以调用方先拿名字，图随后补上。
     * 截屏失败时对应的图就不存在，日志里那条也只是少一张图，不会串行。
     */
    fun nameFor(timestamp: Long): String = "snap_$timestamp.jpg"

    fun fileFor(name: String): File = File(dir, name)

    /** 保存一张快照并顺带清理旧文件。返回是否成功。 */
    fun save(bitmap: Bitmap, name: String): Boolean = runCatching {
        dir.mkdirs()
        val scaled = scaleDown(bitmap)
        fileFor(name).outputStream().use { out ->
            // JPEG 质量 80 足够看清按钮位置和形状，体积约为无损的三分之一。
            // 这类快照的用途是"定位那块像素在屏幕的哪里"，不是做视觉设计评审。
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        if (scaled !== bitmap) scaled.recycle()
        prune()
        true
    }.getOrElse { e ->
        Log.w(TAG, "快照保存失败：$name", e)
        false
    }

    fun list(): List<File> =
        runCatching { dir.listFiles()?.sortedByDescending { it.name } ?: emptyList() }
            .getOrDefault(emptyList())

    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
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
