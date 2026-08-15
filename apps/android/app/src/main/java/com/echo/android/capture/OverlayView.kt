package com.echo.android.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.echo.android.ocr.OcrBlock
import com.echo.android.palette.TextPalette
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 实时翻译悬浮窗。
 * 两种展示方式：
 * - 下方对照（默认）：保持原块宽度，译文画在原块下方，原文不被覆盖
 * - 原位覆盖：译文直接盖在原块上（取色底衬）
 */
class OverlayView(context: Context) : View(context) {

    data class Item(
        val block: OcrBlock,
        val translation: String,
        val palette: TextPalette.Palette,
    )

    var hasContent = false
        private set

    /** true = 译文在原块下方；false = 覆盖原块 */
    var displayBelow = true

    /** 用户字号缩放（设置页），横排/竖排两分支统一应用 */
    var fontScale = 1f

    /** 底衬浓度（设置页） */
    var scrimAlpha = 0.55f

    private var items: List<Item> = emptyList()
    private var srcWidth = 1
    private var srcHeight = 1

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val verticalPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    fun update(items: List<Item>, srcWidth: Int, srcHeight: Int) {
        this.items = items
        this.srcWidth = srcWidth
        this.srcHeight = srcHeight
        hasContent = items.isNotEmpty()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = width.toFloat() / srcWidth
        items.forEach { item ->
            if (item.block.isVertical) {
                drawVertical(canvas, item, scale)
            } else {
                drawHorizontal(canvas, item, scale)
            }
        }
    }

    private fun scrimColor(palette: TextPalette.Palette): Int =
        android.graphics.Color.argb(
            (scrimAlpha * 255).toInt(),
            (palette.background.red * 255).roundToInt(),
            (palette.background.green * 255).roundToInt(),
            (palette.background.blue * 255).roundToInt(),
        )

    private fun textColor(palette: TextPalette.Palette): Int =
        android.graphics.Color.argb(
            (0.9f * 255).toInt(),
            (palette.foreground.red * 255).roundToInt(),
            (palette.foreground.green * 255).roundToInt(),
            (palette.foreground.blue * 255).roundToInt(),
        )

    private fun drawHorizontal(canvas: Canvas, item: Item, scale: Float) {
        val block = item.block
        val left = block.left * scale
        val top = block.top * scale
        val right = block.right * scale
        val bottom = block.bottom * scale

        val basePx = block.lineHeightPx * scale * 0.82f * fontScale
        val charsPerLine = ((right - left) / max(basePx, 1f)).toInt().coerceAtLeast(1)
        val capacity = charsPerLine * (block.lines.size + 1)
        val shrink = if (item.translation.length > capacity) {
            (capacity.toFloat() / item.translation.length).coerceAtLeast(0.6f)
        } else {
            1f
        }

        textPaint.textSize = basePx * shrink
        textPaint.color = textColor(item.palette)
        bgPaint.color = scrimColor(item.palette)

        val widthPx = ((right - left).toInt() - 4).coerceAtLeast(8)
        val layout = StaticLayout.Builder
            .obtain(item.translation, 0, item.translation.length, textPaint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(block.lines.size + 1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        val pad = 4f
        val scrimTop = if (displayBelow) bottom + pad else top
        canvas.drawRoundRect(left, scrimTop, right, scrimTop + layout.height + pad * 2, 4f, 4f, bgPaint)
        canvas.save()
        canvas.translate(left + 2, scrimTop + pad)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawVertical(canvas: Canvas, item: Item, scale: Float) {
        val block = item.block
        val left = block.left * scale
        val top = block.top * scale
        val right = block.right * scale
        val bottom = block.bottom * scale

        val basePx = block.avgLineWidthPx * scale * 0.82f * fontScale
        val charsPerColumn = ((bottom - top) / max(basePx * 1.2f, 1f)).toInt().coerceIn(1, 64)
        val maxColumns = ((right - left) / max(basePx * 1.15f, 1f)).toInt().coerceIn(1, 32)
        val capacity = charsPerColumn * maxColumns
        val cleanText = item.translation.replace("\n", "")
        val shrink = if (cleanText.length > capacity) {
            (capacity.toFloat() / cleanText.length).coerceAtLeast(0.6f)
        } else {
            1f
        }
        val charPx = basePx * shrink

        bgPaint.color = scrimColor(item.palette)
        verticalPaint.textSize = charPx
        verticalPaint.color = textColor(item.palette)

        if (displayBelow) {
            // 竖排原文保持可见，译文以横排放在块下方（宽度不小于 6 字，保证可读）
            val widthPx = max((right - left).toInt(), (charPx * 6).toInt())
            textPaint.textSize = charPx
            textPaint.color = textColor(item.palette)
            val layout = StaticLayout.Builder
                .obtain(cleanText, 0, cleanText.length, textPaint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()
            val pad = 4f
            val scrimTop = bottom + pad
            val scrimRight = left + widthPx
            canvas.drawRoundRect(left, scrimTop, scrimRight, scrimTop + layout.height + pad * 2, 4f, 4f, bgPaint)
            canvas.save()
            canvas.translate(left + 2, scrimTop + pad)
            layout.draw(canvas)
            canvas.restore()
            return
        }

        val columns = cleanText.chunked(charsPerColumn).take(maxColumns)
        canvas.drawRoundRect(left, top, right, bottom, 4f, 4f, bgPaint)
        val fontMetrics = verticalPaint.fontMetrics
        val lineStep = charPx * 1.2f
        val baselineOffset = (lineStep - (fontMetrics.descent - fontMetrics.ascent)) / 2 - fontMetrics.ascent

        // 日文纵排从右往左读：首列在最右
        columns.forEachIndexed { colIndex, column ->
            val x = right - (colIndex + 0.5f) * charPx * 1.15f
            column.forEachIndexed { charIndex, _ ->
                val y = top + baselineOffset + charIndex * lineStep
                canvas.drawText(column, charIndex, charIndex + 1, x, y, verticalPaint)
            }
        }
    }
}
