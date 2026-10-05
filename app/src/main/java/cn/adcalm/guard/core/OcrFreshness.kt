package cn.adcalm.guard.core

import cn.adcalm.guard.model.RectSnapshot

/**
 * 「准备点下去的这一刻，界面还是当初截屏那一个吗？」
 *
 * ## 为什么需要它
 *
 * 截图是**异步**的：截屏 → ML Kit 离线识别 → 按坐标注入点击，中间隔着几百毫秒。
 * 这段时间里用户完全可能已经翻到别的页面了，而 OCR 手里只有一个坐标——
 * 那一刻点下去，就是在**新页面上**注入一次点击。
 *
 * 节点树那条路天生不用管这件事：它按 `path` + `bounds` 回去找同一个节点，
 * 界面换了就找不到，点空而已（`ClickExecutor` 会如实报失败）。
 * **OCR 没有这层兜底**——坐标永远"存在"，点哪儿都算成功。所以它得自己复核。
 *
 * ## 判据为什么取这三样
 *
 * 包名 + Activity 名 + 屏幕矩形。前两样缺一不可：
 * **只看包名的话，同一个应用内部的跳转认不出来**——而广告把人送到落地页、
 * 送到应用市场，恰恰大多发生在同一个包里面（真机日志里那次误跳就是
 * `<同一个浏览器>` 里换了 Activity）。这一点和 [CountdownTracker] 的签名是同一个道理：
 * 光有"是谁"不够，还得有"在哪一页"。
 *
 * 屏幕矩形是防转屏和分屏改尺寸的（`currentWindowMetrics.bounds`，输入法弹出不会改变它）。
 *
 * ## 读不到前台时怎么办：不拦
 *
 * `rootInActiveWindow` 在窗口切换的空档会返回 null。那意味着**问不出是谁**，
 * 不等于"变了"。判成变了的话，一次读取失败就白白丢掉一次正确的点击——
 * 而这个项目最怕的失败是「装上去什么都不点」，不是偶尔漏掉一次。
 * 所以：**只在拿到明确证据说"界面换了"时才拦**（fail-open）。
 *
 * 纯函数，不接触任何 Android 类，可直接单元测试。
 */
object OcrFreshness {

    /** 一次"这是哪个界面"的快照。 */
    data class Identity(
        val pkg: String,
        val activity: String?,
        val screen: RectSnapshot,
    )

    /**
     * @param before 截屏那一刻的身份
     * @param now    准备点下去那一刻的身份；[Identity.pkg] 为空表示问不出前台是谁
     * @return true 表示"还是同一个界面，可以点"
     */
    fun isSameScreen(before: Identity, now: Identity): Boolean {
        // 问不出前台是谁 → 不拦。理由见类注释最后一段。
        if (now.pkg.isEmpty()) return true

        if (now.pkg != before.pkg) return false
        if (now.screen != before.screen) return false

        // Activity 只在一侧读得到时不做判断：`currentActivity` 由窗口事件带上来，
        // 空档里可能是 null。拿"一边有一边没有"当跳转证据太脆，会误伤。
        val a = before.activity
        val b = now.activity
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return true
        return a == b
    }
}
