package com.echo.android.palette

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.echo.android.ocr.OcrBlock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 从原图采样文本区域的背景色与文字色，让译文与原文观感一致。
 *
 * 策略：
 * - 背景 = 文本框上下边缘若干行像素的均值（文字主体不占据边缘）
 * - 文字 = 框内与背景亮度差大的像素簇均值（步进采样控性能）
 */
object TextPalette {

    data class Palette(val background: Color, val foreground: Color)

    private data class Rgb(var r: Float = 0f, var g: Float = 0f, var b: Float = 0f) {
        fun add(pixel: Int) {
            r += (pixel shr 16 and 0xFF)
            g += (pixel shr 8 and 0xFF)
            b += (pixel and 0xFF)
        }

        fun toColor(count: Int): Color =
            Color(r / count / 255f, g / count / 255f, b / count / 255f)

        fun luminance(count: Int): Float {
            val (rr, gg, bb) = Triple(r / count, g / count, b / count)
            return (0.299f * rr + 0.587f * gg + 0.114f * bb) / 255f
        }
    }

    fun sample(bitmap: Bitmap, block: OcrBlock): Palette =
        sample(bitmap, block.left, block.top, block.right, block.bottom)

    fun sample(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Palette {
        val l = max(0, left)
        val t = max(0, top)
        val r = min(bitmap.width - 1, right)
        val b = min(bitmap.height - 1, bottom)
        if (r - l < 4 || b - t < 4) {
            return Palette(Color.White.copy(alpha = 0.4f), Color.Black)
        }

        // 背景：上下各 2 行边缘
        val ring = Rgb()
        var ringCount = 0
        for (x in l..r step 2) {
            for (y in intArrayOf(t, t + 1, b - 1, b)) {
                ring.add(bitmap.getPixel(x, y))
                ringCount++
            }
        }
        val background = ring.toColor(ringCount)
        val bgLum = ring.luminance(ringCount)

        // 文字：框内（跳过边缘行）与背景亮度差 > 0.30 的像素
        val fg = Rgb()
        var fgCount = 0
        val yStart = t + 2
        val yEnd = b - 2
        var total = 0
        var y = yStart
        while (y < yEnd) {
            var x = l
            while (x < r) {
                val pixel = bitmap.getPixel(x, y)
                val lum = (
                    0.299f * (pixel shr 16 and 0xFF) +
                        0.587f * (pixel shr 8 and 0xFF) +
                        0.114f * (pixel and 0xFF)
                    ) / 255f
                if (abs(lum - bgLum) > 0.30f) {
                    fg.add(pixel)
                    fgCount++
                }
                total++
                x += 2
            }
            y += 2
        }

        // 高反差像素太少说明该区域没有清晰文字，按背景亮度给对比色兜底
        val foreground = if (fgCount < total * 0.02f) {
            if (bgLum > 0.5f) Color.Black else Color.White
        } else {
            fg.toColor(fgCount)
        }
        return Palette(background, foreground)
    }
}
