package cn.adcalm.guard.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** OCR 返回的一个文本块，坐标是位图坐标系。 */
data class RawOcrBlock(val text: String, val rect: Rect)

/**
 * 离线中文文字识别。
 *
 * 用 ML Kit 的 bundled 中文模型——模型直接打包进 APK，装好即可用。
 * 另一条路是 unbundled 版本，模型运行时从 Google Play 服务下载，
 * 侧载场景下基本走不通。
 *
 * 识别全程在本机完成，不联网。
 */
class OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * @return 识别到的文本块；失败返回空列表（调用方按"没找到"处理）
     */
    suspend fun recognize(bitmap: Bitmap): List<RawOcrBlock> {
        return suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val blocks = ArrayList<RawOcrBlock>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val text = line.text.trim()
                            if (text.isEmpty()) continue
                            val box = line.boundingBox ?: continue
                            blocks += RawOcrBlock(text, box)
                        }
                    }
                    cont.resume(blocks)
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "OCR 识别失败", error)
                    cont.resume(emptyList())
                }
        }
    }

    /**
     * 关掉识别器。
     *
     * ⚠ **`TextRecognition.getClient()` 返回的是共享单例**，所以关掉一个实例等于关掉
     * 进程里**所有**实例——包括无障碍服务正在用的那个。2026-10-05 真机踩到过：
     * 诊断页 new 了一个引擎、用完 close()，服务那条 OCR 路径从此彻底不工作，
     * 而日志里只看到"没有候选"。
     *
     * 所以：**只有确定进程里不再需要 OCR 时才调它**（例如服务销毁）。
     * 想临时跑一次识别，用完别关——让 ML Kit 自己管那个单例。
     */
    fun close() {
        runCatching { recognizer.close() }
    }

    private companion object {
        const val TAG = "AdCalm"
    }
}
