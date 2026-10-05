package cn.adcalm.guard.core

import java.util.Locale

/**
 * 找出**别的应用私有目录里的安装包**，供「清理广告安装包」页面列给用户判断。
 *
 * ## 为什么不在标准下载目录里找
 *
 * [cn.adcalm.guard.data.DownloadJanitor] 用 `FileObserver` 盯着三个目录
 * （`Download` / `Browser` / `download`），那一层负责标准下载目录。但 2026-10-04 实测下来，
 * 广告的安装包**根本不进这三个目录**，而是落在**广告 SDK 自己的缓存目录**里：
 *
 * ```
 * /sdcard/Android/data/<浏览器包名>/cache/<广告 SDK 的缓存目录>/apk/
 *     <被广告推下来的应用>.apk      400 MB
 *     <另一个>.apk                  107 MB
 * ```
 *
 * 这类路径有两个特点，决定了它必须走 shell 而不是 `File`：
 *
 * 1. **不在标准下载目录里**——`FileObserver` 挂不上去
 * 2. **在别的应用的私有目录里**——普通应用连读都读不到，只有 shell 碰得着
 *
 * ## 判据是位置，决定权在用户
 *
 * 这一层**不做自动删除**。早先试过"误跳之后按时间窗自动清掉"，后来去掉了——
 * 那等于程序替用户判断"哪个安装包是垃圾"，而它手里只有时间这一个证据，只能是猜。
 * 现在只做两件事：把够大的安装包列出来、把**缓存目录里的**默认勾上。
 * 删不删由用户看着办，见 [Kind]。
 *
 * （具体是哪些应用、哪些文件，记在不进仓库的 `docs/交接说明.md` 里。）
 *
 * 纯函数，可直接单元测试。
 */
object AdPackageCleaner {

    /** 安装包类扩展名。`.apk.tmp` / `.part` 是下载中的临时态，同样要清。 */
    val SUFFIXES = listOf("apk", "apks", "xapk", "apk.tmp", "part", "bin", "download")

    /**
     * 扫描根。都在别的应用的私有目录下，只有 shell（Shizuku）读得到。
     *
     * 不扫 `/sdcard/Download` 那类标准目录——那些由 [cn.adcalm.guard.data.DownloadJanitor]
     * 的 `FileObserver` 负责，而且那些目录里用户的正常下载更多，判错代价更大。
     */
    val SCAN_ROOTS = listOf("/sdcard/Android/data", "/sdcard/Android/obb")

    /**
     * 手动扫描**额外**覆盖的标准目录（2026-10-05 加）。
     *
     * 原来的分工是：标准目录交给 [cn.adcalm.guard.data.DownloadJanitor] 的 `FileObserver`，
     * 手动扫描只管别的应用的私有目录。但红队验出来两个洞——广告把安装包下到
     * `Documents` 这类**没人盯**的标准目录时两边都不管；改了扩展名之后按名字筛也看不见。
     *
     * 补这两条**不动手动扫描的定位**：它只把文件列出来、默认只勾"缓存残渣"，
     * 判错由用户看着办（[Kind]）。所以多扫几个目录不像自动清理那样有误删风险。
     */
    val EXTRA_SCAN_ROOTS = listOf("/sdcard/Download", "/sdcard/Documents")


    /**
     * 小于这个大小的不当安装包。
     *
     * 1 MB：真安装包不会这么小，而缓存目录里到处是几 KB 的碎片。
     * 这个下限主要是为了别把遍历结果里的噪声当成目标。
     */
    const val MIN_SIZE_BYTES = 1L * 1024 * 1024

    /** 扫描结果里的一条。 */
    data class Found(
        val path: String,
        /** 字节数。 */
        val sizeBytes: Long,
        /** 修改时间，**秒**（`stat %Y` 的单位）。 */
        val modifiedAtSec: Long,
    )

    /**
     * 解析一行 `stat -c '%s %Y %n'` 的输出（`大小 修改时间 路径`）。
     *
     * 脏行返回 null，不抛——扫描输出里混进一行怪东西不该让整轮清理失败。
     */
    fun parseStatLine(line: String): Found? {
        val text = line.trim()
        if (text.isEmpty()) return null

        val first = text.indexOf(' ')
        if (first <= 0) return null
        val second = text.indexOf(' ', first + 1)
        if (second <= first + 1) return null

        val size = text.substring(0, first).toLongOrNull() ?: return null
        val modified = text.substring(first + 1, second).toLongOrNull() ?: return null
        val path = text.substring(second + 1)
        if (path.isEmpty()) return null

        return Found(path, size, modified)
    }

    /** 路径是不是安装包（按扩展名）。 */
    fun looksLikePackage(path: String): Boolean {
        val lower = path.lowercase()
        return SUFFIXES.any { lower.endsWith(".$it") }
    }


    // ---- 手动扫描 ----
    //
    // 和上面那套自动清理是两条不同的路，判据也不同：
    //
    // | | 何时跑 | 判据 | 谁决定删不删 |
    // |---|---|---|---|
    // | 自动 | 误跳之后扫四轮 | **时间窗**（文件必须在那次误跳前后落盘） | 程序 |
    // | 手动 | 用户点「清理广告安装包」 | **位置**（在不在缓存目录里） | **用户**，程序只负责默认勾选 |
    //
    // 手动这条路没有时间锚点可用——用户可能隔了几小时才来点，那时窗口早过了。
    // 但它也不需要：用户会亲自审核，程序只要把"哪些是明显的残渣"标出来、默认勾上，
    // 剩下的交给人。所以这里的判据是位置而不是时间，**而且默认勾选的只是缓存目录里的**。

    /** 手动扫描时一条记录的性质。 */
    enum class Kind {
        /**
         * 落在缓存目录里。**默认勾选。**
         *
         * 缓存按定义就是可丢的副本：浏览器缓存里躺着一个用户当初下过的安装包，
         * 删掉不影响任何东西；广告 SDK 下在 `<别的应用>/cache/…/apk/` 里的
         * 那几百 MB 更是纯垃圾。
         */
        CACHE_RESIDUE,

        /**
         * 扩展名不对、但文件头看着是安装包（改了名的）。**默认不勾。**
         *
         * 判据比 [CACHE_RESIDUE] 弱一档：它只看文件前 256KB 里有没有 `AndroidManifest.xml`
         * （见 [cn.adcalm.guard.core.ShellCommands.listRenamedPackages]），理论上别的 ZIP
         * 也可能命中。所以只列出来给人看，不替用户默认勾上。
         *
         * 加它的起因是 2026-10-05 红队验证：`.apk` 改名成 `.dat` 之后，
         * 按扩展名筛的那条命令完全看不见它。
         */
        RENAMED_PACKAGE,

        /**
         * 在应用的数据目录里，但不在缓存下——比如应用市场自己的下载暂存。
         * **默认不勾**，只列出来供用户判断：那里可能有正在进行中的安装。
         */
        ELSEWHERE,
    }

    /** 扫描到的一条，带上性质。 */
    data class Scanned(val found: Found, val kind: Kind)

    /** 路径里有没有一段叫 `cache`。 */
    fun isCacheResidue(path: String): Boolean =
        path.split('/').any { it.equals("cache", ignoreCase = true) }

    /**
     * 手动扫描要不要列出这个文件。
     *
     * 只看"是不是个够大的安装包"——没有别的判据，所以列全一点没坏处，
     * 删不删由用户在界面上定。
     * 尺寸下限照旧：缓存目录里到处是几 KB 的碎片，列出来只会淹没真正要看的东西。
     */
    /** 尺寸够不够当一条目标（缓存目录里到处是几 KB 的碎片，列出来只会淹没真正要看的东西）。 */
    fun isBigEnough(found: Found): Boolean = found.sizeBytes >= MIN_SIZE_BYTES

    fun isListable(found: Found): Boolean =
        looksLikePackage(found.path) && isBigEnough(found)

    /**
     * 手动扫描的第二趟：解析 [cn.adcalm.guard.core.ShellCommands.listRenamedPackages] 的输出。
     *
     * 这些文件的扩展名不在安装包列表里，是**按文件头认出来的**。
     * 一律标成 [Kind.RENAMED_PACKAGE]（默认不勾），理由见那个枚举值的说明。
     */
    fun scanRenamed(lines: Sequence<String>): List<Scanned> =
        lines.mapNotNull { parseStatLine(it) }
            .filter { isBigEnough(it) }
            .map { Scanned(it, Kind.RENAMED_PACKAGE) }
            .toList()

    /** 手动扫描：解析、过滤、分类，缓存残渣排在前面，同类里大的在前。 */
    fun scan(lines: Sequence<String>): List<Scanned> =
        lines.mapNotNull { parseStatLine(it) }
            .filter { isListable(it) }
            .map { Scanned(it, if (isCacheResidue(it.path)) Kind.CACHE_RESIDUE else Kind.ELSEWHERE) }
            .sortedWith(compareBy({ it.kind.ordinal }, { -it.found.sizeBytes }))
            .toList()

    /**
     * 从 `/sdcard/Android/data/<包名>/…` 里取出**拥有这个文件的应用**。
     *
     * 界面上要显示"这是谁下的"，还要据此跳到那个应用的应用信息页——
     * 而 `/Android/data/<别的应用>/` 在 Android 11+ 上是**禁止浏览**的，
     * 文件管理器根本打不开那个目录。所以"跳过去"唯一能落地的去处就是
     * 拥有它的那个应用，这也正是用户真正想看的东西。
     *
     * @return 包名；路径里没有 `Android/data` 时返回 null——**不要编一个出来**
     */
    fun ownerPackageOf(path: String): String? {
        val marker = "/Android/data/"
        val index = path.indexOf(marker)
        if (index < 0) return null
        val owner = path.substring(index + marker.length).substringBefore('/')
        return owner.ifEmpty { null }
    }

    /** 路径里的文件名。 */
    fun fileNameOf(path: String): String = path.substringAfterLast('/')

    /**
     * 人类可读的大小。
     *
     * 显式指定 [Locale.US]：默认 Locale 下小数点可能是逗号，同一个数字在不同机器上
     * 会显示成不同样子，而它是要写进界面文案和日志的。
     */
    fun readableSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
