package cn.adcalm.guard

import android.app.Application
import cn.adcalm.guard.shizuku.ShizukuShell

/**
 * 应用入口。
 *
 * 目前只做一件事：把 application context 交给 [ShizukuShell]。
 * 它是个 object，拿不到 Context 参数，但要查 Shizuku 应用装了没有。
 */
class AdCalmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuShell.appContext = applicationContext
    }
}
