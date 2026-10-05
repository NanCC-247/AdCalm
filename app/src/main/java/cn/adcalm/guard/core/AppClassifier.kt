package cn.adcalm.guard.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * 判断一个包属于什么性质。
 *
 * 用途是界定"广告目标特征集"：浏览器、应用市场、没有桌面图标的包、
 * 以及刚装上不久的包——这几类是广告跳转最常落地的目标。
 * 用户自己的常用软件不会命中这里，因此不会被强停。
 */
object AppClassifier {

    /** 常见浏览器。广告点击后大多跳到浏览器落地页。 */
    val BROWSERS = setOf(
        "com.android.browser",
        "com.android.chrome",
        "com.chrome.beta",
        "com.tencent.mtt",
        "com.UCMobile",
        "com.uc.browser",
        "com.quark.browser",
        "com.baidu.searchbox",
        "com.baidu.browser.apps",
        "com.miui.browser",
        "com.huawei.browser",
        "com.heytap.browser",
        "com.vivo.browser",
        "com.meizu.flyme.browser",
        "com.sec.android.app.sbrowser",
        "com.qihoo.browser",
        "com.qihoo.contents",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.UCMobile.intl",
    )

    /** 常见应用市场。广告点击后跳到这里会自动开始下载安装包。 */
    val MARKETS = setOf(
        "com.android.vending",
        "com.xiaomi.market",
        "com.xiaomi.gamecenter",
        "com.huawei.appmarket",
        "com.heytap.market",
        "com.oppo.market",
        "com.bbk.appstore",
        "com.vivo.appstore",
        "com.tencent.android.qqdownloader",
        "com.qihoo.appstore",
        "com.baidu.appsearch",
        "com.sec.android.app.samsungapps",
        "com.lenovo.leos.appstore",
        "com.meizu.mstore",
        "com.wandoujia.phoenix2",
        "com.hiapk.marketpho",
    )

    /** 永不触碰的系统关键包。 */
    val SYSTEM_CRITICAL = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.dialer",
        "com.android.server.telecom",
        "com.android.mms",
        "com.android.providers.telephony",
        "com.google.android.apps.messaging",
        "com.android.shell",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    /** 常见输入法。强停输入法会让用户无法打字，属于必须硬保护的一类。 */
    val INPUT_METHODS = setOf(
        "com.android.inputmethod.latin",
        "com.google.android.inputmethod.latin",
        "com.baidu.input",
        "com.sohu.inputmethod.sogou",
        "com.iflytek.inputmethod",
        "com.tencent.qqpinyin",
        "com.tencent.wetype",
        "com.emoji.keyboard.touchpal",
    )

    /**
     * 各家 ROM 的系统安装器。
     *
     * 见 [isInstaller]——它们是广告下载闭环的最后一环，不是"用户的应用"。
     */
    val INSTALLERS = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.oppo.packageinstaller",
        "com.coloros.packageinstaller",
        "com.vivo.packageinstaller",
        "com.huawei.android.packageinstaller",
        "com.zte.packageinstaller",
    )

    fun isBrowser(pkg: String): Boolean = pkg in BROWSERS

    fun isMarket(pkg: String): Boolean = pkg in MARKETS

    fun isSystemCritical(pkg: String): Boolean = pkg in SYSTEM_CRITICAL

    fun isInputMethod(pkg: String): Boolean = pkg in INPUT_METHODS

    /**
     * 系统安装器。
     *
     * 广告的完整闭环通常是「点广告 → 浏览器落地页 → 下载 APK → 安装器弹出来」，
     * 安装器就是这条链的最后一环。它必须被归类为**广告落地面**而不是"用户的应用"——
     * 2026-10-04 实测：安装器 7 天累计前台 9 分 32 秒，被行为保护名单收成了
     * "用户自己在用的应用"，于是广告把它弹出来时，回退反而被保护名单挡住了。
     * 广告越频繁地拉它，它就越"受保护"，这是个自相矛盾的循环。
     *
     * 各家 ROM 的包名不统一，所以除了名单还匹配 `packageinstaller` 这个词。
     */
    fun isInstaller(pkg: String): Boolean =
        pkg in INSTALLERS || pkg.contains("packageinstaller", ignoreCase = true)

    /**
     * 是不是「广告落地面」——广告把用户弹过去之后落脚的地方。
     *
     * 这三类不享受行为保护：**用户对它们的"使用"很可能本身就是广告造成的**，
     * 拿它去给广告的下一次拉起做担保是说不通的。
     * 普通应用不受影响——你用的某通讯应用、某支付应用仍按行为保护名单走。
     */
    fun isAdLandingSurface(pkg: String): Boolean =
        isBrowser(pkg) || isMarket(pkg) || isInstaller(pkg)

    /** 是否拥有桌面图标。没有的包通常是服务组件或广告马甲包。 */
    fun hasLauncherIcon(context: Context, pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        val result: List<ResolveInfo> = context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return result.isNotEmpty()
    }

    /** 当前系统桌面包名集合。强停桌面会让手机没法用。 */
    fun launcherPackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    /**
     * 是否属于"广告目标特征集"——只有这些包才可能被强停。
     * 注意这里不含"用户自己的普通应用"，那部分由 [ProtectionRegistry] 的行为保护负责。
     */
    fun looksLikeAdTarget(context: Context, pkg: String, installedWithinDays: Long = 1): Boolean {
        if (isBrowser(pkg) || isMarket(pkg)) return true
        if (!hasLauncherIcon(context, pkg)) return true
        return isRecentlyInstalled(context, pkg, installedWithinDays)
    }

    /** 包是否在最近 N 天内安装。广告常"下载即装"，这类包值得重点清理。 */
    fun isRecentlyInstalled(context: Context, pkg: String, days: Long): Boolean {
        return try {
            val info = context.packageManager.getPackageInfo(pkg, 0)
            val ageMs = System.currentTimeMillis() - info.firstInstallTime
            ageMs in 0..(days * 24L * 60 * 60 * 1000)
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
