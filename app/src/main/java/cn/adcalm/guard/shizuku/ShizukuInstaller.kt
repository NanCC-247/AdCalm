package cn.adcalm.guard.shizuku
import android.content.Context
import android.content.Intent
import android.net.Uri
object ShizukuInstaller {
    const val OFFICIAL_RELEASES_URL = "https://github.com/RikkaApps/Shizuku/releases"
    fun openOfficialPage(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
