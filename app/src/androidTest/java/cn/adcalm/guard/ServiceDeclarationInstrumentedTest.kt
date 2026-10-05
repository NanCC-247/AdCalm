package cn.adcalm.guard

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.XmlResourceParser
import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.adcalm.guard.rules.RuleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * 集成层的验证：无障碍服务的声明、能力标志、以及规则资源是否真的打进了包里。
 *
 * 这些是纯逻辑单测**测不到**的东西——它们要么依赖 Android 的运行时
 * （比如 org.json 的实际实现、二进制 XML 的解析），要么依赖打包结果。
 * 之前整个项目零仪器测试，这一类问题完全靠"应该没问题"糊过去。
 *
 * 写这几条用例的过程中踩了三个坑，都记在对应的注释里，因为它们都不是
 * "看一眼就知道"的错误——每一个都会让测试以错误的方式通过或失败。
 */
@RunWith(AndroidJUnit4::class)
class ServiceDeclarationInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        /** 无障碍服务配置里的属性都挂在这个命名空间下。 */
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val CONFIG_META_NAME = "android.accessibilityservice"
    }

    /**
     * 取出无障碍服务的 ServiceInfo。
     *
     * 坑 1：必须带 [PackageManager.GET_META_DATA]，否则 ServiceInfo.metaData 是 null。
     * 坑 2：不能用 MATCH_DEFAULT_ONLY——无障碍服务的 intent-filter 里只有 action，
     *       没有 CATEGORY_DEFAULT，带了那个标志一条都查不到。
     */
    private fun accessibilityServiceInfo(): ServiceInfo {
        val intent = Intent("android.accessibilityservice.AccessibilityService")
            .setPackage(context.packageName)
        val services = context.packageManager
            .queryIntentServices(intent, PackageManager.GET_META_DATA)

        assertEquals("应恰好声明一个无障碍服务", 1, services.size)
        return services.first().serviceInfo
    }

    /**
     * 取出已定位到根标签的配置 parser。
     *
     * 坑 3：loadXmlMetaData 返回的 parser 停在文档开头，**必须自己推进到 START_TAG**，
     * 否则 attributeCount 是 -1，读任何属性都会拿到默认值——测试会以为能力标志缺失。
     */
    private fun accessibilityConfig(): XmlResourceParser {
        val parser = accessibilityServiceInfo()
            .loadXmlMetaData(context.packageManager, CONFIG_META_NAME)
        assertNotNull("无障碍服务配置元数据应能解析", parser)

        var type = parser!!.eventType
        while (type != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) {
            type = parser.next()
        }
        assertEquals("应定位到根标签", XmlPullParser.START_TAG, type)
        return parser
    }

    /** 属性全导出来，断言失败时能直接看到真相而不是靠猜。 */
    private fun dumpAttributes(parser: XmlResourceParser): String = buildString {
        append("属性数=").append(parser.attributeCount).append(" [")
        for (i in 0 until parser.attributeCount) {
            append(parser.getAttributeName(i))
            append('=')
            append(parser.getAttributeValue(i))
            append(", ")
        }
        append(']')
    }

    @Test
    fun 无障碍服务被正确声明() {
        assertEquals(
            "cn.adcalm.guard.service.AdCalmAccessibilityService",
            accessibilityServiceInfo().name,
        )
    }

    @Test
    fun 无障碍服务的能力标志齐全() {
        val parser = accessibilityConfig()

        val dump = dumpAttributes(parser)
        // getAttributeBooleanValue 的三个属性都在 android 命名空间下
        assertTrue(
            "必须声明 canRetrieveWindowContent（少了它读不到节点树，整个应用失效）。实际：$dump",
            parser.getAttributeBooleanValue(ANDROID_NS, "canRetrieveWindowContent", false),
        )
        assertTrue(
            "必须声明 canPerformGestures（少了它节点不可点击时没有兜底手段）。实际：$dump",
            parser.getAttributeBooleanValue(ANDROID_NS, "canPerformGestures", false),
        )
        assertTrue(
            "必须声明 canTakeScreenshot（少了它 OCR 整条路径失效）。实际：$dump",
            parser.getAttributeBooleanValue(ANDROID_NS, "canTakeScreenshot", false),
        )
    }

    @Test
    fun 无障碍服务的包名白名单为空() {
        // 服务配置里不能限制 packageNames——误跳回退依赖"看到陌生应用被拉起来"，
        // 一旦把投递范围限死，回退功能就废了。
        val parser = accessibilityConfig()
        val attr = parser.getAttributeValue(ANDROID_NS, "packageNames")
        assertTrue("不应在配置里限定 packageNames，实际值：$attr", attr.isNullOrEmpty())
    }

    @Test
    fun 事件类型覆盖窗口状态与内容变化() {
        // 少了 typeWindowContentChanged 就抓不到倒计时刷新；
        // 少了 typeWindowStateChanged 就抓不到弹窗出现，也发现不了误跳。
        //
        // 注意：编译后的二进制 XML 把事件类型存成**整数位掩码**，
        // 读出来是 0x820 这种数字而不是 "typeWindowStateChanged|..." 字符串。
        val parser = accessibilityConfig()
        val types = parser.getAttributeIntValue(ANDROID_NS, "accessibilityEventTypes", 0)

        assertTrue(
            "应监听窗口状态变化（弹窗出现与误跳检测依赖它）。实际位掩码：0x${types.toString(16)}",
            types and AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED != 0,
        )
        assertTrue(
            "应监听窗口内容变化（倒计时刷新依赖它）。实际位掩码：0x${types.toString(16)}",
            types and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED != 0,
        )
    }

    @Test
    fun 规则资源真的打进了包并能解析() {
        // 这条能抓住两类问题：资源没被打进 APK；以及 Android 的 org.json
        // 与 JVM 单测里用的 org.json 实现行为不一致（两者确实有细微差别）。
        //
        // 注意：默认规则库**故意为空**（原因见 builtin.json 里的注释），
        // 所以这里不能断言"至少有一条规则"——只验证文件在、且是合法 JSON。
        val json = context.assets.open("rules/builtin.json")
            .bufferedReader()
            .use { it.readText() }

        assertTrue("规则文件不应为空", json.length > 100)

        val set = RuleRepository.parse(json)
        assertTrue("空规则集应能正常解析，不抛异常", set.size >= 0)
    }

    @Test
    fun 默认规则库为空() {
        // 这是一条合规护栏，不只是功能测试。
        //
        // 规则文件如果点名具体的广告 SDK 类名，读起来就是一份针对特定厂商的靶向清单。
        // 这类内容不应该随项目分发。默认留空是刻意的决定，不是遗漏——
        // 详见 builtin.json 里的 _why_empty。
        //
        // 本应用的实际识别能力来自启发式打分与 OCR 兜底，不依赖这个文件。
        val json = context.assets.open("rules/builtin.json")
            .bufferedReader()
            .use { it.readText() }

        assertTrue("规则文件应能被打进包并读取", json.length > 100)

        val set = RuleRepository.parse(json)
        assertEquals("默认规则库应不包含任何规则", 0, set.size)
    }
}
