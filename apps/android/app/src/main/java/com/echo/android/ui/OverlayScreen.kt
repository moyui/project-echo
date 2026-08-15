package com.echo.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.echo.android.ocr.OcrBlock
import com.echo.android.palette.TextPalette
import kotlin.math.max
import kotlin.math.roundToInt

/** 一个块的完整翻译结果：原文块 + 译文 + 区域配色 */
data class TranslationResult(
    val block: OcrBlock,
    val translation: String,
    val palette: TextPalette.Palette,
)

/** 译文文字透明度（底衬浓度由设置 scrim_alpha 控制） */
private const val TEXT_ALPHA = 0.9f
private const val MIN_SHRINK = 0.6f

/**
 * 叠加视图：原图铺满宽度，译文按 OCR bbox 定位。
 * below=true（下方对照）：保持原块宽度，译文画在原块下方，原文可见；
 * below=false（原位覆盖）：译文盖在原块上。
 */
@Composable
fun OverlayScreen(bitmap: Bitmap, results: List<TranslationResult>, below: Boolean = true) {
    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // 用户字号缩放（设置页），展示分支统一应用
    val fontScale = remember {
        context.getSharedPreferences("echo", android.content.Context.MODE_PRIVATE)
            .getString("font_scale", "1.0")?.toFloatOrNull() ?: 1f
    }
    val scrimAlpha = remember {
        context.getSharedPreferences("echo", android.content.Context.MODE_PRIVATE)
            .getString("scrim_alpha", "0.55")?.toFloatOrNull() ?: 0.55f
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val scale = with(density) { maxWidth.toPx() } / bitmap.width
            val displayHeight = with(density) { (bitmap.height * scale).toDp() }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(displayHeight)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )

                results.forEach { result ->
                    val block = result.block
                    val xOffset = with(density) { (block.left * scale).roundToInt().toDp() }
                    val yOffset = with(density) {
        ((if (below) block.bottom * scale + 6f else block.top * scale).roundToInt()).toDp()
                    }
                    val boxWidth = with(density) { ((block.right - block.left) * scale).roundToInt().toDp() }

                    val basePx = if (block.isVertical) block.avgLineWidthPx else block.lineHeightPx
                    val basePxScaled = basePx * scale * 0.82f * fontScale
                    val estPerLine = max(((block.right - block.left) * scale) / max(basePxScaled, 1f), 1f).toInt()
                    val capacity = estPerLine * (block.lines.size + 1)
                    val shrink = if (result.translation.length > capacity) {
                        (capacity.toFloat() / result.translation.length).coerceAtLeast(MIN_SHRINK)
                    } else {
                        1f
                    }
                    val fontSize = with(density) { (basePxScaled * shrink).toSp() }
                    // 下方对照时窄块（竖排）给个最小可读宽度
                    val finalWidth = if (below && block.isVertical) {
                        with(density) { max(boxWidth.toPx(), fontSize.toPx() * 6).toDp() }
                    } else {
                        boxWidth
                    }

                    Text(
                        text = result.translation,
                        style = TextStyle(
                            fontSize = fontSize,
                            lineHeight = fontSize * 1.12f,
                            textAlign = TextAlign.Center,
                        ),
                        color = result.palette.foreground.copy(alpha = TEXT_ALPHA),
                        maxLines = block.lines.size + 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .offset(x = xOffset, y = yOffset)
                            .width(finalWidth)
                            .background(
                                result.palette.background.copy(alpha = scrimAlpha),
                                RoundedCornerShape(3.dp),
                            )
                            .padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}
