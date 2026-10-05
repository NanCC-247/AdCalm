package cn.adcalm.guard.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import cn.adcalm.guard.core.GuardSettings
import cn.adcalm.guard.data.DownloadJanitor
import cn.adcalm.guard.data.QuarantinedFile
import cn.adcalm.guard.databinding.ActivityQuarantineBinding
import cn.adcalm.guard.service.AdCalmAccessibilityService
import java.util.Locale

/**
 * 隔离区界面。
 *
 * 广告下载的安装包被移到这里暂存，用户可以先看一眼再决定恢复还是删除。
 * 之所以要有这个界面而不是直接删：误判的代价不对称，必须留后悔的余地。
 */
class QuarantineActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuarantineBinding
    private lateinit var janitor: DownloadJanitor
    private lateinit var adapter: QuarantineAdapter
    private lateinit var settings: GuardSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuarantineBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CalmUi.prepare(this, binding.root)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        val prefs = getSharedPreferences(AdCalmAccessibilityService.PREFS_NAME, Context.MODE_PRIVATE)
        settings = GuardSettings(prefs)
        janitor = DownloadJanitor(this, settings)

        adapter = QuarantineAdapter(
            onRestore = { entry -> confirmRestore(entry) },
            onDelete = { entry -> confirmDelete(entry) },
        )
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = adapter

    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val files = janitor.listQuarantined().sortedByDescending { it.quarantinedAt }
        adapter.submit(files)

        val totalBytes = files.sumOf { it.sizeBytes }
        binding.tvSummary.text = "${files.size} 个暂存文件"
        binding.tvTotalSize.text = "占用空间 ${formatSize(totalBytes)}"
        binding.tvRetention.text = "文件只是被移走，可恢复到原位置。保留 ${settings.quarantineRetentionHours} 小时后会自动删除。"
        binding.emptyState.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        binding.rvFiles.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        binding.tvListLabel.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun confirmRestore(entry: QuarantinedFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle("恢复到原位置？")
            .setMessage("将把「${entry.fileName}」放回：\n\n${entry.originalPath}")
            .setPositiveButton("恢复") { _, _ ->
                val ok = janitor.restore(entry)
                toast(if (ok) "已恢复" else "恢复失败")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(entry: QuarantinedFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle("彻底删除？")
            .setMessage("「${entry.fileName}」将被永久删除，无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                val ok = janitor.deleteNow(entry)
                toast(if (ok) "已删除" else "删除失败")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
