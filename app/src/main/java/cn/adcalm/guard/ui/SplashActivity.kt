package cn.adcalm.guard.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * 打开应用时的一屏展示。
 *
 * 就是首页那个盾牌插画铺满整屏，停一下再进主界面。
 *
 * **这里不做任何事**——不读设置、不查权限、不加载数据，纯粹是视觉过渡。
 * 所以固定一小段时间就够（[SHOW_MS]），不会拖慢真正的启动：
 * 该做的初始化仍然发生在 [MainActivity] 里，和有没有这个页面无关。
 *
 * 时长刻意压得很短：它的作用是「打开时看一眼」，不是让用户等。
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(CalmShieldView(this))

        // postDelayed 而不是 Thread.sleep：不阻塞主线程，
        // 期间系统该做的窗口合成、进程预热照常进行。
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, SHOW_MS)
    }

    private companion object {
        const val SHOW_MS = 600L
    }
}
