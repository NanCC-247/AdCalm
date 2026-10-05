package cn.adcalm.guard.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import cn.adcalm.guard.core.AppClassifier
import cn.adcalm.guard.core.Permissions
import cn.adcalm.guard.data.DownloadJanitor

/**
 * 通过通知栏取消广告触发的下载。
 *
 * 广告的下载几乎都会在通知栏挂一条带「取消」按钮的进度通知。
 * 这里在检测到误跳之后的时间窗内，找出这类通知并直接触发它的取消 Action，
 * 等价于用户亲手点了「取消」。
 *
 * 严格限制在"刚发生过误跳"的窗口内，并且排除浏览器和应用市场之外的保护包——
 * 否则会把用户自己的下载也一起取消掉。
 */
class DownloadNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val adJumpAt = DownloadJanitor.lastAdJumpAt
        if (adJumpAt <= 0L) return

        val now = System.currentTimeMillis()
        if (now - adJumpAt > CANCEL_WINDOW_MS) return

        val pkg = sbn.packageName ?: return

        // 用户自己的软件不碰。只处理浏览器和应用市场这两类"广告落地页"。
        if (!AppClassifier.isBrowser(pkg) && !AppClassifier.isMarket(pkg)) return

        val notification = sbn.notification ?: return
        if (!looksLikeDownload(notification)) return

        if (tryCancel(notification)) {
            Log.i(TAG, "已取消 $pkg 的下载通知")
        }
    }

    /** 是否是下载类通知：有进度，或者文案里有下载字样。 */
    private fun looksLikeDownload(notification: Notification): Boolean {
        if (notification.extras.containsKey(Notification.EXTRA_PROGRESS)) return true

        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return DOWNLOAD_WORDS.any { title.contains(it) || text.contains(it) }
    }

    /** 找到「取消」按钮并触发它。找不到就放弃，不做别的动作。 */
    private fun tryCancel(notification: Notification): Boolean {
        val actions = notification.actions ?: return false
        for (action in actions) {
            val title = action.title?.toString() ?: continue
            if (CANCEL_WORDS.none { title.contains(it) }) continue
            val sent = runCatching { action.actionIntent.send() }.isSuccess
            if (sent) return true
        }
        return false
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "通知监听已连接，所有文件访问=${Permissions.hasAllFilesAccess(this)}")
    }

    private companion object {
        const val TAG = "AdCalm"

        /** 通知取消只在误跳后的一小段时间内生效，避免误伤用户自己的下载。 */
        const val CANCEL_WINDOW_MS = 120_000L

        val DOWNLOAD_WORDS = listOf("下载", "Download", "downloading", "正在下载")
        val CANCEL_WORDS = listOf("取消", "Cancel", "cancel", "停止")
    }
}
