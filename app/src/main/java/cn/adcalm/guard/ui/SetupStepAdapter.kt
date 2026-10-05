package cn.adcalm.guard.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import cn.adcalm.guard.R
import cn.adcalm.guard.databinding.ItemSetupStepBinding

/** 配置向导里的一步。 */
data class SetupStep(
    val title: String,
    val description: String,
    val granted: Boolean,
    /** 是否推荐开启。非必需的步骤会标注出来。 */
    val required: Boolean,
    /**
     * 按钮文案。为 null 时按状态自动生成「去开启（必需/可选）」。
     * 有些步骤的动作不是"跳设置页"而是"下载安装"，需要自己给文案。
     */
    val actionLabel: String? = null,
    /** 主列表展示简短说明，具体系统操作保留在展开内容里。 */
    val summary: String? = null,
    val open: () -> Unit,
)

class SetupStepAdapter(
    private val onOpen: (SetupStep) -> Unit,
) : RecyclerView.Adapter<SetupStepAdapter.Holder>() {

    private val items = mutableListOf<SetupStep>()
    private val expandedTitles = mutableSetOf<String>()

    fun submit(list: List<SetupStep>) {
        items.clear()
        items += list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSetupStepBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) =
        holder.bind(position + 1, items[position])

    inner class Holder(private val binding: ItemSetupStepBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(index: Int, step: SetupStep) {
            binding.tvStepIndex.text = index.toString()
            binding.tvTitle.text = step.title
            ViewCompat.setAccessibilityHeading(binding.tvTitle, true)
            binding.tvSummary.text = step.summary ?: step.description.substringBefore("\n\n")
            binding.tvDescription.text = step.description

            val context = binding.root.context
            val statusColor: Int
            val statusSurface: Int
            when {
                step.granted -> {
                    binding.tvStatus.text = "已开启"
                    statusColor = R.color.calm_primary_dark
                    statusSurface = R.color.calm_mint
                    binding.btnOpen.text = step.actionLabel ?: "前往设置"
                }
                step.required -> {
                    binding.tvStatus.text = "必需 · 待开启"
                    statusColor = R.color.calm_warning
                    statusSurface = R.color.calm_warning_surface
                    binding.btnOpen.text = step.actionLabel ?: "去开启"
                }
                else -> {
                    binding.tvStatus.text = "可选 · 待开启"
                    statusColor = R.color.calm_text_secondary
                    statusSurface = R.color.calm_mint
                    binding.btnOpen.text = step.actionLabel ?: "去开启"
                }
            }
            binding.tvStatus.setTextColor(ContextCompat.getColor(context, statusColor))
            binding.tvStatus.background = GradientDrawable().apply {
                cornerRadius = 40f * context.resources.displayMetrics.density
                setColor(ContextCompat.getColor(context, statusSurface))
            }
            binding.btnOpen.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, if (step.granted) R.color.calm_mint else R.color.calm_primary_dark)
            )
            binding.btnOpen.setTextColor(
                ContextCompat.getColor(context, if (step.granted) R.color.calm_primary_dark else android.R.color.white)
            )
            binding.btnOpen.contentDescription = "${binding.btnOpen.text}：${step.title}"
            updateDetails(step)
            binding.btnDetails.setOnClickListener {
                if (!expandedTitles.add(step.title)) expandedTitles.remove(step.title)
                updateDetails(step)
            }

            binding.btnOpen.setOnClickListener { onOpen(step) }
        }

        private fun updateDetails(step: SetupStep) {
            val expanded = step.title in expandedTitles
            binding.tvDescription.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.btnDetails.text = if (expanded) "收起说明" else "查看说明"
            binding.btnDetails.contentDescription = "${binding.btnDetails.text}：${step.title}"
            ViewCompat.setStateDescription(binding.btnDetails, if (expanded) "已展开" else "已收起")
        }
    }
}
