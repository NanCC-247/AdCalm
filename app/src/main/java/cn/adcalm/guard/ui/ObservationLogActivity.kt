package cn.adcalm.guard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import cn.adcalm.guard.core.GuardSettings
import cn.adcalm.guard.data.ObservationLog
import cn.adcalm.guard.databinding.ActivityObservationLogBinding
import cn.adcalm.guard.service.AdCalmAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Existing log operations, with their own focused page and real file statistics. */
class ObservationLogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityObservationLogBinding
    private lateinit var log: ObservationLog
    private lateinit var settings: GuardSettings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityObservationLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CalmUi.prepare(this, binding.root)
        log = ObservationLog(this)
        settings = GuardSettings(getSharedPreferences(AdCalmAccessibilityService.PREFS_NAME, MODE_PRIVATE))
        binding.btnBack.setOnClickListener { finish() }
        binding.btnExportLog.setOnClickListener { exportLog() }
        binding.btnClearLog.setOnClickListener { confirmClearLog() }
        binding.rowDiagnostics.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra(CalmUi.EXTRA_START_TAB, CalmUi.TAB_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    override fun onResume() { super.onResume(); refresh() }
    private fun refresh() {
        binding.tvRecordCount.text = log.lineCount().toString()
        binding.tvFileSize.text = (log.sizeBytes() / 1024).toString()
        binding.tvDiagnosticState.text = if (settings.debugMode) "已开启" else "已关闭"
        binding.tvDiagnosticSummary.text = "需要完整界面结构时，可在设置中开启"
        val hasLog = log.file.exists() && log.sizeBytes() > 0
        binding.btnExportLog.isEnabled = hasLog
        binding.btnClearLog.isEnabled = hasLog
    }

    private fun exportLog() {
        val src = log.file
        if (!src.exists() || src.length() == 0L) { toast("暂无日志可导出"); refresh(); return }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { src.copyTo(File(getExternalFilesDir(null) ?: filesDir, "observer_log.jsonl"), overwrite = true) }
            }
            val dest = result.getOrElse { toast("导出失败，请重试"); return@launch }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this@ObservationLogActivity, "$packageName.fileprovider", dest))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            MaterialAlertDialogBuilder(this@ObservationLogActivity).setTitle("日志已导出")
                .setMessage("可以分享给其他应用，也可在电脑上提取：\n\nadb pull ${dest.absolutePath}")
                .setPositiveButton("分享") { _, _ -> startActivity(Intent.createChooser(share, "导出观察日志")) }
                .setNegativeButton("关闭", null).show()
        }
    }

    private fun confirmClearLog() {
        MaterialAlertDialogBuilder(this).setTitle("清空观察日志？")
            .setMessage("已记录的 ${log.lineCount()} 条日志将被删除，无法恢复。")
            .setPositiveButton("清空") { _, _ -> log.clear(); refresh(); toast("日志已清空") }
            .setNegativeButton("取消", null).show()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
