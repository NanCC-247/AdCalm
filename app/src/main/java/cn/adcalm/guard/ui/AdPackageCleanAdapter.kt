package cn.adcalm.guard.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cn.adcalm.guard.core.AdPackageCleaner
import cn.adcalm.guard.databinding.ItemAdPackageBinding

/**
 * 「清理广告安装包」列表。
 *
 * 勾选状态在这里，**默认勾选的是缓存残渣**（[AdPackageCleaner.Kind.CACHE_RESIDUE]）——
 * 缓存按定义就是可丢的副本，删掉没有代价。其余（比如应用市场自己的下载暂存）
 * 只列出来、默认不勾，因为那里可能有正在进行中的安装。
 *
 * 用路径而不是下标做选中键：删除之后会重新扫描，下标会整体错位。
 */
class AdPackageCleanAdapter(
    private val onSelectionChanged: () -> Unit,
    /** 点击整行时回调——看它在哪儿。勾选交给左边的复选框。 */
    private val onShowLocation: (AdPackageCleaner.Scanned) -> Unit,
) : RecyclerView.Adapter<AdPackageCleanAdapter.Holder>() {

    private val items = mutableListOf<AdPackageCleaner.Scanned>()
    private val selected = mutableSetOf<String>()

    fun submit(list: List<AdPackageCleaner.Scanned>) {
        items.clear()
        items += list
        selected.clear()
        selected += list.filter { it.kind == AdPackageCleaner.Kind.CACHE_RESIDUE }
            .map { it.found.path }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun clear() {
        items.clear()
        selected.clear()
        notifyDataSetChanged()
    }

    fun isEmpty(): Boolean = items.isEmpty()

    fun selectedPaths(): List<String> = items.map { it.found.path }.filter { it in selected }

    fun selectedCount(): Int = selectedPaths().size

    fun selectedBytes(): Long =
        items.filter { it.found.path in selected }.sumOf { it.found.sizeBytes }

    /** 是不是全都选上了——用来决定「全选 / 全不选」按钮显示哪个词。 */
    fun allSelected(): Boolean = items.isNotEmpty() && selected.size == items.size

    /** 全选与全不选之间切换。 */
    fun toggleAll() {
        if (allSelected()) {
            selected.clear()
        } else {
            selected.clear()
            selected += items.map { it.found.path }
        }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    private fun toggle(path: String) {
        if (!selected.remove(path)) selected += path
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAdPackageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemAdPackageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AdPackageCleaner.Scanned) {
            val name = AdPackageCleaner.fileNameOf(item.found.path)
            val owner = AdPackageCleaner.ownerPackageOf(item.found.path)
            binding.tvFileName.text = name
            binding.tvMeta.text = "${AdPackageCleaner.readableSize(item.found.sizeBytes)}" +
                if (owner != null) "  ·  所在应用 $owner" else ""

            binding.tvReason.text = when (item.kind) {
                AdPackageCleaner.Kind.CACHE_RESIDUE -> "缓存残渣 · 已勾选"
                AdPackageCleaner.Kind.RENAMED_PACKAGE -> "扩展名不对，但文件头是安装包 · 请自行判断"
                AdPackageCleaner.Kind.ELSEWHERE -> "不在缓存目录 · 请自行判断"
            }
            binding.tvPath.text = item.found.path

            binding.cbSelect.isChecked = item.found.path in selected
            binding.cbSelect.setOnClickListener { toggle(item.found.path) }
            // 整行点了是"看它在哪儿"，不是勾选——勾选交给左边的复选框。
            // 这两个动作分开是有意的：勾了就要删，误触的代价不对称。
            binding.root.setOnClickListener { onShowLocation(item) }
            binding.root.contentDescription =
                "$name，${AdPackageCleaner.readableSize(item.found.sizeBytes)}，${binding.tvReason.text}"
        }
    }

}
