package cn.adcalm.guard.data

import java.io.File
import java.io.RandomAccessFile

/**
 * 从观察日志里数出「每个应用出现过多少次广告判定」。
 *
 * 这是「多广告应用」推荐的唯一数据来源——不查任何外部清单，
 * 只看这台机器自己积累下来的记录。
 *
 * **刻意不解析整行 JSON**：日志里带节点树的行能有一两百 KB，
 * 几千行完整解析会卡住界面。这里只要两个字段，而且都写在行首
 * （`pkg` / `decision` 都在 `tree` 之前），所以每行只扫前 [HEADER_CHARS] 个字符。
 *
 * 大文件只扫末尾 [MAX_SCAN_BYTES]——推荐看的是「最近是不是有广告」，
 * 三个月前的记录没什么参考价值，也没必要为它把整份日志读一遍。
 */
object AdEvidence {

    /** 算作「这里有广告」的判定。观察类（只记不点）和点击类都算。 */
    private val AD_DECISIONS = setOf(
        "SUSPECT_ONLY",
        "WOULD_CLICK",
        "CLICKED",
        "CLICK_FAILED",
        "COOLDOWN",
        "OCR_CLICKED",
        "OCR_WOULD_CLICK",
    )

    /** 每行只看这么多个字符——够覆盖 `pkg` 和 `decision`，又不会碰到巨大的 tree 字段。 */
    private const val HEADER_CHARS = 2_000

    /** 最多往回读这么多字节。 */
    private const val MAX_SCAN_BYTES = 8L * 1024 * 1024

    /** @return 包名 → 广告判定条数。日志不存在或读不动时返回空表（调用方据此退回代理判据）。 */
    fun scan(logFile: File): Map<String, Int> = runCatching {
        if (!logFile.isFile || logFile.length() == 0L) return emptyMap()

        val counts = HashMap<String, Int>()
        RandomAccessFile(logFile, "r").use { raf ->
            val start = maxOf(0L, raf.length() - MAX_SCAN_BYTES)
            raf.seek(start)
            // 从中间开始读的话，第一行多半是被截断的，丢掉
            if (start > 0) raf.readLine()

            while (true) {
                val line = raf.readLine() ?: break
                if (line.isEmpty()) continue
                val head = if (line.length > HEADER_CHARS) line.substring(0, HEADER_CHARS) else line
                val decision = field(head, "decision") ?: continue
                if (decision !in AD_DECISIONS) continue
                val pkg = field(head, "pkg") ?: continue
                counts[pkg] = (counts[pkg] ?: 0) + 1
            }
        }
        counts
    }.getOrDefault(emptyMap())

    /** 取出 `"key":"value"` 里的 value。日志是本项目自己写的，值里不含转义字符。 */
    private fun field(line: String, key: String): String? {
        val marker = "\"$key\":\""
        val at = line.indexOf(marker)
        if (at < 0) return null
        val from = at + marker.length
        val to = line.indexOf('"', from)
        if (to < 0) return null
        return line.substring(from, to)
    }
}
