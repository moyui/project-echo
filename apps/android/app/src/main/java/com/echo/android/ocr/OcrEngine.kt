package com.echo.android.ocr

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine

enum class OcrLang(val display: String) {
    Ja("日语"),
    Zh("中文"),
    En("英文"),
}

/** OCR 识别出的一行：文本 + 像素 bbox */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val height: Int get() = bottom - top
    val width: Int get() = right - left
}

/** 一个 OCR 文本块（若干行）：叠加渲染按块定位、按行高定字号 */
data class OcrBlock(
    val lines: List<OcrLine>,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val text: String get() = lines.joinToString("\n") { it.text }

    val height: Int get() = bottom - top
    val width: Int get() = right - left

    /**
     * 纵排文本（漫画竖排对白）：ML Kit 日语识别竖排时，"行"实为竖列。
     * 判定：所有行都显著高大于宽 → 竖列。
     */
    val isVertical: Boolean
        get() = lines.isNotEmpty() && lines.all { it.height.toFloat() > it.width.toFloat() * 1.15f }

    /** 平均行高（px），横排时用于推算字号 */
    val lineHeightPx: Float
        get() = if (lines.isEmpty()) height.toFloat() else lines.map { it.height }.average().toFloat()

    /** 平均行宽（px），纵排时即列宽（≈字号） */
    val avgLineWidthPx: Float
        get() = if (lines.isEmpty()) width.toFloat() else lines.map { it.width }.average().toFloat()

    /** 坐标整体缩放（小字放大识别后映射回原图用） */
    fun scaledBy(factor: Float): OcrBlock = OcrBlock(
        lines = lines.map { line ->
            line.copy(
                left = (line.left * factor).toInt(),
                top = (line.top * factor).toInt(),
                right = (line.right * factor).toInt(),
                bottom = (line.bottom * factor).toInt(),
            )
        },
        left = (left * factor).toInt(),
        top = (top * factor).toInt(),
        right = (right * factor).toInt(),
        bottom = (bottom * factor).toInt(),
    )
}

/** Google Play Services Task 的协程桥（官方 play-services 扩展包已停更，自实现） */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            cont.resumeWith(Result.success(task.result))
        } else {
            cont.resumeWith(Result.failure(task.exception ?: RuntimeException("ML Kit task failed")))
        }
    }
}

/** ML Kit 端侧 OCR 封装（bundled，无需 Google Play 服务） */
object OcrEngine {

    private fun recognizer(lang: OcrLang): TextRecognizer = when (lang) {
        OcrLang.Ja -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        OcrLang.Zh -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        OcrLang.En -> TextRecognition.getClient(TextRecognizerOptions.Builder().build())
    }

    /** 直接用已解码的位图识别（避免二次读取 uri —— 部分 OEM 上 content 读取对特定文件会失败） */
    suspend fun recognize(image: Bitmap, lang: OcrLang): List<OcrBlock> {
        val input = InputImage.fromBitmap(image, 0)
        val result = recognizer(lang).process(input).await()
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val lines = block.lines.mapNotNull { line ->
                val lb = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, lb.left, lb.top, lb.right, lb.bottom)
            }
            if (lines.isEmpty()) return@mapNotNull null
            OcrBlock(lines, box.left, box.top, box.right, box.bottom)
        }
    }
}
