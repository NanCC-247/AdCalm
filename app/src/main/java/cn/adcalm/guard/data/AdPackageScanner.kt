package cn.adcalm.guard.data

import android.util.Log
import cn.adcalm.guard.core.AdPackageCleaner
import cn.adcalm.guard.core.ShellCommands
import cn.adcalm.guard.shizuku.ShizukuShell

/**
 * 扫一遍别的应用的数据目录，把安装包列出来给用户判断。
 *
 * 只有用户点了按钮才会跑，跑完由用户勾选——**程序不自动删任何东西**。
 * 判据是"位置"（在不在缓存目录里），见 [AdPackageCleaner.scan]。
 *
 * 走 shell：`Android/data/<别的应用>/` 普通应用连读都读不到。
 * 所以**没有 Shizuku 时这个功能整体不可用**，界面要如实说明，不要假装扫了个空。
 */
object AdPackageScanner {

    /** 扫描结果。 */
    sealed interface Result {
        /** 没有 Shizuku，扫不了。界面要说明原因，别显示成"没找到"。 */
        data object NoShizuku : Result

        /** 扫描失败（超时、命令出错）。 */
        data class Failed(val reason: String) : Result

        /** 扫描完成。[items] 可能为空，那才是真的"没找到"。 */
        data class Done(val items: List<AdPackageCleaner.Scanned>) : Result
    }

    /**
     * 扫描全部 [AdPackageCleaner.SCAN_ROOTS]。
     *
     * **必须在 IO 线程调用**：要跑几条可能几秒的 shell 命令，还要遍历整棵目录树。
     * 实测整轮要二十多秒，所以用一个 [onProgress] 回调把"正在扫哪个根"报出去——
     * 期间界面上得有点动静，否则用户会以为卡死了。
     *
     * @param onProgress 在 IO 线程上被调用，**回调实现要自己切回主线程**
     */
    fun scan(onProgress: (String) -> Unit = {}): Result {
        if (!ShizukuShell.isAuthorized()) return Result.NoShizuku

        val items = mutableListOf<AdPackageCleaner.Scanned>()
        var anySucceeded = false

        val roots = AdPackageCleaner.SCAN_ROOTS + AdPackageCleaner.EXTRA_SCAN_ROOTS
        for ((index, root) in roots.withIndex()) {
            onProgress("正在扫描 ${root.substringAfterLast('/')}（${index + 1}/${roots.size}）")
            // 每个根跑两趟：先按扩展名收，再按**文件头**收"改了名的"（见 ShellCommands）。
            // 第二趟只读每个文件的前 256KB，所以两趟加起来仍然是一次能接受的等待。
            val passes = listOf(
                ShellCommands.listPackages(root) to AdPackageCleaner::scan,
                ShellCommands.listRenamedPackages(root) to AdPackageCleaner::scanRenamed,
            )
            for ((command, parse) in passes) {
                if (command == null) continue
                when (val result = ShizukuShell.exec(command, SCAN_TIMEOUT_MS)) {
                    is ShizukuShell.Result.Done -> {
                        anySucceeded = true
                        items += parse(result.output.lineSequence())
                    }
                    is ShizukuShell.Result.Failed -> Log.w(TAG, "扫描 $root 失败：${result.reason}")
                    ShizukuShell.Result.Unavailable -> return Result.NoShizuku
                }
            }
        }

        if (!anySucceeded) return Result.Failed("扫描命令没能执行")
        // 两个根都扫完再统一排序，否则后一个根的结果会整段排在前一个后面
        return Result.Done(items.sortedWith(compareBy({ it.kind.ordinal }, { -it.found.sizeBytes })))
    }

    /**
     * 删除选中的文件。返回成功删除的数量。
     *
     * **必须在 IO 线程调用。** 逐条 `rm`：这里没有事务可言，
     * 一个删不掉不该影响其余的，所以计数而不是抛异常。
     *
     * 走的是 `rm` 而不是移进隔离区——文件名旁边就是"用户审核后删除"这个动作，
     * 人已经看过一遍了，再要求他 24 小时后来清一次没有意义。
     */
    fun delete(paths: List<String>): Int {
        if (!ShizukuShell.isAuthorized()) return 0
        var removed = 0
        for (path in paths) {
            val command = ShellCommands.deleteFile(path)
            if (command == null) {
                Log.w(TAG, "路径不合法，拒绝删除：$path")
                continue
            }
            val result = ShizukuShell.exec(command)
            if (result is ShizukuShell.Result.Done && result.exitCode == 0) removed++
            else Log.w(TAG, "删除失败：$path")
        }
        return removed
    }

    private const val TAG = "AdCalm"

    /**
     * 手动扫描的超时。
     *
     * 比自动清理那轮更长：用户在等结果，宁可多等一会儿也不要半路超时给个残缺列表。
     */
    private const val SCAN_TIMEOUT_MS = 45_000L
}
