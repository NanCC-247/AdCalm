package cn.adcalm.guard.core

/** 是否把某个文件移进隔离区。 */
sealed interface QuarantineDecision {
    data class Quarantine(val reason: String) : QuarantineDecision
    data class Skip(val reason: String) : QuarantineDecision
}

/**
 * 判断一个文件是不是"广告下载下来的安装包"。
 *
 * 这是整个项目里唯一会动用户数据的判断，所以条件收得很紧：
 * **四个条件全部满足**才会动手，任何一个不满足都放过。
 *
 * 误判的代价是不对称的——漏掉一个广告 apk 只是占几 MB 空间，
 * 误动一个用户正在下载的文件是数据丢失。
 */
object DownloadFilter {

    private val SUSPICIOUS_SUFFIXES = listOf(
        ".apk",
        ".apk.tmp",
        ".apk.1",
        ".apks",
        ".xapk",
        ".part",
        ".bin",
        ".download",
    )

    /** 用户资料目录。这些地方无论什么情况都不动。 */
    private val DEFAULT_PROTECTED_PREFIXES = listOf(
        "/storage/emulated/0/DCIM",
        "/storage/emulated/0/Pictures",
        "/storage/emulated/0/Movies",
        "/storage/emulated/0/Music",
        "/storage/emulated/0/Documents",
        "/storage/emulated/0/WeiXin",
        "/storage/emulated/0/tencent/MicroMsg",
    )

    fun isSuspiciousFileName(name: String): Boolean {
        val lower = name.lowercase()
        return SUSPICIOUS_SUFFIXES.any { lower.endsWith(it) }
    }

    fun isInProtectedDirectory(path: String, extraPrefixes: List<String> = emptyList()): Boolean {
        val normalized = path.replace('\\', '/')
        return (DEFAULT_PROTECTED_PREFIXES + extraPrefixes).any { normalized.startsWith(it) }
    }

    /**
     * @param createdAt 文件创建时间。早于广告跳转的文件与这次广告无关。
     * @param adJumpAt  最近一次误跳的时间戳；0 表示没有记录
     */
    fun decide(
        path: String,
        createdAt: Long,
        adJumpAt: Long,
        now: Long,
        extraProtectedPrefixes: List<String> = emptyList(),
    ): QuarantineDecision {
        if (adJumpAt <= 0L) {
            return QuarantineDecision.Skip("没有广告跳转记录")
        }
        if (createdAt < adJumpAt) {
            return QuarantineDecision.Skip("文件早于广告跳转就已存在")
        }
        if (now - adJumpAt > WINDOW_MS) {
            return QuarantineDecision.Skip("超出广告跳转的时间窗")
        }

        val name = path.substringAfterLast('/')
        if (!isSuspiciousFileName(name)) {
            return QuarantineDecision.Skip("扩展名不是安装包")
        }
        if (isInProtectedDirectory(path, extraProtectedPrefixes)) {
            return QuarantineDecision.Skip("位于受保护目录")
        }

        return QuarantineDecision.Quarantine("广告跳转后 ${(now - adJumpAt) / 1000} 秒内出现的安装包")
    }

    /** 广告跳转后的观察窗口。只有这段时间内出现的文件才可能与广告有关。 */
    const val WINDOW_MS = 120_000L
}
