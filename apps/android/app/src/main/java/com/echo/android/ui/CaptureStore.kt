package com.echo.android.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.echo.android.ocr.OcrBlock
import com.echo.android.palette.TextPalette

/**
 * 最新一次捕获翻译结果的进程级缓存。
 * 输入源：悬浮球点按抓取的屏幕帧（ScreenCaptureService）、通知栏截图入口。
 * 识别结果页（ResultActivity）从这里取数；bitmap 仅在发布时用于取色，
 * 不随缓存持有（一张 1080×2400 截图约 10MB，驻留无意义）。
 */
object CaptureStore {
    data class Captured(
        val results: List<TranslationResult>,
        val atMillis: Long = System.currentTimeMillis(),
    )

    var latest by mutableStateOf<Captured?>(null)
        private set

    fun publish(
        bitmap: Bitmap,
        blocks: List<OcrBlock>,
        translations: List<String>,
    ) {
        latest =
            Captured(
                results =
                    blocks.mapIndexed { index, block ->
                        TranslationResult(
                            block = block,
                            translation = translations.getOrElse(index) { "" },
                            palette = TextPalette.sample(bitmap, block),
                        )
                    },
            )
    }

    fun clear() {
        latest = null
    }
}
