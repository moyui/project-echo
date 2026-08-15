package com.echo.android.util

import android.content.Context
import com.echo.android.ocr.OcrBlock

/**
 * 裁剪区域：跳过状态栏（时间/电量）和导航栏/手势条区域。
 * 顶部默认取系统状态栏实际高度，底部默认取导航栏高度，设置页可覆盖。
 */
data class CropRegion(val top: Int, val bottom: Int) {

    /** block 是否落在裁剪区内（整块在顶部条或底部条内才跳过，跨界块保留） */
    fun shouldSkip(block: OcrBlock, screenHeight: Int): Boolean =
        block.bottom <= top || (bottom > 0 && block.top >= screenHeight - bottom)

    fun filter(blocks: List<OcrBlock>, screenHeight: Int): List<OcrBlock> =
        blocks.filterNot { shouldSkip(it, screenHeight) }

    /**
     * 行级裁剪：ML Kit 可能把状态栏时钟和邻近气泡文字合进同一块，
     * 块级过滤会整块放行。这里先剔除落在顶/底条内的行，再由剩余行重建块。
     */
    fun filterByLines(blocks: List<OcrBlock>, screenHeight: Int): List<OcrBlock> =
        blocks.mapNotNull { block ->
            val kept = block.lines.filterNot { line ->
                line.bottom <= top || (bottom > 0 && line.top >= screenHeight - bottom)
            }
            if (kept.isEmpty()) {
                null
            } else {
                OcrBlock(
                    lines = kept,
                    left = kept.minOf { it.left },
                    top = kept.minOf { it.top },
                    right = kept.maxOf { it.right },
                    bottom = kept.maxOf { it.bottom },
                )
            }
        }

    companion object {
        fun fromPrefs(context: Context): CropRegion {
            val prefs = context.getSharedPreferences("echo", Context.MODE_PRIVATE)
            val top = prefs.getString("crop_top", null)?.toIntOrNull() ?: statusBarHeight(context)
            val bottom = prefs.getString("crop_bottom", null)?.toIntOrNull() ?: navigationBarHeight(context)
            return CropRegion(top, bottom)
        }

        fun statusBarHeight(context: Context): Int =
            dimension(context, "status_bar_height") ?: (32 * context.resources.displayMetrics.density).toInt()

        fun navigationBarHeight(context: Context): Int =
            dimension(context, "navigation_bar_height") ?: (48 * context.resources.displayMetrics.density).toInt()

        private fun dimension(context: Context, name: String): Int? {
            val res = context.resources
            val id = res.getIdentifier(name, "dimen", "android")
            return if (id > 0) res.getDimensionPixelSize(id).takeIf { it > 0 } else null
        }
    }
}
