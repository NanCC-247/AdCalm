package cn.adcalm.guard.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import cn.adcalm.guard.R
import com.google.android.material.bottomnavigation.BottomNavigationView

/** Shared system-bar treatment and navigation for the mint interface. */
object CalmUi {
    const val EXTRA_START_TAB = "page"

    /**
     * 极简教程点「去首页配置」回首页时带上：在首页底部亮出「往下滑」的引导条。
     *
     * 只传"该引导了"这个意思，**不传滚动目标**——首页不做自动滚动（理由见
     * `MainActivity.showSetupGuide`），位置和时机都由首页自己算。
     */
    const val EXTRA_SETUP_GUIDE = "setup_guide"

    const val TAB_HOME = "home"
    const val TAB_SETTINGS = "settings"

    fun prepare(activity: Activity, root: View, onKeyboardVisibilityChanged: ((Boolean) -> Unit)? = null) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = activity.getColor(R.color.calm_surface)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        root.fitsSystemWindows = false
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            onKeyboardVisibilityChanged?.invoke(insets.isVisible(WindowInsetsCompat.Type.ime()))
            view.setPadding(
                left + bars.left,
                top + bars.top,
                right + bars.right,
                bottom + maxOf(bars.bottom, ime.bottom)
            )
            // The root already reserves these areas. In particular, Material's
            // bottom navigation must not apply the navigation inset a second time.
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun bindNavigation(
        activity: Activity,
        navigation: BottomNavigationView,
        activeItem: Int,
    ) {
        navigation.selectedItemId = activeItem
        navigation.setOnItemSelectedListener { item ->
            if (item.itemId == activeItem) {
                true
            } else {
                when (item.itemId) {
                    R.id.navApps -> activity.startActivity(
                        Intent(activity, AppPickerActivity::class.java)
                    )
                    R.id.navHome -> openMain(activity, TAB_HOME)
                    R.id.navSettings -> openMain(activity, TAB_SETTINGS)
                    else -> return@setOnItemSelectedListener false
                }
                if (activity !is MainActivity) activity.finish()
                true
            }
        }
    }

    private fun openMain(activity: Activity, page: String) {
        activity.startActivity(Intent(activity, MainActivity::class.java).apply {
            putExtra(EXTRA_START_TAB, page)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }
}
