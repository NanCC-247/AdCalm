package cn.adcalm.guard.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 无障碍服务截屏，API 30 起可用。
 *
 * `takeScreenshot` 返回的是 HardwareBuffer 包装的位图，不能直接逐像素读取，
 * 必须拷一份到 ARGB_8888 软件位图。硬件缓冲无论成败都要关掉——
 * 它不受 GC 管理，泄漏几次就会拿不到新的截图。
 */
object ScreenshotCapturer {

    /** 截屏回调用的执行器。每次调用新建一个线程池太浪费，共用一条。 */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "adcalm-screenshot").apply { isDaemon = true }
    }

    /** 低于这个版本没有 takeScreenshot API。 */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun capture(service: AccessibilityService): Bitmap? =
        suspendCancellableCoroutine { cont ->
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {

                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val bitmap = try {
                                Bitmap.wrapHardwareBuffer(
                                    result.hardwareBuffer,
                                    result.colorSpace,
                                )?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (e: Exception) {
                                Log.w(TAG, "截屏位图转换失败", e)
                                null
                            } finally {
                                runCatching { result.hardwareBuffer.close() }
                            }
                            cont.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "截屏失败，errorCode=$errorCode")
                            cont.resume(null)
                        }
                    },
                )
            } catch (e: Exception) {
                Log.w(TAG, "截屏调用异常", e)
                cont.resume(null)
            }
        }

    private const val TAG = "AdCalm"
}
