package cn.adcalm.guard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装包列表的判据。
 *
 * 数字都来自 2026-10-04 的真机现场——那次广告往浏览器的广告 SDK 缓存目录里
 * 下了两个安装包，而**同一批扫描结果里还混着用户自己下过的副本**。
 * 那批副本是「默认只勾缓存目录里的」这条规则的由来：
 * 把列出来的全默认勾上，就等于把用户自己的东西一起送进删除。
 *
 * 真实路径里的包名和 SDK 目录名**刻意不写在这里**，用 `com.example.*` 占位。
 * 带厂商标识的字面量不随项目分发，理由见 `assets/rules/builtin.json` 的 `_why_empty`；
 * 需要看实名的话，在不进仓库的 `docs/交接说明.md` 里。
 */
class AdPackageCleanerTest {

    private fun line(size: Long, mtimeSec: Long, path: String) = "$size $mtimeSec $path"

    /** 广告安装包落地的那个目录：别的应用私有目录 → 缓存 → 广告 SDK 自己的子目录。 */
    private val adDir = "/sdcard/Android/data/com.example.browser/cache/com.example.adsdk/apk"

    // ---- 解析 ----

    @Test
    fun `解析 stat 输出的一行`() {
        val f = AdPackageCleaner.parseStatLine(line(400753041, 1_791_127_800, "$adDir/netdisk.apk"))
        assertEquals("$adDir/netdisk.apk", f?.path)
        assertEquals(400753041L, f?.sizeBytes)
        assertEquals(1_791_127_800L, f?.modifiedAtSec)
    }

    @Test
    fun `路径里带空格也能解析出来`() {
        // 只按前两个空格切分，剩下的整段都是路径
        val f = AdPackageCleaner.parseStatLine(line(1024, 100, "/a b/c d/e.apk"))
        assertEquals("/a b/c d/e.apk", f?.path)
    }

    @Test
    fun `脏行返回 null 而不是抛异常`() {
        // 扫描输出里混进一行怪东西，不该让整轮清理失败
        for (bad in listOf("", "   ", "12345", "abc 123 /x.apk", "123 abc /x.apk", "123 456 ")) {
            assertNull("应当拒绝：[$bad]", AdPackageCleaner.parseStatLine(bad))
        }
    }

    // ---- 手动扫描 ----
    //
    // 手动这条路没有时间锚点（用户可能隔几小时才来点），判据换成"位置"：
    // 在不在缓存目录里。程序只负责把明显的残渣默认勾上，删不删由用户看着办。

    @Test
    fun `缓存目录的识别`() {
        assertTrue(AdPackageCleaner.isCacheResidue("$adDir/x.apk"))
        assertTrue(
            AdPackageCleaner.isCacheResidue(
                "/sdcard/Android/data/com.example.browser/files/assets/cache/abc/AdCalm.apk",
            ),
        )
        assertTrue(AdPackageCleaner.isCacheResidue("/a/Cache/b.apk"))
        assertFalse(
            AdPackageCleaner.isCacheResidue(
                "/sdcard/Android/data/com.example.market/files/market/apk/x.apk",
            ),
        )
        assertFalse(AdPackageCleaner.isCacheResidue("/sdcard/Android/data/a/files/b.apk"))
    }

    @Test
    fun `手动扫描不设时间窗，够大的安装包都列出来`() {
        val scanned = AdPackageCleaner.scan(
            sequenceOf(
                line(50000000, 1_000_000_000, "$adDir/old.apk"),              // 很旧，但仍要列
                line(1000, 1_000_000_000, "$adDir/tiny.apk"),                 // 太小
                line(50000000, 1_000_000_000, "$adDir/note.txt"),             // 不是安装包
            ),
        )
        assertEquals(1, scanned.size)
        assertEquals("$adDir/old.apk", scanned[0].found.path)
    }

    @Test
    fun `缓存残渣排在前面，同类里大的在前`() {
        val scanned = AdPackageCleaner.scan(
            sequenceOf(
                line(10000000, 1, "/sdcard/Android/data/com.example.market/files/market/apk/small.apk"),
                line(10000000, 1, "$adDir/b.apk"),
                line(300000000, 1, "$adDir/a.apk"),
            ),
        )
        assertEquals(AdPackageCleaner.Kind.CACHE_RESIDUE, scanned[0].kind)
        assertEquals("$adDir/a.apk", scanned[0].found.path)   // 缓存里最大的排最前
        assertEquals("$adDir/b.apk", scanned[1].found.path)
        assertEquals(AdPackageCleaner.Kind.ELSEWHERE, scanned[2].kind)
    }

    @Test
    fun `大小显示成人能读的`() {
        assertEquals("512 B", AdPackageCleaner.readableSize(512))
        assertEquals("1 KB", AdPackageCleaner.readableSize(1024))
        // 400753041 字节 ≈ 382 MB——别按"文件名里写着 400"去猜
        assertEquals("382 MB", AdPackageCleaner.readableSize(400753041))
        assertEquals("1.0 GB", AdPackageCleaner.readableSize(1024L * 1024 * 1024))
    }

    // ---- 「这是谁下的」 ----

    @Test
    fun `从路径里取出拥有它的应用`() {
        assertEquals("com.example.browser", AdPackageCleaner.ownerPackageOf("$adDir/x.apk"))
        assertEquals(
            "com.example.market",
            AdPackageCleaner.ownerPackageOf("/sdcard/Android/data/com.example.market/files/market/apk/x.apk"),
        )
    }

    @Test
    fun `路径里没有 Android data 时不编一个包名出来`() {
        // 编一个出来会让界面显示"所在应用 xxx"，而那是假的——
        // 界面上宁可少一行，也不要给错信息
        assertNull(AdPackageCleaner.ownerPackageOf("/sdcard/Download/x.apk"))
        assertNull(AdPackageCleaner.ownerPackageOf(""))
    }

    @Test
    fun `取文件名`() {
        assertEquals("x.apk", AdPackageCleaner.fileNameOf("/a/b/c/x.apk"))
        assertEquals("x.apk", AdPackageCleaner.fileNameOf("x.apk"))
    }

    // ---- 改了扩展名的安装包（2026-10-05 加，红队验出来的缺口）----

    @Test
    fun `改了扩展名的安装包单独归一类`() {
        // 真机探针：把 22MB 的真 APK 改名成 .dat 放进 Download，按文件头认出来的那一行
        val line = "21986610 1791197645 /sdcard/Download/probe_big.dat"
        val found = AdPackageCleaner.scanRenamed(sequenceOf(line))
        assertEquals(1, found.size)
        assertEquals(AdPackageCleaner.Kind.RENAMED_PACKAGE, found[0].kind)
        assertTrue(
            "判据比'在缓存目录里'弱一档，排序时排在它后面、更要靠用户判断",
            AdPackageCleaner.Kind.RENAMED_PACKAGE.ordinal > AdPackageCleaner.Kind.CACHE_RESIDUE.ordinal
        )
    }

    @Test
    fun `改名那一趟同样有大小下限`() {
        // 真机现场：14KB 的探针包被这条挡掉了——广告安装包都是几十兆，几 KB 的碎片不该列
        val small = "14799 1791197645 /sdcard/Download/probe_renamed.dat"
        assertTrue(AdPackageCleaner.scanRenamed(sequenceOf(small)).isEmpty())
    }
}
