package cn.adcalm.guard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cn.adcalm.guard.core.AppNeutralizer
import cn.adcalm.guard.core.ScanFreshness
import cn.adcalm.guard.core.SelfCheckReport
import cn.adcalm.guard.core.SelfCheckReport.Check
import cn.adcalm.guard.core.SelfCheckReport.Outcome
import cn.adcalm.guard.databinding.ActivitySelfCheckBinding
import cn.adcalm.guard.ocr.OcrEngine
import cn.adcalm.guard.ocr.ScreenshotCapturer
import cn.adcalm.guard.service.AdCalmAccessibilityService
import cn.adcalm.guard.shizuku.ShizukuShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「能力自检」。
 *
 * ## 为什么值得单开一页
 *
 * 这个项目的真机结论全部来自同一台设备，而"换台机器再验一遍"一直没做，不是因为
 * 它不重要，是因为**成本卡在"要手工逐项试"**：无障碍事件、截图通道、手势注入、
 * 强停的两条路、OCR 引擎——每一项在别的 ROM 上都可能悄悄不通，
 * 而**不通的样子全都是"界面一切正常"**。
 *
 * 这一页把"逐项试一遍"压成一个按钮：一次跑完，每项给出结论**加原始证据**，
 * 整份能复制走。换机、借机、或者只是怀疑"今天怎么不灵了"，一分钟就能出结论。
 *
 * ## 两条自己定下的规矩
 *
 * **一、只有一项会动界面，而且说了。** 其余检查全部只读；唯一会动手的
 * 「强停（应用信息页）」必须把系统那一页打开才知道按钮在不在，跑完立刻回来。
 *
 * **二、拿不到证据一律记「未知」，绝不因为"没报错"就写「通过」。** 这个项目最怕的失败
 * 是**虚报能力**——一条从不触发的规则、一条从没通过的 Shizuku 通道，
 * 都曾经以"一切正常"的样子活了很久。
 */
class SelfCheckActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySelfCheckBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val checks = mutableListOf<Check>()
    private var running: Job? = null

    /** 手势自检期间为 true：那一下真实点击落到「复制报告」上时只记命中，不真的去复制。 */
    private var awaitingGestureProbe = false
    private var gestureProbeHit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelfCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)
        CalmUi.prepare(this, binding.root)

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnRerun.setOnClickListener { runChecks() }
        binding.btnCopyReport.setOnClickListener {
            // 手势自检会往这个按钮的屏幕坐标注入一次真实点击。命中了就只记一笔——
            // 那一次点击本身就是检查的证据，不该顺带把半截报告复制进剪贴板。
            if (awaitingGestureProbe) gestureProbeHit = true else copyReport()
        }
        runChecks()
    }

    override fun onDestroy() {
        running?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ---- 跑检查 ----

    private fun runChecks() {
        running?.cancel()
        checks.clear()
        renderReport()
        running = scope.launch {
            // 先让布局落定：手势自检要读按钮的屏幕坐标，没上屏就测不了。
            delay(LAYOUT_SETTLE_MS)
            add(runCatching { checkAccessibility() })
            add(runCatching { checkReadTree() })
            add(runCatching { checkScreenshot() })
            add(runCatching { checkOcr() })
            add(runCatching { checkGestureInjection() })
            add(runCatching { checkForceStopShizuku() })
            add(runCatching { checkForceStopUi() })
        }
    }

    /** 一项检查自己崩了也要留下一条结果——静默少一项，比报错更难查。 */
    private fun add(result: Result<Check>) {
        checks += result.getOrElse { e ->
            Check("检查本身出错", Outcome.UNKNOWN, "${e.javaClass.simpleName}：${e.message.orEmpty()}")
        }
        renderReport()
    }

    private fun renderReport() {
        binding.tvReport.text = SelfCheckReport.render(headerLines(), checks)
    }

    // ---- 各项检查 ----

    private fun checkAccessibility(): Check {
        val service = AdCalmAccessibilityService.instance
            ?: return Check(
                "无障碍服务",
                Outcome.FAIL,
                "服务实例不在——开关看着是开的，实际一次都不会触发",
            )
        // 光有实例还不够：节拍器可能压根没在跑（2026-10-05 真出现过）。
        // 「最近一次扫描」是现成的证据，直接借过来。
        val scan = ScanFreshness.describe(service.msSinceLastScan(), serviceUp = true)
        return Check("无障碍服务", Outcome.PASS, "服务实例在；$scan")
    }

    private fun checkReadTree(): Check {
        val service = AdCalmAccessibilityService.instance
            ?: return Check("读取前台窗口", Outcome.UNKNOWN, "服务没在跑，测不了")
        val texts = service.probeVisibleTexts()
        if (texts.isEmpty()) {
            return Check("读取前台窗口", Outcome.FAIL, "读到空——无障碍树取不到根节点")
        }
        // 当前前台就是本页，所以这里应该能看到本页自己的文案；看得到就说明读得到。
        return Check("读取前台窗口", Outcome.PASS, "读到 ${texts.size} 条文案，首条「${texts.first().take(24)}」")
    }

    private suspend fun checkScreenshot(): Check {
        if (!ScreenshotCapturer.isSupported) {
            return Check("截图通道", Outcome.UNKNOWN, "系统低于 Android 11，本机没有 takeScreenshot")
        }
        val service = AdCalmAccessibilityService.instance
            ?: return Check("截图通道", Outcome.UNKNOWN, "服务没在跑，测不了")
        val start = SystemClock.elapsedRealtime()
        val bitmap = ScreenshotCapturer.capture(service)
        val cost = SystemClock.elapsedRealtime() - start
        if (bitmap == null) {
            return Check("截图通道", Outcome.FAIL, "takeScreenshot 返回失败（耗时 ${cost}ms）")
        }
        val size = "${bitmap.width}x${bitmap.height}"
        bitmap.recycle()
        return Check("截图通道", Outcome.PASS, "$size，耗时 ${cost}ms")
    }

    private suspend fun checkOcr(): Check {
        // 造一张写着「跳过」的图，看离线模型认不认得。这是**端到端**的——
        // "引擎对象建起来了"说明不了什么：模型没打进包时，对象照样建得起来。
        val bitmap = ocrProbeBitmap()
        val engine = OcrEngine()
        return try {
            val start = SystemClock.elapsedRealtime()
            val blocks = engine.recognize(bitmap)
            val cost = SystemClock.elapsedRealtime() - start
            if (blocks.any { it.text.contains(PROBE_WORD) }) {
                Check("OCR 引擎", Outcome.PASS, "自造的「$PROBE_WORD」认出来了，耗时 ${cost}ms")
            } else {
                val got = blocks.joinToString(" | ") { it.text }.take(80)
                Check(
                    "OCR 引擎",
                    Outcome.FAIL,
                    "自造的「$PROBE_WORD」没认出来（识别到 ${blocks.size} 块：$got）",
                )
            }
        } finally {
            bitmap.recycle()
            // **刻意不调用 engine.close()。**
            //
            // ML Kit 的 `TextRecognition.getClient()` 返回的是**共享单例**，`close()` 关掉的是
            // 那个单例本身——**无障碍服务里那个引擎会被一起关掉**。
            // 2026-10-05 在真机上踩到过：跑完一次自检之后，服务的 OCR 路径从此一次都认不出来，
            // 日志里只剩 `NO_CANDIDATE`（广告点不掉了），而界面上毫无异常。
            // 自检是诊断工具，**不该有副作用**——尤其不该把被诊断的东西弄坏。
            // 单例的生命周期交给 ML Kit 自己管，这里不碰。
        }
    }

    private suspend fun checkGestureInjection(): Check {
        val service = AdCalmAccessibilityService.instance
            ?: return Check("手势注入", Outcome.UNKNOWN, "服务没在跑，测不了")
        val view = binding.btnCopyReport
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        if (loc[0] == 0 && loc[1] == 0) {
            return Check("手势注入", Outcome.UNKNOWN, "按钮还没上屏，测不了")
        }
        val x = loc[0] + view.width / 2
        val y = loc[1] + view.height / 2

        awaitingGestureProbe = true
        gestureProbeHit = false
        val dispatched = service.probeTapAt(x, y)

        // 等界面回应。**这一步才是检查的重点**：dispatchGesture 说 true 不等于真的点下去了，
        // 2026-10-05 就栽在这上面（零长度路径：返回 true、日志一切正常、点击根本没发生）。
        val deadline = SystemClock.elapsedRealtime() + GESTURE_PROBE_TIMEOUT_MS
        while (!gestureProbeHit && SystemClock.elapsedRealtime() < deadline) delay(50)
        awaitingGestureProbe = false

        return when {
            gestureProbeHit -> Check("手势注入", Outcome.PASS, "在 ($x,$y) 注入点击，界面响应了")
            dispatched -> Check(
                "手势注入",
                Outcome.FAIL,
                "dispatchGesture 返回 true，但 ${GESTURE_PROBE_TIMEOUT_MS}ms 内界面没有任何反应。" +
                    "这是最坏的一种：日志会写「已点击」，而其实什么都没点下去",
            )
            else -> Check("手势注入", Outcome.FAIL, "dispatchGesture 直接返回了 false")
        }
    }

    private fun checkForceStopShizuku(): Check {
        val status = ShizukuShell.status()
        if (status != ShizukuShell.Status.Authorized) {
            return Check(
                "强停（Shizuku）",
                Outcome.UNKNOWN,
                "Shizuku 状态：$status。这条快路不通，会走下面那条应用信息页的降级路径",
            )
        }
        val probe = ShizukuShell.probe()
        return if (probe.contains("uid=2000")) {
            Check("强停（Shizuku）", Outcome.PASS, "自检输出：${probe.trim().lines().first()}")
        } else {
            Check("强停（Shizuku）", Outcome.FAIL, "显示已授权、命令却跑不通：${probe.trim().take(80)}")
        }
    }

    /**
     * 强停的**降级路径**：没有 Shizuku 时要靠 UI 自动化——打开应用信息页，再点「强行停止」。
     *
     * 这条路各 ROM 差别极大：那一页在哪个包里、那个按钮叫什么，只有真机看了才知道。
     * 所以这里真的把那一页打开、读一遍、再回来。**这是全套检查里唯一会动界面的一项。**
     */
    private suspend fun checkForceStopUi(): Check {
        val service = AdCalmAccessibilityService.instance
            ?: return Check("强停（应用信息页）", Outcome.UNKNOWN, "服务没在跑，测不了")

        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(intent) }.isFailure) {
            return Check("强停（应用信息页）", Outcome.UNKNOWN, "打不开系统的应用信息页")
        }

        var found: String? = null
        val deadline = SystemClock.elapsedRealtime() + FORCE_STOP_PROBE_TIMEOUT_MS
        while (found == null && SystemClock.elapsedRealtime() < deadline) {
            delay(200)
            found = service.probeVisibleTexts().firstOrNull { text ->
                AppNeutralizer.FORCE_STOP_TEXTS.any { text.contains(it) }
            }
        }

        // 无论结果如何都要回来——自检把用户丢在系统设置里是不行的。
        startActivity(
            Intent(this, SelfCheckActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            ),
        )
        delay(RETURN_SETTLE_MS)

        return if (found != null) {
            Check("强停（应用信息页）", Outcome.PASS, "在这一页上找到了「$found」")
        } else {
            Check(
                "强停（应用信息页）",
                Outcome.FAIL,
                "应用信息页打开了，但 ${FORCE_STOP_PROBE_TIMEOUT_MS}ms 内没找到强停按钮。" +
                    "找过：${AppNeutralizer.FORCE_STOP_TEXTS.joinToString("/")}",
            )
        }
    }

    // ---- 报告 ----

    private fun headerLines(): List<String> = listOf(
        "AdCalm 能力自检",
        // 构建号保留毫秒形式（和日志里那行"构建=0.1.0@…"对得上，排查时要能互相印证），
        // 后面再补一个人看得懂的时间。
        "构建 ${versionName()}@${lastUpdateTime()}（${formatTime(lastUpdateTime())}）",
        "设备 ${deviceName()} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "时间 ${formatTime(System.currentTimeMillis())}",
    )

    /**
     * 厂商 + 型号。
     *
     * 很多 ROM 的 `MODEL` 里**已经含厂商名**，直接拼会写成"厂商 厂商 型号"这种重复——
     * 报告是给人看的，这种重复只会让人怀疑别的字段也没校对过。
     */
    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.trim()
        val model = Build.MODEL.trim()
        return when {
            maker.isEmpty() -> model
            model.startsWith(maker, ignoreCase = true) -> model
            else -> "$maker $model"
        }
    }

    private fun formatTime(ms: Long): String =
        if (ms <= 0) "未知" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    private fun lastUpdateTime(): Long =
        runCatching { packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)

    private fun copyReport() {
        val text = SelfCheckReport.render(headerLines(), checks)
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("AdCalm 能力自检", text))
        Toast.makeText(this, "报告已复制", Toast.LENGTH_SHORT).show()
    }

    /** 造一张写着「跳过」的位图，给 OCR 做端到端探针。 */
    private fun ocrProbeBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(OCR_PROBE_W, OCR_PROBE_H, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 84f
            textAlign = Paint.Align.CENTER
        }
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(PROBE_WORD, OCR_PROBE_W / 2f, OCR_PROBE_H * 0.68f, paint)
        }
        return bitmap
    }

    private companion object {
        /** 自检用哪个词：它在项目的强关闭词表里，认得它等于认得关闭按钮。 */
        const val PROBE_WORD = "跳过"

        const val OCR_PROBE_W = 360
        const val OCR_PROBE_H = 180

        /** 手势注入后等界面回应的上限。真机一次点击的端到端延迟是 200~400ms，留足余量。 */
        const val GESTURE_PROBE_TIMEOUT_MS = 1_200L

        /** 应用信息页的加载时间各 ROM 差别很大，给宽一点。 */
        const val FORCE_STOP_PROBE_TIMEOUT_MS = 3_000L

        /** 等布局落定，以及从系统页面回来之后等本页恢复。 */
        const val LAYOUT_SETTLE_MS = 300L
        const val RETURN_SETTLE_MS = 400L
    }
}
