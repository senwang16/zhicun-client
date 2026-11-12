package com.zhicun.app.data.ocr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.zhicun.app.domain.ocr.OcrEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ML Kit 中文文字识别实现（M7，T-005 决策：捆绑模型）。
 *
 * 三个实现细节值得说明：
 * 1. **识别器只建一次**（`by lazy`）。`TextRecognition.getClient` 本身很轻，但首次 `process`
 *    要加载模型；每次识别都新建客户端等于每次重载模型，慢且费电。识别器随进程存活，
 *    不主动 `close()` —— 它是 App 级单例，没有"用完"的时刻。
 * 2. **`Tasks.await` 放在 IO 线程**。ML Kit 的 Task 是异步 API，把它桥接成 `suspend` 最省事的方式
 *    是阻塞等待，但必须在 IO 调度器上等（`withContext(Dispatchers.IO)`），否则会卡住调用方线程。
 * 3. **`ExecutionException` 要拆包**。底层抛出的 `IOException`（图片读不出来）必须原样抛出，
 *    `ProcessingJobExecutor` 靠异常类型区分"可重试"和"终态失败"；
 *    如果统一包成 `ExecutionException`，一次磁盘抖动就会被判成永久失败。
 *
 * 中文识别器同时支持英文与数字（模型是多语种混合训练），因此 `IMAGE` 记录统一走这一个引擎，
 * 不必按图片语言分流。
 */
@Singleton
class MlKitOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : OcrEngine {

    /**
     * 识别器复用同一个实例。
     *
     * `ChineseTextRecognizerOptions` 是捆绑模型版；若换成 `TextRecognizerOptions.DEFAULT_OPTIONS`
     * 则只认拉丁字母，中文截图会返回空串。
     */
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(imagePath: String): String = withContext(Dispatchers.IO) {
        val file = File(imagePath)
        if (!file.exists()) {
            // 文件在但读不到 / 文件没了：都属于可重试（可能是外部清理或落盘未完成）
            throw IOException("图片不存在：$imagePath")
        }

        // fromFilePath 内部会读图片尺寸并做 EXIF 旋转，失败时自己抛 IOException
        val image = InputImage.fromFilePath(context, Uri.fromFile(file))
        val text = processSafely(image)
        val normalized = text.textBlocks
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
        Log.i(TAG, "OCR 完成 path=$imagePath 字符数=${normalized.length}")
        normalized
    }

    private fun processSafely(image: InputImage) = try {
        Tasks.await(recognizer.process(image))
    } catch (e: ExecutionException) {
        // 拆包：保留 IO 语义，其余（模型加载失败、解码失败）按原样抛，交由上层判终态
        val cause = e.cause ?: throw IllegalStateException("OCR 执行失败", e)
        throw cause
    } catch (e: InterruptedException) {
        // 恢复中断标记是 JVM 的硬约定，吞掉会让上游的取消逻辑失效
        Thread.currentThread().interrupt()
        throw IllegalStateException("OCR 被中断", e)
    }

    private companion object {
        const val TAG = "MlKitOcr"
    }
}
