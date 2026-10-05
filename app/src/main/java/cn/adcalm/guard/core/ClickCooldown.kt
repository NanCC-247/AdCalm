package cn.adcalm.guard.core

import cn.adcalm.guard.model.RectSnapshot

/**
 * 点击冷却。
 *
 * 本意是防"连点同一个按钮"，但真机日志暴露出它曾经挡错了对象：
 * 某浏览器那次先点了一个 65 分的空白节点（没关掉广告），一秒后真正的倒计时
 * 按钮出现了，却被冷却连着挡了两次，**白白多等 3 秒**。
 *
 * 所以现在只对**同一个位置**的元素冷却。换了位置说明上一次点错了或没生效，
 * 应该允许再点。另外保留一条全局硬间隔，防止在多个候选之间来回点。
 *
 * 纯逻辑，可直接单元测试。
 */
class ClickCooldown(
    private val sameElementCooldownMs: Long = DEFAULT_SAME_ELEMENT_MS,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {

    private var lastAt = 0L
    private var lastPackage: String? = null
    private var lastBounds: RectSnapshot? = null

    /**
     * "点它会把人带走"的位置，以及记下来的时刻。
     *
     * 用途见 [markLastClickAsJumping]。
     */
    private var jumpedPackage: String? = null
    private var jumpedBounds: RectSnapshot? = null
    private var jumpedAt = 0L

    /**
     * 把"上一次点的那个位置会跳转"记下来，之后**长时间不再点它**。
     *
     * 2026-10-05 用广告样机复现出来的场景：一个**假关闭**——点下去不是关闭，而是
     * 4 秒后跳走。回退会把人送回来，然后我们**又把它点了一遍**，于是
     * "点 → 被带走 → 回退 → 再点"成了一个循环，用户被反复弹进弹出。
     *
     * 这说明那次点击的结论就是错的：那个位置不是关闭按钮。既然已经知道它会跳，
     * 就该像人类一样记住它，而不是每 3 秒再试一次（[DEFAULT_SAME_ELEMENT_MS] 太短，
     * 挡不住这种几秒一轮的循环）。
     */
    fun markLastClickAsJumping(now: Long) {
        jumpedPackage = lastPackage
        jumpedBounds = lastBounds
        jumpedAt = now
    }

    /** 现在是否允许点击。 */
    fun allows(pkg: String, now: Long, bounds: RectSnapshot): Boolean {
        // 先看"点它会跳走"这条长期记录：命中就直接挡下，不看别的。
        // 这里用 [sameElement]（大小+中心都对得上），**不用 [overlaps]**——
        // 2026-10-05 用样机踩到：一页上诱饵和陷阱两个按钮本来就互相重叠，
        // 用相交判定会把诱饵也一起压住，于是整页都点不动了。
        // 大意是"同一颗按钮才是同一颗按钮"，不是"碰得到就算同一颗"。
        val marked = jumpedBounds
        if (marked != null && pkg == jumpedPackage && sameElement(bounds, marked) &&
            now - jumpedAt < JUMPED_COOLDOWN_MS
        ) {
            return false
        }

        // 全局硬间隔：任何两次点击之间都要留出最小间距
        if (lastAt != 0L && now - lastAt < minIntervalMs) return false

        // 从没点过，或换了个应用 —— 放行
        if (lastAt == 0L) return true
        if (pkg != lastPackage) return true

        // 同一个应用：超过冷却时间就放行
        if (now - lastAt >= sameElementCooldownMs) return true

        // 冷却期内——只有"同一个位置"才继续挡
        val previous = lastBounds ?: return false
        return !overlaps(bounds, previous)
    }

    /** 两个矩形是否相交。 */
    private fun overlaps(a: RectSnapshot, b: RectSnapshot): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /**
     * 两个矩形是不是**同一颗按钮**：宽高与中心都要对得上（各允许 [SAME_ELEMENT_SLOP_PX] 的误差）。
     *
     * 用在"点它会跳走"那条长期标记上：一页里互相重叠的两个按钮是两颗按钮，不是一颗。
     */
    private fun sameElement(a: RectSnapshot, b: RectSnapshot): Boolean =
        kotlin.math.abs(a.width - b.width) <= SAME_ELEMENT_SLOP_PX &&
            kotlin.math.abs(a.height - b.height) <= SAME_ELEMENT_SLOP_PX &&
            kotlin.math.abs(a.centerX - b.centerX) <= SAME_ELEMENT_SLOP_PX &&
            kotlin.math.abs(a.centerY - b.centerY) <= SAME_ELEMENT_SLOP_PX

    /** 点击成功后调用。 */
    fun record(pkg: String, now: Long, bounds: RectSnapshot) {
        lastPackage = pkg
        lastAt = now
        lastBounds = bounds
    }

    fun reset() {
        lastAt = 0L
        lastPackage = null
        lastBounds = null
    }

    internal companion object {
        const val DEFAULT_SAME_ELEMENT_MS = 3_000L
        const val DEFAULT_MIN_INTERVAL_MS = 400L

        /**
         * "点它会跳走"之后，同一个位置多久不再点。
         *
         * 取 10 分钟：比一轮广告长得多，足够让"点 → 被带走 → 回退 → 再点"的循环停下；
         * 又不至于永久——应用改版之后那个位置可能真的变成关闭按钮了。
         */
        const val JUMPED_COOLDOWN_MS = 10 * 60_000L

        /** [sameElement] 允许的误差（像素）：渲染往返会让同一颗按钮差几个像素。 */
        private const val SAME_ELEMENT_SLOP_PX = 16
    }
}
