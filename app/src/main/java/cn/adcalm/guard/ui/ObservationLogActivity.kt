package cn.adcalm.guard.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import cn.adcalm.guard.core.GuardSettings
import cn.adcalm.guard.data.ObservationLog
import cn.adcalm.guard.data.SnapshotStore
import cn.adcalm.guard.databinding.ActivityObservationLogBinding
import cn.adcalm.guard.service.AdCalmAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private lateinit var snapshots: SnapshotStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refreshJob: Job? = null
    private var recordCount = 0
    private var snapshotCount = 0
    private data class FileSummary(val records: Int, val bytes: Long, val pictures: Int, val hasExports: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityObservationLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CalmUi.prepare(this, binding.root)
        log = ObservationLog(this)
        snapshots = SnapshotStore(this)
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
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val summary = withContext(Dispatchers.IO) {
                FileSummary(log.lineCount(), log.sizeBytes(), snapshots.list().size, log.hasExportedCopies())
            }
            recordCount = summary.records
            snapshotCount = summary.pictures
            binding.tvRecordCount.text = summary.records.toString()
            binding.tvFileSize.text = (summary.bytes / 1024).toString()
            binding.tvDiagnosticState.text = if (settings.debugMode) "已开启" else "已关闭"
            binding.tvDiagnosticSummary.text = "开启后会在本机保存完整节点树和未脱敏截图；摘要导出不含这些内容。现存截图 ${summary.pictures} 张。"
            binding.btnExportLog.text = "导出安全摘要"
            binding.btnExportLog.isEnabled = summary.bytes > 0
            binding.btnClearLog.isEnabled = summary.bytes > 0 || summary.pictures > 0 || summary.hasExports
        }
    }

    private fun exportLog() {
        binding.btnExportLog.isEnabled = false
        scope.launch {
            val dest = File(File(cacheDir, ObservationLog.EXPORT_DIR_NAME), ObservationLog.EXPORT_FILE_NAME)
            val exported = withContext(Dispatchers.IO) {
                log.exportTo(dest)
            }
            refresh()
            if (!exported) { toast("没有可导出的摘要或导出失败，请重试"); return@launch }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this@ObservationLogActivity, "$packageName.fileprovider", dest))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            MaterialAlertDialogBuilder(this@ObservationLogActivity).setTitle("安全摘要已导出")
                .setMessage("摘要保留事件时间、判定和分数，应用以本次导出的代号表示。页面文字、账户信息、完整节点树和截图不在摘要中。分享后，接收应用会获得这份摘要。")
                .setPositiveButton("分享摘要") { _, _ -> startActivity(Intent.createChooser(share, "导出观察摘要")) }
                .setNegativeButton("关闭", null).show()
        }
    }

    private fun confirmClearLog() {
        MaterialAlertDialogBuilder(this).setTitle("清空观察日志？")
            .setMessage("将删除本应用的 $recordCount 条观察记录、$snapshotCount 张诊断截图和本机导出副本，无法恢复。已分享给其他应用的副本不受影响。诊断仍开启时，会继续记录新的内容。")
            .setPositiveButton("清空日志和截图") { _, _ ->
                binding.btnClearLog.isEnabled = false
                scope.launch {
                    val cleared = withContext(Dispatchers.IO) { log.clear() }
                    refresh()
                    toast(if (cleared) "既有日志与诊断截图已清理" else "部分文件未清理，请重试")
                }
            }
            .setNegativeButton("取消", null).show()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
