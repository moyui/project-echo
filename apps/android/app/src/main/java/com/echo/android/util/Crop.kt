package com.echo.android.util

import android.content.Context
import com.echo.android.ocr.OcrBlock

/**
 * 裁剪区域：跳过状态栏（时间/电量）和导航栏区域的文本。
 * 顶部默认取系统状态栏实际高度，设置页可覆盖。
 */
data class CropRegion(val top: Int, val bottom: Int) {

    /** block 是否落在裁剪区内（整块在顶部条或底部条内才跳过，跨界块保留） */
    fun shouldSkip(block: OcrBlock, screenHeight: Int): Boolean =
        block.bottom <= top || (bottom > 0 && block.top >= screenHeight - bottom)

    fun filter(blocks: List<OcrBlock>, screenHeight: Int): List<OcrBlock> =
        blocks.filterNot { shouldSkip(it, screenHeight) }

    companion object {
        fun fromPrefs(context: Context): CropRegion {
            val prefs = context.getSharedPreferences("echo", Context.MODE_PRIVATE)
            val top = prefs.getString("crop_top", null)?.toIntOrNull() ?: statusBarHeight(context)
            val bottom = prefs.getString("crop_bottom", null)?.toIntOrNull() ?: 0
            return CropRegion(top, bottom)
        }

        fun statusBarHeight(context: Context): Int {
            val res = context.resources
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) {
                res.getDimensionPixelSize(id)
            } else {
                (32 * res.displayMetrics.density).toInt()
            }
        }
    }
}
