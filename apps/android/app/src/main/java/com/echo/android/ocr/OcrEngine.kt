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

enum class OcrLang(
    val display: String,
) {
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
    fun scaledBy(factor: Float): OcrBlock =
        OcrBlock(
            lines =
                lines.map { line ->
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
suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
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
    private fun recognizer(lang: OcrLang): TextRecognizer =
        when (lang) {
            OcrLang.Ja -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrLang.Zh -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrLang.En -> TextRecognition.getClient(TextRecognizerOptions.Builder().build())
        }

    /** 直接用已解码的位图识别（避免二次读取 uri —— 部分 OEM 上 content 读取对特定文件会失败） */
    suspend fun recognize(
        image: Bitmap,
        lang: OcrLang,
    ): List<OcrBlock> {
        val input = InputImage.fromBitmap(image, 0)
        val result = recognizer(lang).process(input).await()
        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val lines =
                block.lines.mapNotNull { line ->
                    val lb = line.boundingBox ?: return@mapNotNull null
                    OcrLine(line.text, lb.left, lb.top, lb.right, lb.bottom)
                }
            if (lines.isEmpty()) return@mapNotNull null
            OcrBlock(lines, box.left, box.top, box.right, box.bottom)
        }
    }
}

/**
 * 气泡聚类：ML Kit 常把一个气泡拆成多个块，逐块渲染译文会导致框错位叠影。
 * 规则：同方向（横/竖）且膨胀矩形（半径=平均行高×1.2）相交的块合并为一个气泡；
 * 合并后按阅读顺序重排行（纵排右起、横排自上而下），一个气泡只出一个译文框。
 * 块与块之间也按阅读顺序排列（纵排右→左、横排左→右，均自上而下），
 * 覆盖 ML Kit 的原始返回顺序，保证语句拼接/渲染顺序符合漫画阅读习惯。
 */
object BlockMerge {
    fun merge(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size <= 1) return blocks
        var groups = blocks.map { listOf(it) }

        // 传递合并直到收敛
        var changed = true
        while (changed) {
            changed = false
            val remaining = groups.toMutableList()
            val merged = mutableListOf<List<OcrBlock>>()
            while (remaining.isNotEmpty()) {
                var group = remaining.removeAt(0)
                var absorbed = true
                while (absorbed) {
                    absorbed = false
                    val iter = remaining.iterator()
                    while (iter.hasNext()) {
                        val other = iter.next()
                        if (compatible(group, other)) {
                            group = group + other
                            iter.remove()
                            absorbed = true
                            changed = true
                        }
                    }
                }
                merged.add(group)
            }
            groups = merged
        }

        return groups.map { members ->
            val vertical = members.first().isVertical
            val lines =
                members.flatMap { it.lines }.sortedWith(
                    if (vertical) {
                        compareByDescending<OcrLine> { it.right }.thenBy { it.top }
                    } else {
                        compareBy({ it.top }, { it.left })
                    },
                )
            OcrBlock(
                lines = lines,
                left = members.minOf { it.left },
                top = members.minOf { it.top },
                right = members.maxOf { it.right },
                bottom = members.maxOf { it.bottom },
            )
        }.let { sortByReadingOrder(it) }
    }

    /**
     * 块级阅读顺序（参考 manga-image-translator 的 sort_regions，省去面板检测）：
     * 页面主导方向由竖排块占比决定——日文漫画竖排占优时按列（右→左、列内上→下），
     * 否则按行（上→下、行内左→右）。排序键用块中心点，且先分组再排组内，
     * 避免简单 (x, y) 复合键把"上一行靠右的块"排到"下一行靠左的块"之前。
     */
    private fun sortByReadingOrder(blocks: List<OcrBlock>): List<OcrBlock> {
        if (blocks.size <= 1) return blocks
        val verticalDominant = blocks.count { it.isVertical } >= (blocks.size + 1) / 2
        // (块, 中心 x, 中心 y)
        val withCenter =
            blocks.map { b ->
                Triple(b, (b.left + b.right) / 2.0, (b.top + b.bottom) / 2.0)
            }

        if (verticalDominant) {
            // 列主序：右→左分列，列内上→下
            val verticalWidths = blocks.filter { it.isVertical }.map { it.width }
            val colGap =
                (verticalWidths.medianOrNull() ?: blocks.map { it.width }.average())
                    .times(0.6)
                    .toInt()
                    .coerceAtLeast(16)
            val cols = mutableListOf<MutableList<Triple<OcrBlock, Double, Double>>>()
            for (t in withCenter.sortedByDescending { it.second }) {
                val col = cols.lastOrNull()
                if (col == null || col[0].second - t.second > colGap) {
                    cols.add(mutableListOf(t))
                } else {
                    col.add(t)
                }
            }
            return cols.flatMap { col -> col.sortedBy { it.third } }.map { it.first }
        } else {
            // 行主序：上→下分行，行内左→右
            val horizontalHeights = blocks.filterNot { it.isVertical }.map { it.height }
            val rowGap =
                (horizontalHeights.medianOrNull() ?: blocks.map { it.height }.average())
                    .times(0.6)
                    .toInt()
                    .coerceAtLeast(12)
            val rows = mutableListOf<MutableList<Triple<OcrBlock, Double, Double>>>()
            for (t in withCenter.sortedBy { it.third }) {
                val row = rows.lastOrNull()
                if (row == null || t.third - row[0].third > rowGap) {
                    rows.add(mutableListOf(t))
                } else {
                    row.add(t)
                }
            }
            return rows.flatMap { row -> row.sortedBy { it.second } }.map { it.first }
        }
    }

    private fun List<Int>.medianOrNull(): Double? = if (isEmpty()) null else sorted()[size / 2].toDouble()

    private fun compatible(
        a: List<OcrBlock>,
        b: List<OcrBlock>,
    ): Boolean {
        val vertical = a.first().isVertical
        if (vertical != b.first().isVertical) return false

        // 膨胀半径按"字符尺寸"算（垂直块=行宽即列宽，水平块=行高），
        // 不能用行高——竖排的行是整列，高几百像素，会把整页气泡连锁合并
        fun margin(blocks: List<OcrBlock>): Int {
            val charSize = blocks.maxOf { if (vertical) it.avgLineWidthPx else it.lineHeightPx }
            return (charSize * 1.2f).toInt().coerceIn(8, 48)
        }

        fun expanded(
            blocks: List<OcrBlock>,
            m: Int,
        ): IntArray =
            intArrayOf(
                blocks.minOf { it.left } - m,
                blocks.minOf { it.top } - m,
                blocks.maxOf { it.right } + m,
                blocks.maxOf { it.bottom } + m,
            )
        val ra = expanded(a, margin(a))
        val rb = expanded(b, margin(b))
        return ra[0] < rb[2] && ra[2] > rb[0] && ra[1] < rb[3] && ra[3] > rb[1]
    }
}
