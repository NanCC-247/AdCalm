package cn.adcalm.guard.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import cn.adcalm.guard.databinding.ItemAppBinding
import cn.adcalm.guard.R
import java.text.Collator
import java.util.Locale

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    var selected: Boolean,
)

/**
 * 已安装应用列表。勾选状态即 [cn.adcalm.guard.core.GuardSettings.targetedPackages]，
 * 没勾的应用本工具完全不介入。
 *
 * 适配器只持有**当前可见**的那部分条目；完整的列表由 Activity 保存。
 * 两边共享同一批 [AppEntry] 实例，所以过滤搜索时勾选状态不会丢。
 */
class AppListAdapter(
    private val onToggle: (AppEntry, Boolean) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.Holder>() {

    private val items = mutableListOf<AppEntry>()

    fun submit(list: List<AppEntry>) {
        items.clear()
        items += list
        notifyDataSetChanged()
    }

    /** 对当前可见的条目整体置位。返回受影响的条数。 */
    fun setAll(value: Boolean): Int {
        items.forEach { it.selected = value }
        notifyDataSetChanged()
        return items.size
    }

    /** 对当前可见的条目反选。 */
    fun invert() {
        items.forEach { it.selected = !it.selected }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            ViewCompat.setAccessibilityDelegate(binding.root, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = CheckBox::class.java.name
                    info.isCheckable = true
                    info.isChecked = binding.cbSelected.isChecked
                }
            })
        }

        fun bind(entry: AppEntry) {
            if (entry.icon != null) {
                binding.ivIcon.setImageDrawable(entry.icon)
            } else {
                binding.ivIcon.setImageResource(R.drawable.ic_calm_apps)
            }
            binding.tvAppName.text = entry.label
            binding.tvPackageName.text = entry.packageName
            binding.cbSelected.isChecked = entry.selected
            updateAccessibility(entry)

            binding.root.setOnClickListener {
                // 必须同时更新 entry.selected —— 设置里的选中集合是从这些实例推导的，
                // 只改复选框显示会让两处状态漂移。
                entry.selected = !entry.selected
                binding.cbSelected.isChecked = entry.selected
                updateAccessibility(entry)
                onToggle(entry, entry.selected)
            }
        }

        private fun updateAccessibility(entry: AppEntry) {
            binding.root.contentDescription = "${entry.label}，${entry.packageName}"
            ViewCompat.setStateDescription(binding.root, if (entry.selected) "已选择" else "未选择")
            binding.root.isSelected = entry.selected
            binding.tvAppName.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            binding.tvPackageName.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    companion object {
        /**
         * 枚举应用。
         *
         * @param includeHidden true 时额外纳入没有桌面图标的已安装应用（非系统包）。
         */
        fun loadInstalledApps(
            context: Context,
            selectedPackages: Set<String>,
            includeHidden: Boolean = false,
        ): List<AppEntry> {
            val pm = context.packageManager
            val result = LinkedHashMap<String, AppEntry>()

            // 来源一：有桌面图标的应用
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

            // ⚠ 这里绝对不能用 MATCH_DEFAULT_ONLY。
            // 绝大多数应用的启动 Activity，intent-filter 里只有 MAIN + LAUNCHER，
            // 并不声明 CATEGORY_DEFAULT；加上这个 flag 会把它们全部过滤掉，
            // 结果列表里只剩寥寥几个应用（实测只显示 20 个左右）。
            val resolved = pm.queryIntentActivities(launcherIntent, 0)
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == context.packageName) continue
                result[pkg] = buildEntry(pm, pkg, selectedPackages)
            }

            // 来源二：可选的兜底——所有非系统的已安装应用，覆盖那些没有桌面图标的
            if (includeHidden) {
                val installed = runCatching {
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                }.getOrDefault(emptyList())

                for (app in installed) {
                    val pkg = app.packageName ?: continue
                    if (pkg == context.packageName) continue
                    if (pkg in result) continue
                    if (isSystemApp(app)) continue
                    result[pkg] = buildEntry(pm, pkg, selectedPackages)
                }
            }

            val collator = Collator.getInstance(Locale.CHINA)
            val sorted = result.values.sortedWith { a, b -> collator.compare(a.label, b.label) }

            // 把数量打到日志里：列表数量不对时这是第一手线索
            android.util.Log.i(
                "AdCalm",
                "应用列表：桌面图标 ${resolved.size} 项 → 去重后 ${sorted.size} 个" +
                    if (includeHidden) "（含无图标应用）" else ""
            )
            return sorted
        }

        private fun isSystemApp(info: ApplicationInfo): Boolean {
            val mask = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            return (info.flags and mask) != 0
        }

        /**
         * 只要「包名 → 应用名」，**不碰图标**。
         *
         * 「广告软件更新」的推荐只需要这两样，而 [loadInstalledApps] 会为每个应用取图标——
         * 那是整条链路里最慢的一步：图标是跨进程从 PackageManager 拿 drawable，
         * 一百多个应用要好几秒，而结果根本用不上。
         */
        fun loadInstalledNames(context: Context, includeHidden: Boolean): Map<String, String> {
            val pm = context.packageManager
            val result = LinkedHashMap<String, String>()

            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (info in pm.queryIntentActivities(launcherIntent, 0)) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == context.packageName) continue
                result[pkg] = labelOf(pm, pkg)
            }

            if (includeHidden) {
                val installed = runCatching {
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                }.getOrDefault(emptyList())
                for (app in installed) {
                    val pkg = app.packageName ?: continue
                    if (pkg == context.packageName || pkg in result) continue
                    if (isSystemApp(app)) continue
                    result[pkg] = labelOf(pm, pkg)
                }
            }
            return result
        }

        private fun labelOf(pm: PackageManager, pkg: String): String = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        private fun buildEntry(
            pm: PackageManager,
            pkg: String,
            selectedPackages: Set<String>,
        ): AppEntry {
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            return AppEntry(pkg, label, icon, pkg in selectedPackages)
        }
    }
}
