package cn.adcalm.guard.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * APK 完整性校验与文件名提取的测试。
 *
 * 校验是拦住"把垃圾文件送去安装"的最后一道关：镜像限流时会返回
 * HTTP 200 + 一个 HTML 页面，光看状态码和大小都拦不住。
 *
 * ⚠ 有一点要说明：这个校验刚加上时，我把它当成"抓到了截断文件"的证据，
 * 但后来解包看了——那个 2.5MB 的文件**是完整的 Shizuku APK**
 * （含 libshizuku.so / libadb.so），Shizuku 本体就这么大。是我误判了。
 * 校验本身仍然有价值（HTML 错误页、真截断都能拦），但当时并没有抓到真问题。
 */
class ShizukuInstallerTest {

    private fun tempFile(suffix: String = ".apk"): File =
        File.createTempFile("adcalm-test", suffix).apply { deleteOnExit() }

    /** 造一个最小可用的 APK 结构：ZIP，根目录含 AndroidManifest.xml。 */
    private fun writeFakeApk(file: File, manifest: ByteArray = "dummy-manifest".toByteArray()) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(1024))
            zip.closeEntry()
        }
    }

    @Test
    fun `结构正确的 APK 被接受`() {
        val f = tempFile()
        writeFakeApk(f)
        assertTrue("ZIP 里含 AndroidManifest.xml 就应通过", ShizukuInstaller.isValidApk(f))
    }

    @Test
    fun `HTML 错误页被拒绝`() {
        // 镜像限流时经常返回 HTTP 200 + 一个 HTML 页面，
        // 光看大小和状态码都拦不住，必须校验内容。
        val f = tempFile()
        f.writeText("<html><head><title>Rate limited</title></head><body>请稍后再试</body></html>")
        assertFalse(ShizukuInstaller.isValidApk(f))
    }

    @Test
    fun `不含 AndroidManifest 的 ZIP 被拒绝`() {
        val f = tempFile(".zip")
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("hi".toByteArray())
            zip.closeEntry()
        }
        assertFalse("是合法 ZIP 但不是 APK", ShizukuInstaller.isValidApk(f))
    }

    @Test
    fun `截断的 APK 被拒绝`() {
        // ZIP 的中央目录在文件尾部，截断后解析必然失败。
        // 这正是下载到一半断流时的形态。
        val whole = tempFile()
        writeFakeApk(whole, ByteArray(200_000) { it.toByte() })
        val bytes = whole.readBytes()
        val truncated = tempFile()
        truncated.writeBytes(bytes.copyOf(bytes.size / 2))
        assertFalse(ShizukuInstaller.isValidApk(truncated))
    }

    @Test
    fun `空文件被拒绝`() {
        assertFalse(ShizukuInstaller.isValidApk(tempFile()))
    }

    @Test
    fun `不存在的文件被拒绝而不是抛异常`() {
        assertFalse(ShizukuInstaller.isValidApk(File("/definitely/not/here.apk")))
    }

    @Test
    fun `随机二进制被拒绝`() {
        val f = tempFile()
        val out = ByteArrayOutputStream()
        // 故意不以 PK 开头
        out.write("NOTAZIP".toByteArray())
        out.write(ByteArray(50_000) { (it % 251).toByte() })
        f.writeBytes(out.toByteArray())
        assertFalse(ShizukuInstaller.isValidApk(f))
    }

    // ---- 提取文件名（给"请手动下载"的提示用）----

    @Test
    fun `从下载地址里取出文件名`() {
        val url = "https://github.com/RikkaApps/Shizuku/releases/download/" +
            "v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk"
        assertEquals(
            "shizuku-v13.6.0.r1086.2650830c-release.apk",
            ShizukuInstaller.apkFileName(url),
        )
    }

    @Test
    fun `镜像前缀不影响文件名提取`() {
        val url = "https://gh.llkk.cc/https://github.com/RikkaApps/Shizuku/releases/" +
            "download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk"
        assertEquals(
            "shizuku-v13.6.0.r1086.2650830c-release.apk",
            ShizukuInstaller.apkFileName(url),
        )
    }

    @Test
    fun `地址里没有 apk 时给一个通配提示而不是空字符串`() {
        // 用户在发布页看到的是 Assets 里的文件名，给个通配比给空串有用
        val name = ShizukuInstaller.apkFileName("https://github.com/RikkaApps/Shizuku/releases")
        assertTrue("应给出通配形式，实际 $name", name.contains("shizuku") && name.contains(".apk"))
    }
}
