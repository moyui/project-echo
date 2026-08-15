package com.echo.android.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import com.echo.android.ocr.OcrBlock
import com.echo.android.palette.TextPalette

/**
 * 实时翻译悬浮窗：底部对照面板。
 * 所有气泡的译文逐条列在屏幕底部的一个面板里，全部横排，
 * 按面板宽度自动换行，内容过多时整体缩小字号以适配面板高度。
 */
class OverlayView(context: Context) : View(context) {

    data class Item(
        val block: OcrBlock,
        val translation: String,
        val palette: TextPalette.Palette,
    )

    var hasContent = false
        private set

    /** 用户字号缩放（设置页） */
    var fontScale = 1f

    /** 面板底衬浓度（设置页） */
    var scrimAlpha = 0.75f

    private var items: List<Item> = emptyList()

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        color = Color.argb(242, 255, 255, 255)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        strokeWidth = 2f
    }

    fun update(items: List<Item>, srcWidth: Int, srcHeight: Int) {
        this.items = items
        hasContent = items.isNotEmpty()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty() || width <= 0 || height <= 0) return

        val marginH = (width * 0.025f).toInt()
        // 底部间距与裁剪设置同源：导航条/手势安全区 + 少量呼吸，面板不压系统栏
        val cropBottom = runCatching {
            com.echo.android.util.CropRegion.fromPrefs(context).bottom
        }.getOrDefault(0)
        val bottomMargin = (cropBottom + (height * 0.006f).toInt()).coerceAtLeast((height * 0.02f).toInt())
        val panelWidth = width - marginH * 2
        val maxPanelHeight = (height * 0.42f).toInt()
        val padH = (width * 0.035f).toInt()
        val padV = padH / 2
        val entryGap = (height * 0.008f).toInt()
        val textWidth = panelWidth - padH * 2

        fun buildLayouts(fontPx: Float): List<StaticLayout> = items.map { item ->
            StaticLayout.Builder
                .obtain(item.translation, 0, item.translation.length, textPaint.apply { textSize = fontPx }, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(entryGap.toFloat() * 0.4f, 1f)
                .build()
        }

        // 字号超限时迭代等比缩小，保证所有条目都完整放进面板（不再有下限，避免溢出）
        var fontPx = width / 24f * fontScale
        var layouts = buildLayouts(fontPx)
        fun contentHeight(ls: List<StaticLayout>): Int = ls.sumOf { it.height } + entryGap * (ls.size - 1)
        val maxContent = maxPanelHeight - padV * 2
        var content = contentHeight(layouts)
        var guard = 0
        while (content > maxContent && guard < 6) {
            fontPx = (fontPx * maxContent.toFloat() / content * 0.98f).coerceAtLeast(6f)
            layouts = buildLayouts(fontPx)
            content = contentHeight(layouts)
            guard++
        }

        val panelHeight = (content + padV * 2).coerceAtMost(maxPanelHeight).toFloat()
        val panelTop = (height - bottomMargin - panelHeight).toFloat()
        val panelLeft = marginH.toFloat()
        val panelRight = (width - marginH).toFloat()

        bgPaint.color = Color.argb((scrimAlpha * 255).toInt(), 18, 18, 22)
        canvas.drawRoundRect(panelLeft, panelTop, panelRight, panelTop + panelHeight, 16f, 16f, bgPaint)

        // 双保险：极端情况内容仍略超时裁剪在面板内，不画出黑底之外
        canvas.save()
        canvas.clipRect(panelLeft, panelTop, panelRight, panelTop + panelHeight)
        var y = panelTop + padV
        layouts.forEachIndexed { index, layout ->
            canvas.save()
            canvas.translate(panelLeft + padH, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height
            if (index < layouts.lastIndex) {
                y += entryGap
                canvas.drawLine(panelLeft + padH, y - entryGap / 2f, panelRight - padH, y - entryGap / 2f, dividerPaint)
            }
        }
        canvas.restore()
    }
}
