package cn.adcalm.guard.shizuku

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 下载并安装 Shizuku。
 *
 * 这是**整个应用里唯一会联网的地方**，且只在用户主动点「下载 Shizuku」时触发。
 * 广告识别、判定、点击全程离线。README 与免责声明里对此有明确说明——
 * 往这个文件里加任何别的网络请求之前，先把那段说明改掉。
 *
 * 从 GitHub 官方仓库取最新 release：先查 API 拿资源直链，再下载。不写死版本号，
 * 否则会随着 Shizuku 更新而过期。
 *
 * **安装必须由用户确认**：Android 不允许应用静默安装 APK（除非是设备所有者），
 * 所以这里只做到"把系统安装器拉起来"，最后一步由系统对话框完成。
 */
object ShizukuInstaller {

    private const val TAG = "AdCalm"
    private const val REPO = "RikkaApps/Shizuku"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val APK_NAME = "shizuku-installer.apk"

    /** 下载到的文件小于这个大小就直接放弃，省得对明显的错误页做后续处理。 */
    private const val MIN_APK_BYTES = 1_000_000L

    /**
     * 下载源，按实测速度排序。
     *
     * 2026-10 在桌面端实测（直连 363 KB/s、gh.llkk.cc 415 KB/s、ghfast.top 402 KB/s、
     * gh-proxy.com 313 KB/s、ghproxy.net 187 KB/s）。桌面端直连完全可用，
     * 但**手机端经常差两个数量级**——实测过 3.6 KB/s。
     *
     * 那多半是运营商到境外的路由问题（5G 移动数据下尤其明显），换源改善有限。
     * 真正有用的是 [SLOW_SPEED_FLOOR] 那个"太慢就换下一个"的判断，
     * 以及全部失败时给用户一个带官方地址的说明——让他在电脑上下好再传过来，
     * 比在手机上等十分钟现实得多。
     */
    private val MIRROR_PREFIXES = listOf(
        "https://gh.llkk.cc/",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "",  // 空串 = 直连，放最后
    )

    /**
     * 判定"这个源太慢"的门槛。
     *
     * 开始下载后先观察 [SLOW_CHECK_WINDOW_MS]，如果拿到的字节数低于
     * [SLOW_SPEED_FLOOR]，就放弃这个源换下一个。实测手机上遇到过 3.6 KB/s，
     * 按那个速度下 2.5MB 要十几分钟——与其干等，不如换源或者干脆让用户手动下。
     */
    private const val SLOW_CHECK_WINDOW_MS = 15_000L
    private const val SLOW_SPEED_FLOOR = 100_000L  // 15 秒至少要有 100KB

    /**
     * 镜像优先，直连放最后。
     *
     * 真机实测：国内直连 `objects.githubusercontent.com` 会在 TLS 握手阶段
     * 一直超时，白等 20 秒才轮到下一个。所以把镜像排在前面。
     */
    private fun candidateUrls(original: String): List<String> =
        MIRROR_PREFIXES.filter { it.isNotEmpty() }.map { "$it$original" } + original

    /** 从下载地址里取文件名，给"请手动下载"的提示用。 */
    fun apkFileName(url: String): String =
        url.substringAfterLast('/').takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "shizuku-*-release.apk"

    /**
     * 查最新版本的 APK 下载地址。
     *
     * @return 直链；查询失败返回 null
     */
    fun resolveLatestApkUrl(): String? =
        withConnection(API_LATEST, connectMs = 10_000, readMs = 15_000) { conn ->
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) {
                Log.w(TAG, "查询 Shizuku 最新版失败，HTTP ${conn.responseCode}")
                return@withConnection null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val assets = JSONObject(body).optJSONArray("assets") ?: return@withConnection null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
                val url = asset.optString("browser_download_url")
                if (url.isNotEmpty()) return@withConnection url
            }
            null
        }

    /**
     * 下载 APK 到应用缓存目录。
     *
     * 直连失败会自动改用镜像重试——GitHub 的下载域名在国内通常不可达，
     * 这一步是必需的而不是优化。
     *
     * @param onProgress 进度回调 `(已下载字节, 总字节)`。**总字节可能是 -1**——
     *   镜像常常不回 Content-Length，这时调用方应退化成"只显示已下载量"，
     *   不要因为拿不到总数就不更新界面。
     * @return 下载好的文件；全部途径都失败返回 null
     */
    fun download(
        context: Context,
        url: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    ): File? {
        for (candidate in candidateUrls(url)) {
            val file = downloadOnce(context, candidate, onProgress)
            if (file != null) {
                if (candidate != url) Log.i(TAG, "直连失败，已通过镜像下载成功：$candidate")
                return file
            }
            onProgress(0, 0)
        }
        return null
    }

    /** 从单个地址下载一次。失败返回 null。 */
    private fun downloadOnce(
        context: Context,
        url: String,
        onProgress: (Long, Long) -> Unit,
    ): File? = withConnection(url, connectMs = 15_000, readMs = 20_000) { conn ->
        if (conn.responseCode != 200) {
            Log.w(TAG, "下载失败 HTTP ${conn.responseCode}：$url")
            return@withConnection null
        }

        val dest = File(context.cacheDir, APK_NAME)
        if (dest.exists()) dest.delete()

        // 可能是 -1：服务端没给 Content-Length。不能因此就不报进度。
        val total = conn.contentLength.toLong()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var read = 0L
        var speedChecked = false

        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    output.write(buffer, 0, n)
                    read += n
                    onProgress(read, total)

                    // 开跑一段时间后体检一次：太慢就放弃这个源，换下一个。
                    // 实测在手机上遇到过 3.6 KB/s——按那个速度下 2.5MB 要十几分钟，
                    // 与其让用户干等，不如早点换源或者干脆建议他手动下。
                    if (!speedChecked &&
                        android.os.SystemClock.elapsedRealtime() - startedAt >= SLOW_CHECK_WINDOW_MS
                    ) {
                        speedChecked = true
                        if (read < SLOW_SPEED_FLOOR) {
                            Log.w(
                                TAG,
                                "源太慢：${SLOW_CHECK_WINDOW_MS / 1000} 秒只拿到 $read 字节，放弃：$url",
                            )
                            dest.delete()
                            return@withConnection null
                        }
                    }
                }
            }
        }

        // 只有拿到 Content-Length 时才做字节数比对。
        if (total > 0 && read < total) {
            Log.w(TAG, "下载不完整：只拿到 $read / $total 字节：$url")
            dest.delete()
            return@withConnection null
        }

        // 兜底：镜像可能返回一个 HTTP 200 但内容错误的页面（限流提示之类）。
        // 光看大小不可靠——一个 HTML 错误页也可能有几百 KB。
        //
        // 注意 shizuku 的 APK 只有 2.5MB 左右（体积主要在 native 库，且不含 x86），
        // 别看到这个数字就以为是截断——真机上我这么误判过一次。
        if (!isValidApk(dest)) {
            Log.w(TAG, "下载的文件不是有效 APK（${dest.length()} 字节）：$url")
            dest.delete()
            return@withConnection null
        }

        Log.i(TAG, "下载完成：${dest.length()} 字节，来源 $url")
        onProgress(read, if (total > 0) total else read)
        dest
    }

    /**
     * 校验文件是不是一个像样的 APK。
     *
     * APK 本质是 ZIP，根目录下必须有 `AndroidManifest.xml`。
     * 光看大小不可靠——一个 HTML 错误页也可能有几百 KB。
     */
    internal fun isValidApk(file: File): Boolean = runCatching {
        java.util.zip.ZipFile(file).use { zip ->
            zip.getEntry("AndroidManifest.xml") != null
        }
    }.getOrDefault(false)

    /**
     * 拉起系统安装器。
     *
     * 到这一步之后由用户确认——应用无法静默安装 APK，这是 Android 的设计。
     * 用 FileProvider 暴露缓存目录里的文件，而不是往共享存储里写。
     */
    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrElse {
        Log.w(TAG, "拉起安装器失败", it)
        false
    }

    /** 清掉下载缓存。装完之后调用，别让 APK 一直躺在缓存里。 */
    fun clearCache(context: Context) {
        runCatching { File(context.cacheDir, APK_NAME).takeIf { it.exists() }?.delete() }
    }

    /**
     * 打开一个 HttpURLConnection 并在结束时断开。
     *
     * `HttpURLConnection` 没有实现 `Closeable`，所以不能用 `use {}`，
     * 只能显式 disconnect。异常一并吞掉返回 null——调用方按"失败"处理。
     */
    private fun <T> withConnection(
        url: String,
        connectMs: Int,
        readMs: Int,
        block: (HttpURLConnection) -> T,
    ): T? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectMs
                readTimeout = readMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AdCalm")
            }
            block(conn)
        } catch (e: Exception) {
            Log.w(TAG, "网络请求失败：$url", e)
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
