package cn.adcalm.guard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隔离判定的边界测试。
 *
 * 这是项目里唯一会动用户数据的逻辑，误判代价不对称，
 * 所以四个必要条件各写一组对偶用例：满足时要动手，不满足时要放过。
 */
class DownloadFilterTest {

    private val adJumpAt = 1_000_000L
    private val justAfter = adJumpAt + 5_000L

    private fun decide(
        path: String = "/storage/emulated/0/Download/ad.apk",
        createdAt: Long = justAfter,
        now: Long = justAfter,
        adJump: Long = adJumpAt,
        extraProtected: List<String> = emptyList(),
    ) = DownloadFilter.decide(path, createdAt, adJump, now, extraProtected)

    // ---- 四个条件都满足 ----

    @Test
    fun `广告跳转后落盘的 apk 应被隔离`() {
        assertTrue(decide() is QuarantineDecision.Quarantine)
    }

    // ---- 条件一：必须有广告跳转记录 ----

    @Test
    fun `没有广告跳转记录时什么都不动`() {
        val d = decide(adJump = 0L)
        assertTrue("没有广告事件就不该动任何文件，实际 $d", d is QuarantineDecision.Skip)
    }

    // ---- 条件二：文件必须晚于广告跳转 ----

    @Test
    fun `早于广告跳转的文件不碰`() {
        val d = decide(createdAt = adJumpAt - 1_000L)
        assertTrue(d is QuarantineDecision.Skip)
    }

    // ---- 条件三：必须在时间窗内 ----

    @Test
    fun `超出时间窗后不再隔离`() {
        val d = decide(now = adJumpAt + DownloadFilter.WINDOW_MS + 1)
        assertTrue(d is QuarantineDecision.Skip)
    }

    @Test
    fun `时间窗边界内仍然隔离`() {
        val now = adJumpAt + DownloadFilter.WINDOW_MS
        assertTrue(decide(now = now) is QuarantineDecision.Quarantine)
    }

    // ---- 条件四：必须是安装包 ----

    @Test
    fun `安装包相关的扩展名都能识别`() {
        val names = listOf(
            "ad.apk", "ad.APK", "ad.apk.tmp", "ad.apk.1",
            "bundle.apks", "bundle.xapk", "partial.part", "partial.bin", "x.download",
        )
        for (name in names) {
            assertTrue("$name 应被识别为安装包", DownloadFilter.isSuspiciousFileName(name))
        }
    }

    @Test
    fun `普通文件不会被误判`() {
        val names = listOf("photo.jpg", "report.pdf", "song.mp3", "video.mp4", "notes.txt", "apk")
        for (name in names) {
            assertFalse("$name 不该被识别为安装包", DownloadFilter.isSuspiciousFileName(name))
        }
    }

    @Test
    fun `下载目录里的普通文件不隔离`() {
        val d = decide(path = "/storage/emulated/0/Download/photo.jpg")
        assertTrue(d is QuarantineDecision.Skip)
    }

    // ---- 条件五：受保护目录 ----

    @Test
    fun `受保护目录里的文件绝不触碰`() {
        val protectedPaths = listOf(
            "/storage/emulated/0/DCIM/ad.apk",
            "/storage/emulated/0/Pictures/ad.apk",
            "/storage/emulated/0/Documents/setup.apk",
            "/storage/emulated/0/tencent/MicroMsg/wechat.apk",
        )
        for (path in protectedPaths) {
            val d = decide(path = path)
            assertTrue("$path 不该被隔离，实际 $d", d is QuarantineDecision.Skip)
        }
    }

    @Test
    fun `用户可以追加受保护目录`() {
        val path = "/storage/emulated/0/MyBackup/app.apk"
        assertTrue(decide(path = path) is QuarantineDecision.Quarantine)
        assertTrue(
            "加了白名单后就不该动",
            decide(path = path, extraProtected = listOf("/storage/emulated/0/MyBackup"))
                is QuarantineDecision.Skip,
        )
    }

    @Test
    fun `反斜杠分隔的路径也能正确判断保护目录`() {
        // 路径规范化是函数内部做的，这里传入原始的反斜杠形式
        assertTrue(
            DownloadFilter.isInProtectedDirectory("\\storage\\emulated\\0\\DCIM\\a.apk")
        )
        assertFalse(
            DownloadFilter.isInProtectedDirectory("\\storage\\emulated\\0\\Download\\a.apk")
        )
    }
}
