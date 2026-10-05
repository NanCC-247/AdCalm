package cn.adcalm.guard.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import cn.adcalm.guard.R
import cn.adcalm.guard.core.GuardSettings
import cn.adcalm.guard.databinding.ActivityAppPickerBinding
import cn.adcalm.guard.service.AdCalmAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 选择本工具要介入哪些应用。
 *
 * 默认一个都不选。用户没勾的 App，无障碍服务收到其窗口事件会直接返回，
 * 不做任何读取和判定。
 *
 * 完整的应用清单由本 Activity 持有（[master]），适配器只渲染按搜索词过滤后的子集。
 * 两边共享同一批 [AppEntry] 实例，因此过滤时勾选状态不会丢。
 */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppPickerBinding
    private lateinit var settings: GuardSettings
    private lateinit var adapter: AppListAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var master: List<AppEntry> = emptyList()
    private var visible: List<AppEntry> = emptyList()
    private var query: String = ""
    private var includeHidden: Boolean = false
    private var selectedOnly: Boolean = false
    private var loading: Boolean = true
    private var loadJob: Job? = null
    private var keyboardVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CalmUi.prepare(this, binding.root) { visible -> updateKeyboardVisibility(visible) }
        CalmUi.bindNavigation(this, binding.bottomNavigation, R.id.navApps)
        binding.btnBack.setOnClickListener { finish() }
        selectedOnly = savedInstanceState?.getBoolean("selectedOnly") ?: false

        settings = GuardSettings(
            getSharedPreferences(AdCalmAccessibilityService.PREFS_NAME, MODE_PRIVATE)
        )
        includeHidden = settings.includeHiddenApps

        adapter = AppListAdapter { _, _ -> syncSettings() }
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
        binding.rvApps.itemAnimator = null

        binding.etSearch.addTextChangedListener { text ->
            query = text?.toString()?.trim().orEmpty()
            refreshVisible()
        }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
                binding.etSearch.clearFocus()
                true
            } else {
                false
            }
        }

        binding.cbIncludeHidden.isChecked = includeHidden
        binding.cbIncludeHidden.setOnCheckedChangeListener { _, checked ->
            includeHidden = checked
            settings.includeHiddenApps = checked
            loadApps()
        }

        binding.btnSelectAll.setOnClickListener { confirmSelectAll() }
        binding.btnFilterAll.setOnClickListener { setSelectedFilter(false) }
        binding.btnFilterSelected.setOnClickListener { setSelectedFilter(true) }
        binding.btnInvert.setOnClickListener {
            adapter.invert()
            syncSettings()
        }
        binding.btnClearAll.setOnClickListener {
            adapter.setAll(false)
            syncSettings()
        }

        updateFilterAppearance()
        loadApps()
    }

    private fun loadApps() {
        loadJob?.cancel()
        val selected = settings.targetedPackages
        val hidden = includeHidden
        loading = true
        updateCount()
        loadJob = scope.launch {
            // 枚举并加载图标是 IO 密集操作，放后台线程，避免列表卡顿
            val apps = withContext(Dispatchers.IO) {
                AppListAdapter.loadInstalledApps(this@AppPickerActivity, selected, hidden)
            }
            master = apps
            loading = false
            refreshVisible()
        }
    }

    private fun refreshVisible() {
        visible = master.filter {
            (!selectedOnly || it.selected) &&
                (query.isEmpty() || it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true))
        }
        adapter.submit(visible)
        updateCount()
    }

    private fun setSelectedFilter(value: Boolean) {
        selectedOnly = value
        updateFilterAppearance()
        refreshVisible()
    }

    private fun updateKeyboardVisibility(visible: Boolean) {
        if (keyboardVisible == visible) return
        keyboardVisible = visible
        val visibility = if (visible) View.GONE else View.VISIBLE
        binding.selectionSummary.visibility = visibility
        binding.bulkActions.visibility = visibility
        binding.cbIncludeHidden.visibility = visibility
        binding.bottomNavigation.visibility = visibility
        updateToolbarCount()
    }

    private fun updateToolbarCount() {
        binding.tvToolbarStatus.text = if (keyboardVisible) "${master.count { it.selected }} 个已选" else "自动保存"
        binding.tvToolbarStatus.contentDescription = if (keyboardVisible) {
            "已选择 ${master.count { it.selected }} 个应用，选择自动保存"
        } else {
            "选择自动保存"
        }
    }

    private fun updateFilterAppearance() {
        listOf(binding.btnFilterAll to !selectedOnly, binding.btnFilterSelected to selectedOnly)
            .forEach { (button, active) ->
                button.isSelected = active
                button.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (active) R.color.calm_primary_dark else R.color.calm_surface)
                )
                button.setTextColor(ContextCompat.getColor(this, if (active) R.color.calm_surface else R.color.calm_text))
                button.strokeColor = ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (active) R.color.calm_primary_dark else R.color.calm_outline)
                )
                button.contentDescription = "${button.text}，${if (active) "当前筛选" else "切换筛选"}"
            }
    }

    /** 任何勾选变化之后，从完整清单推导并整体写回设置，避免两处状态漂移。 */
    private fun syncSettings() {
        // master 只含**当前装着**的应用。直接整体写回会把已卸载但仍被选中的包
        // 悄悄抹掉——用户把那个应用装回来时会发现它不在名单里了。
        // 所以保留 master 覆盖不到的那部分，只更新它管得到的。
        val known = master.mapTo(HashSet()) { it.packageName }
        val stale = settings.targetedPackages.filterNot { it in known }
        settings.targetedPackages =
            (stale + master.filter { it.selected }.map { it.packageName }).toSet()
        refreshVisible()
    }

    private fun updateCount() {
        val selected = master.count { it.selected }
        updateToolbarCount()
        binding.tvSelectedCount.text = if (loading && master.isEmpty()) {
            "正在读取应用"
        } else {
            "$selected 个已选应用"
        }
        binding.tvListSummary.text = if (loading) "正在加载" else "${visible.size} / ${master.size} 个"
        binding.loadingState.visibility = if (loading) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (!loading && visible.isEmpty()) View.VISIBLE else View.GONE
        binding.btnSelectAll.isEnabled = !loading && visible.isNotEmpty()
        binding.btnInvert.isEnabled = !loading && visible.isNotEmpty()
        binding.btnClearAll.isEnabled = !loading && visible.any { it.selected }
        when {
            query.isNotEmpty() -> {
                binding.tvEmptyMessage.text = "没有找到匹配的应用"
                binding.tvEmptyHint.text = "试试其他应用名称或包名。"
            }
            selectedOnly -> {
                binding.tvEmptyMessage.text = "还没有选择应用"
                binding.tvEmptyHint.text = "切换到「全部」，选择需要处理开屏广告的应用。"
            }
            else -> {
                binding.tvEmptyMessage.text = "暂未找到应用"
                binding.tvEmptyHint.text = "勾选上方选项，查看没有桌面图标的应用。"
            }
        }
    }

    private fun confirmSelectAll() {
        val count = visible.size
        val scoped = if (query.isEmpty() && !selectedOnly) "全部 $count 个应用" else "当前筛选出的 $count 个应用"
        MaterialAlertDialogBuilder(this)
            .setTitle("选择当前列表中的应用？")
            .setMessage(
                "将选择$scoped，读取这些应用的窗口内容，识别并点击广告关闭按钮。\n\n" +
                    "建议只选择确实有开屏广告的应用。未选择的应用不会被处理。"
            )
            .setPositiveButton("选择全部") { _, _ ->
                adapter.setAll(true)
                syncSettings()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("selectedOnly", selectedOnly)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
