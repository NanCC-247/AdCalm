package cn.adcalm.guard.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cn.adcalm.guard.data.QuarantinedFile
import cn.adcalm.guard.databinding.ItemQuarantineBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QuarantineAdapter(
    private val onRestore: (QuarantinedFile) -> Unit,
    private val onDelete: (QuarantinedFile) -> Unit,
) : RecyclerView.Adapter<QuarantineAdapter.Holder>() {

    private val items = mutableListOf<QuarantinedFile>()

    fun submit(list: List<QuarantinedFile>) {
        items.clear()
        items += list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemQuarantineBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemQuarantineBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: QuarantinedFile) {
            binding.tvFileName.text = entry.fileName
            binding.tvMeta.text = buildString {
                append(formatSize(entry.sizeBytes))
                append("  ·  ")
                append(TIME_FORMAT.format(Date(entry.quarantinedAt)))
                append(" 隔离")
            }
            binding.tvOriginalPath.text = "原位置：${entry.originalPath}"
            binding.tvOriginalPath.contentDescription = "原位置：${entry.originalPath}"
            binding.btnRestore.contentDescription = "恢复文件：${entry.fileName}"
            binding.btnDelete.contentDescription = "彻底删除：${entry.fileName}"
            binding.btnRestore.setOnClickListener { onRestore(entry) }
            binding.btnDelete.setOnClickListener { onDelete(entry) }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }
}
