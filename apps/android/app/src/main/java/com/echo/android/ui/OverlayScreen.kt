package com.echo.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.echo.android.ocr.OcrBlock
import com.echo.android.palette.TextPalette

/** 一个块的完整翻译结果：原文块 + 译文 + 区域配色 */
data class TranslationResult(
    val block: OcrBlock,
    val translation: String,
    val palette: TextPalette.Palette,
)

/** 识别结果列表：每块一组「原文（小字灰）+ 译文」，逐条分隔 */
@Composable
fun ResultList(results: List<TranslationResult>) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            "识别结果（${results.size} 条）",
            style = MaterialTheme.typography.titleSmall,
        )
        results.forEachIndexed { index, result ->
            if (index > 0) {
                HorizontalDivider(
                    Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        result.block.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        result.translation.ifEmpty { "（未翻译）" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
