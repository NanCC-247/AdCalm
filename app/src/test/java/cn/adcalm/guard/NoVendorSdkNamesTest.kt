package cn.adcalm.guard

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合规护栏：**仓库里不能出现厂商广告 SDK 的标识。**
 *
 * 这条规矩写在 README 的「分享与法律风险」一节里，原来的守护者是
 * `ServiceDeclarationInstrumentedTest.默认规则库为空`——但它只管 `builtin.json`
 * 一个文件。2026-10-05 复查时发现，规则库里确实一条都没有，**但仓库别的地方有**：
 * 代码注释、测试夹具、README、CHANGELOG 里都散落着某个广告 SDK 的包名和缓存目录名。
 *
 * 所以这条测试扫的是**整个仓库**，把那条红线从"一个文件的断言"变成"全局的性质"。
 *
 * ## 为什么禁词是拼出来的
 *
 * 因为这个文件**自己也会被扫**。直接写全，就等于把禁词又写进了仓库——
 * 护栏变成违规。所以用片段拼装，代价是读起来别扭一点，但这正是它要防的东西。
 *
 * ## 什么在范围内
 *
 * **广告 SDK 的标识**——包名前缀、类名前缀、它自己建的缓存目录名。
 * 这些内容不指名任何东西也能表达清楚（"某个广告 SDK 的缓存目录"），所以没有理由留在仓库里。
 *
 * **不在范围内**：应用包名（某个浏览器、某个应用市场）。产品功能上**需要**它们——
 * `AppClassifier` 里那 67 个浏览器/市场/输入法/系统包是判定的依据，删了功能就没了；
 * 既然它们无论如何都在仓库里，文档里再回避一遍没有意义。
 *
 * ## 禁词表不是穷举
 *
 * 它只覆盖已知的、在这个项目里真实出现过的那几个。**它挡不住没列进来的厂商**，
 * 所以它是一条底线，不是一张保证。
 */
class NoVendorSdkNamesTest {

    /**
     * 把词拆成两半再拼起来。
     *
     * **不是故弄玄虚，是因为这个文件自己也会被扫。** 直接写全，护栏文件本身就违规了——
     * 护栏的意义是"仓库里没有这个词"，而它自己必须也满足这条。
     */
    private fun assemble(a: String, b: String): String = a + b

    /**
     * 禁词。
     */
    private val forbidden: List<Pair<String, String>> = listOf(
        // 某厂广告 SDK 的包名前缀（也是它 Activity 的包名）
        assemble("com.qq", ".e") to "某广告 SDK 的包名前缀",
        // 同一个 SDK 自己建的缓存目录名
        assemble("com_qq", "_e_download") to "某广告 SDK 的缓存目录名",
        // 另一个厂的广告类名前缀
        assemble("TT", "Ad") to "某广告 SDK 的类名前缀",
        // 其余已知前缀
        assemble("com.byte", "dance.sdk") to "某广告 SDK 的包名前缀",
        assemble("com.byte", "dance.pangle") to "某广告 SDK 的包名前缀",
        assemble("com.kw", "ad") to "某广告 SDK 的包名前缀",
        assemble("com.baidu", ".mobads") to "某广告 SDK 的包名前缀",
        assemble("com.sig", "mob") to "某广告 SDK 的包名前缀",
        assemble("com.mb", "ridge") to "某广告 SDK 的包名前缀",
        assemble("com.be", "izi") to "某广告 SDK 的包名前缀",
        assemble("com.jd", ".ad") to "某广告 SDK 的包名前缀",
    )

    /**
     * **全文件禁**：被本工具处理过的那些应用的**中文名**。
     *
     * 中文名在任何地方都不是功能必需的——代码里需要的是包名（ASCII），
     * 而"哪个应用"这件事本身没有功能作用。所以它比包名那条更严，哪里都不许有。
     */
    private val chineseAppNames: List<Pair<String, String>> = listOf(
        assemble("抖", "音") to "被处理过的短视频应用名",
        assemble("小", "黑盒") to "被处理过的社区应用名",
        assemble("携", "程") to "被处理过的旅行应用名",
        // 只拦那串数字**本身**，不拦"铁路"开头那个完整写法。原来只写了后者，于是
        // 只写数字、或者数字前面带个空格，**全都漏过去了**——
        // 2026-10-05 公开前审计就是这么发现有四处漏网的。
        // （这条注释自己也得守规矩：本文件在 skippedFiles 里，护栏扫不到它。）
        assemble("123", "06") to "被处理过的票务应用名",
        assemble("QQ浏览", "器") to "被处理过的浏览器名",
        assemble("UME浏览", "器") to "被处理过的浏览器名",
        assemble("ume 浏览", "器") to "被处理过的浏览器名",
        assemble("百度地", "图") to "被处理过的地图应用名",
        assemble("百度搜", "索") to "被处理过的搜索应用名",
        assemble("高德地", "图") to "被处理过的地图应用名",
        assemble("美", "团") to "被处理过的生活服务应用名",
        assemble("小红", "书") to "被处理过的社区应用名",
        assemble("拼多", "多") to "被处理过的购物应用名",
        // 下面这几个是公开前审计补的：原来那张表根本没列它们，
        // 于是「某购物应用」「某通讯应用」这两种代称在文档里用着，
        // 对应的真名却没人拦。**漏的原因不是难找，是没列。**
        assemble("淘", "宝") to "被处理过的购物应用名",
        assemble("微", "信") to "被处理过的通讯应用名",
        assemble("支付", "宝") to "被处理过的支付应用名",
        assemble("京", "东") to "被处理过的购物应用名",
    )

    /**
     * **只在文档里禁**：被处理过的那些应用的包名。
     *
     * 为什么代码里不禁：`AppClassifier` 那 67 个浏览器/市场/输入法/系统包是**功能依据**，
     * 删了判定就失效。既然它们无论如何都在仓库里，文档里再回避一遍意义不大——
     * 但文档里确实不需要点名，所以文档那条更严。
     */
    private val docOnlyForbidden: List<Pair<String, String>> = listOf(
        "com.tencent.mtt" to "被处理过的浏览器包名",
        "com.tencent.mm" to "被处理过的通讯应用包名",
        "com.ume.browser" to "被处理过的浏览器包名",
        "com.baidu.BaiduMap" to "被处理过的地图应用包名",
        "com.baidu.searchbox" to "被处理过的搜索应用包名",
        "com.MobileTicket" to "被处理过的票务应用包名",
        "ctrip.android" to "被处理过的旅行应用包名",
        "com.taobao.taobao" to "被处理过的购物应用包名",
        "com.jingdong.app.mall" to "被处理过的购物应用包名",
        "com.xunmeng.pinduoduo" to "被处理过的购物应用包名",
        "com.max.xiaoheihe" to "被处理过的社区应用包名",
        "com.kuaishou" to "被处理过的短视频应用包名",
        "com.ss.android.ugc.aweme" to "被处理过的短视频应用包名",
    )

    /** 扫描的文档扩展名——第二条禁词只作用于这几类。 */
    private val docExtensions = setOf("md")

    /** 扫这些扩展名。二进制和构建产物不扫。 */
    private val extensions = setOf("kt", "kts", "java", "md", "json", "json5", "xml", "yml", "yaml", "properties", "txt", "gradle", "sh", "pro")

    /** 不进仓库的目录，跳过。 */
    private val skippedDirs = setOf(
        ".git", "build", ".gradle", ".toolchain", ".idea", "design-review",
        "desktop-tools", "wallpaper-video-review", "artwork",
    )

    /**
     * 这几份文件**故意**保留实名，跳过：
     * - 本文件自己（禁词就写在这里，拼接也一样）
     * - `docs/交接说明.md`：在 `.gitignore` 里，不进仓库，是本地交接记录，
     *   实名证据（哪个应用、哪个 SDK、哪个安装包）都记在那里
     */
    private val skippedFiles = setOf(
        "NoVendorSdkNamesTest.kt",
        "交接说明.md",
    )

    @Test
    fun `仓库里不出现厂商广告 SDK 的标识`() {
        val root = findProjectRoot()
        assertTrue("找不到项目根目录，这条护栏没法验证", root != null)

        val hits = mutableListOf<String>()
        root!!.walkTopDown()
            .onEnter { it.name !in skippedDirs }
            .filter { it.isFile }
            .filter { it.extension.lowercase() in extensions }
            .filter { it.name !in skippedFiles }
            .forEach { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                // 三档，一档比一档严：广告 SDK 标识和中文应用名哪里都不许有；
                // 应用包名只在文档里禁（代码里那份功能名单是必需的）。
                val rules = buildList {
                    addAll(forbidden)
                    addAll(chineseAppNames)
                    if (file.extension.lowercase() in docExtensions) addAll(docOnlyForbidden)
                }
                for ((word, what) in rules) {
                    // 只报行号不报内容：把命中的那一行打进失败信息里，
                    // 等于换个地方又把禁词写了一遍
                    if (text.contains(word, ignoreCase = true)) {
                        val line = text.lineSequence()
                            .indexOfFirst { it.contains(word, ignoreCase = true) } + 1
                        hits += "${file.relativeTo(root).path}:$line（$what）"
                    }
                }
            }

        assertTrue(
            "这些文件里出现了广告 SDK 的标识，应当换成中性描述" +
                "（实名可以记到不进仓库的 docs/交接说明.md 里）：\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /**
     * 从测试的工作目录往上找项目根。
     *
     * Gradle 的测试工作目录是模块目录（`app/`），所以根在上一级——
     * 但不想写死这个假设：往上找带 `settings.gradle.kts` 的那一层，
     * 找不到就返回 null，由调用方报失败（而不是静默扫不到东西还通过）。
     */
    private fun findProjectRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(4) {
            val current = dir ?: return null
            if (File(current, "settings.gradle.kts").isFile) return current
            dir = current.parentFile
        }
        return null
    }
}
