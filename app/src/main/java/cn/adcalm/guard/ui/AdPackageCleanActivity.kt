package cn.adcalm.guard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import cn.adcalm.guard.R
import cn.adcalm.guard.core.AdPackageCleaner
import cn.adcalm.guard.data.AdPackageScanner
import cn.adcalm.guard.databinding.ActivityAdPackageCleanBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「清理广告安装包」。
 *
 * 点进来就先扫一遍，然后**把决定权交给用户**：默认勾选缓存目录里的残渣，
 * 其余列出来但不勾，等他看过再删。
 *
 * 和 [cn.adcalm.guard.data.DownloadJanitor] 那套的分工：那个盯的是标准下载目录
 * （`Download` / `Browser` / `download`），而且只"移进隔离区"；这一页管的是
 * **别的应用私有目录**里的安装包，那里 `FileObserver` 根本挂不上去。
 *
 * 判据是"位置"——在不在缓存目录里，理由见 [AdPackageCleaner.Kind]。
 * **程序不做任何自动删除**：装了什么、删不删，都在这一页由用户看着办。
 *
 * 删除走的是真删（`rm`），不是移进隔离区：这里每一行旁边就是用户自己按下的勾，
 * 再看一遍已经是人工审核过了，再要求他 24 小时后来清一次没有意义。
 */
class AdPackageCleanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdPackageCleanBinding
    private lateinit var adapter: AdPackageCleanAdapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdPackageCleanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CalmUi.prepare(this, binding.root)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        adapter = AdPackageCleanAdapter(
            onSelectionChanged = { renderSelection() },
            onShowLocation = { item -> showLocation(item) },
        )
        binding.rvPackages.layoutManager = LinearLayoutManager(this)
        binding.rvPackages.adapter = adapter

        binding.btnToggleAll.setOnClickListener { adapter.toggleAll() }
        binding.btnDelete.setOnClickListener { confirmDelete() }

        startScan()
    }

    private fun startScan() {
        binding.tvSummary.text = "正在扫描"
        binding.tvTotalSize.text = "扫描别的应用的数据目录，可能需要几秒"
        binding.scanProgress.visibility = View.VISIBLE
        binding.tvListLabel.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.rvPackages.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // 整轮实测二十多秒，期间要让用户看见在动，否则会以为卡死了。
                // 回调在 IO 线程上，切回主线程再碰界面。
                AdPackageScanner.scan { message -> runOnUiThread { binding.tvTotalSize.text = message } }
            }
            binding.scanProgress.visibility = View.GONE

            when (result) {
                is AdPackageScanner.Result.NoShizuku -> showBlocked(
                    "需要 Shizuku",
                    "别的应用的私有目录只有 shell 能读，所以这个功能必须有 Shizuku。\n\n" +
                        "在「设置 → Shizuku 配置」里授权后回来重试。",
                )

                is AdPackageScanner.Result.Failed -> showBlocked(
                    "扫描失败",
                    "${result.reason}\n\n返回上一页再进来会重新扫一次。",
                )

                is AdPackageScanner.Result.Done -> renderScan(result.items)
            }
        }
    }

    private fun renderScan(items: List<AdPackageCleaner.Scanned>) {
        adapter.submit(items)
        if (items.isEmpty()) {
            binding.tvSummary.text = "没有找到安装包"
            binding.tvTotalSize.text = ""
            binding.emptyState.visibility = View.VISIBLE
            // 标题和提示都要重写：上一轮可能是"需要 Shizuku"之类的阻断态留下的文案
            binding.tvEmpty.text = "没有找到安装包"
            binding.tvEmptyHint.text = "别的应用的数据目录里没有安装包残留。\n广告下过东西之后回来再看。"
        } else {
            binding.tvListLabel.visibility = View.VISIBLE
            binding.rvPackages.visibility = View.VISIBLE
        }
        renderSelection()
    }

    /** 扫描进行不下去时（没有 Shizuku / 失败）的呈现——不要伪装成"没找到"。 */
    private fun showBlocked(title: String, message: String) {
        binding.tvSummary.text = title
        binding.tvTotalSize.text = ""
        binding.emptyState.visibility = View.VISIBLE
        binding.tvEmpty.text = title
        binding.tvEmptyHint.text = message
        binding.bottomBar.visibility = View.GONE
    }

    /** 勾选变化后刷新底部那一条和汇总数字。 */
    private fun renderSelection() {
        if (adapter.isEmpty()) return

        val count = adapter.selectedCount()
        val bytes = adapter.selectedBytes()
        binding.tvSummary.text = "找到 ${adapter.itemCount} 个安装包"
        binding.tvTotalSize.text = if (count == 0) {
            "已勾选 0 个"
        } else {
            "已勾选 $count 个 · ${AdPackageCleaner.readableSize(bytes)}"
        }

        binding.bottomBar.visibility = View.VISIBLE
        binding.btnToggleAll.text = if (adapter.allSelected()) "全不选" else "全选"
        binding.btnDelete.isEnabled = count > 0
        // 时长控制在按钮能一行放下——带括号和 MB 的话会折成两行（真机上见过）
        binding.btnDelete.text = if (count == 0) "删除" else "删除 $count 个"
    }

    /**
     * 「看它在哪儿」——点整行时弹的。
     *
     * **没法真的跳到那个文件夹**：`/Android/data/<别的应用>/` 在 Android 11+ 上是
     * 禁止浏览的，文件管理器根本打不开。所以给两个能落地的去处：
     * 复制完整路径（贴到终端里就能定位），以及打开**拥有它的那个应用**的应用信息页——
     * 那才是用户真正想看的"这是谁下的东西"。
     */
    private fun showLocation(item: AdPackageCleaner.Scanned) {
        val path = item.found.path
        val owner = AdPackageCleaner.ownerPackageOf(path)

        val content = layoutInflater.inflate(R.layout.dialog_package_location, null)
        content.findViewById<TextView>(R.id.tvPath).text = path
        content.findViewById<TextView>(R.id.tvNote).text = if (owner != null) {
            "这个位置在别的应用的私有目录里，Android 不允许文件管理器打开它。" +
                "要「跳过去」，只能看拥有它的那个应用。"
        } else {
            "这个位置在别的应用的私有目录里，Android 不允许文件管理器打开它。"
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(AdPackageCleaner.fileNameOf(path))
            .setView(content)
            .setNegativeButton("关闭", null)
            .create()

        content.findViewById<View>(R.id.btnCopy).setOnClickListener { copyPath(path) }

        val openButton = content.findViewById<View>(R.id.btnOpenApp)
        if (owner != null) {
            openButton.setOnClickListener {
                openAppInfo(owner)
                dialog.dismiss()
            }
        } else {
            // 取不到包名就别摆一个点了没反应的按钮
            openButton.visibility = View.GONE
        }

        dialog.show()
    }

    private fun copyPath(path: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("安装包路径", path))
        toast("路径已复制")
    }

    private fun openAppInfo(pkg: String) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", pkg, null),
        )
        runCatching { startActivity(intent) }.onFailure { toast("打不开应用信息页") }
    }

    private fun confirmDelete() {
        val paths = adapter.selectedPaths()
        if (paths.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle("删除这 ${paths.size} 个安装包？")
            .setMessage(
                // 别用 ** 加粗：TextView 不解析 Markdown，会原样显示出来
                "共 ${AdPackageCleaner.readableSize(adapter.selectedBytes())}，永久删除、无法恢复。\n\n" +
                    "它们都在别的应用的目录里，删掉不影响那些应用本身。",
            )
            .setPositiveButton("删除") { _, _ -> runDelete(paths) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runDelete(paths: List<String>) {
        binding.btnDelete.isEnabled = false
        binding.btnDelete.text = "正在删除"
        scope.launch {
            val removed = withContext(Dispatchers.IO) { AdPackageScanner.delete(paths) }
            toast(
                when {
                    removed == paths.size -> "已删除 $removed 个安装包"
                    removed == 0 -> "删除失败，请检查 Shizuku 是否可用"
                    else -> "删除了 $removed 个，另有 ${paths.size - removed} 个失败"
                },
            )
            // 重新扫一遍：删成功的不该再出现，失败的会留在列表里让用户看见
            startScan()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
