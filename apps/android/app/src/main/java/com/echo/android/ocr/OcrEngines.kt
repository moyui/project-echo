package com.echo.android.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.echo.android.ocr.OcrEngineKind.MANGA
import com.echo.android.ocr.OcrEngineKind.MLKIT
import com.echo.android.ocr.OcrEngineKind.PPOCR
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * OCR 引擎三档（文本定位统一用 ML Kit，差异在识别，选哪个是哪个）：
 * - MLKIT  快速：ML Kit 识别全部行，速度最快
 * - PPOCR  标准（默认）：横排 PP-OCRv5 + 竖排 ML Kit
 * - MANGA  精准：manga-ocr 全量识别（漫画质量最佳，模型未下载时回退 ML Kit）
 */
enum class OcrEngineKind(
    val display: String,
    val desc: String,
) {
    MLKIT("快速", "ML Kit 识别全部文本，速度最快"),
    PPOCR("标准（默认）", "横排 PP-OCRv5 + 竖排 ML Kit"),
    MANGA("精准", "manga-ocr 识别全部文本（需下载模型，质量最佳）"),
}

fun Context.ocrEngine(): OcrEngineKind {
    val name = getSharedPreferences("echo", Context.MODE_PRIVATE).getString("ocr_engine", OcrEngineKind.PPOCR.name)
    return runCatching { OcrEngineKind.valueOf(name!!) }.getOrNull() ?: OcrEngineKind.PPOCR
}

/**
 * 按 ML Kit 的定位做二次识别，替换每行文本（坐标不变）。
 * 引擎不可用时回退原文本（不中断管线）。
 */
fun refineBlocks(
    context: Context,
    blocks: List<OcrBlock>,
    bitmap: Bitmap,
    engine: OcrEngineKind,
): List<OcrBlock> =
    when (engine) {
        MLKIT -> blocks
        PPOCR ->
            runCatching { PpOcrRecognizer.get(context).refine(blocks, bitmap) }
                .onFailure { android.util.Log.w("EchoOcr", "PP-OCRv5 识别失败，回退 ML Kit: ${it.message}") }
                .getOrElse { blocks }
        MANGA -> {
            val ocr = MangaOcrRecognizer.getOrNull(context)
            if (ocr == null) {
                android.util.Log.w("EchoOcr", "manga-ocr 模型未下载，回退 ML Kit")
                blocks
            } else {
                runCatching { ocr.refine(blocks, bitmap) }
                    .onFailure { android.util.Log.w("EchoOcr", "manga-ocr 识别失败，回退 ML Kit: ${it.message}") }
                    .getOrElse { blocks }
            }
        }
    }

private fun ortEnv(): OrtEnvironment = OrtEnvironment.getEnvironment()

private fun readAssetBytes(
    context: Context,
    path: String,
): ByteArray = context.assets.open(path).use { it.readBytes() }

private fun cropBmp(
    src: Bitmap,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    pad: Int,
): Bitmap {
    val l = (left - pad).coerceAtLeast(0)
    val t = (top - pad).coerceAtLeast(0)
    val r = (right + pad).coerceAtMost(src.width)
    val b = (bottom + pad).coerceAtMost(src.height)
    if (r <= l || b <= t) return src
    return Bitmap.createBitmap(src, l, t, r - l, b - t)
}

// ---------- PP-OCRv5 rec（默认引擎） ----------

/**
 * PP-OCRv5 mobile 文本行识别（CTC）。
 * 输入 h=48、宽按行宽比例（8 对齐），归一化 (x/255-0.5)/0.5；blank 在词表末位。
 */
class PpOcrRecognizer private constructor(
    context: Context,
) {
    companion object {
        @Volatile
        private var instance: PpOcrRecognizer? = null

        fun get(context: Context): PpOcrRecognizer =
            instance ?: synchronized(this) {
                instance ?: PpOcrRecognizer(context.applicationContext).also { instance = it }
            }
    }

    private val env = ortEnv()
    private val session: OrtSession
    private val inputName: String
    private val dict: List<String>

    init {
        val bytes = readAssetBytes(context, "ocr/ppocrv5-rec.onnx")
        session = env.createSession(bytes, OrtSession.SessionOptions())
        inputName = session.inputNames.first()
        dict =
            context.assets
                .open("ocr/ppocrv5-dict.txt")
                .bufferedReader()
                .readLines()
    }

    /** 输入必须是横排文本条（竖列请先 rotateToHorizontal） */
    fun recognizeLine(line: Bitmap): String {
        val h = 48
        val rawW = line.width * h / line.height.coerceAtLeast(1)
        val w = ((rawW + 7) / 8 * 8).coerceIn(16, 1280)
        val scaled = Bitmap.createScaledBitmap(line, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val data = FloatArray(3 * w * h)
        for (i in pixels.indices) {
            val px = pixels[i]
            data[i] = (((px shr 16 and 0xFF)) / 255f - 0.5f) / 0.5f
            data[w * h + i] = (((px shr 8 and 0xFF)) / 255f - 0.5f) / 0.5f
            data[2 * w * h + i] = (((px and 0xFF)) / 255f - 0.5f) / 0.5f
        }

        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { output ->
                val raw = output[0].value
                val probs: Array<FloatArray> =
                    if (raw is Array<*> && raw.isNotEmpty() && raw[0] is FloatArray) {
                        // 导出无 batch 维 [T][C]
                        @Suppress("UNCHECKED_CAST")
                        raw as Array<FloatArray>
                    } else {
                        // 带 batch 维 [1][T][C]
                        @Suppress("UNCHECKED_CAST")
                        (raw as Array<*>)[0] as Array<FloatArray>
                    }
                return ctcDecode(probs)
            }
        }
    }

    private fun ctcDecode(probs: Array<FloatArray>): String {
        val blank = probs[0].size - 1
        val sb = StringBuilder()
        var prev = -1
        for (step in probs) {
            var best = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (c in step.indices) {
                if (step[c] > bestScore) {
                    bestScore = step[c]
                    best = c
                }
            }
            if (best != blank && best != prev && best < dict.size) {
                sb.append(dict[best])
            }
            prev = best
        }
        // 日文文本不含空格；CTC 步进残留的空格/全角空格清掉
        return sb
            .toString()
            .replace(" ", "")
            .replace("\u3000", "")
            .trim()
    }

    /**
     * 混合二次识别：横排行用 PP-OCRv5（横排/中文小字强于 ML Kit）；
     * 竖排列保留 ML Kit 原文——列裁剪旋转后压到 48px 高损失过大，
     * 实测乱字率高（CTC 对模糊切片输出相似字），不如 ML Kit 原生竖排。
     */
    fun refine(
        blocks: List<OcrBlock>,
        bitmap: Bitmap,
    ): List<OcrBlock> =
        blocks.map { block ->
            val lines =
                block.lines.map { line ->
                    val vertical = line.height > line.width * 1.2f
                    if (vertical) {
                        line
                    } else {
                        val crop = cropBmp(bitmap, line.left, line.top, line.right, line.bottom, 2)
                        val text = recognizeLine(crop)
                        if (text.isBlank()) line else line.copy(text = text)
                    }
                }
            block.copy(lines = lines)
        }
}

// ---------- manga-ocr（精准引擎，按需下载） ----------

/**
 * manga-ocr：ViT 编码器(224×224, mean/std 0.5) + BERT 解码器贪心自回归。
 * 模型文件在 filesDir/ocr-models/，由 ModelDownloader 负责。
 */
class MangaOcrRecognizer private constructor(
    modelDir: File,
) {
    companion object {
        const val ENCODER = "manga-ocr-encoder-dhq.onnx"
        const val DECODER = "manga-ocr-decoder-dhq.onnx"
        const val VOCAB = "manga-ocr-vocab.txt"
        private const val START_TOKEN = 2L
        private const val EOS_TOKEN = 3L

        @Volatile
        private var instance: MangaOcrRecognizer? = null

        fun modelDir(context: Context): File = File(context.filesDir, "ocr-models")

        fun isDownloaded(context: Context): Boolean {
            val dir = modelDir(context)
            return File(dir, ENCODER).exists() && File(dir, DECODER).exists() && File(dir, VOCAB).exists()
        }

        /** 模型就绪才返回实例，否则 null（调用方回退） */
        fun getOrNull(context: Context): MangaOcrRecognizer? {
            if (!isDownloaded(context)) return null
            if (instance != null) return instance
            synchronized(this) {
                if (instance == null) {
                    instance =
                        runCatching { MangaOcrRecognizer(modelDir(context)) }
                            .onFailure { android.util.Log.w("EchoOcr", "manga-ocr 加载失败: ${it.message}") }
                            .getOrNull()
                }
                return instance
            }
        }
    }

    private val env = ortEnv()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val vocab: List<String>

    init {
        val opts = OrtSession.SessionOptions()
        encoder = env.createSession(File(modelDir, ENCODER).absolutePath, opts)
        decoder = env.createSession(File(modelDir, DECODER).absolutePath, opts)
        vocab = File(modelDir, VOCAB).readLines()
    }

    /**
     * 识别一个横排文本行（对齐 mokuro 策略）：
     * 先等比缩放到 64px 行高（text_height，训练尺度），再拉伸 224×224，
     * 避免多行整块拉伸时每行字高被压缩。
     */
    fun recognizeLine(crop: Bitmap): String {
        val th = 64
        val rawW = crop.width * th / crop.height.coerceAtLeast(1)
        val lineScaled = Bitmap.createScaledBitmap(crop, rawW.coerceAtLeast(16), th, true)
        val scaled = Bitmap.createScaledBitmap(lineScaled, 224, 224, true)
        val pixels = IntArray(224 * 224)
        scaled.getPixels(pixels, 0, 224, 0, 0, 224, 224)
        val n = pixels.size
        val data = FloatArray(3 * n)
        for (i in 0 until n) {
            val px = pixels[i]
            val r = px shr 16 and 0xFF
            val g = px shr 8 and 0xFF
            val b = px and 0xFF
            // ITU-R 601-2 灰度（对齐官方 PIL convert("L")，模型在灰度图上训练）
            val y = (0.299f * r + 0.587f * g + 0.114f * b).roundToInt()
            val v = (y / 255f - 0.5f) / 0.5f
            data[i] = v
            data[n + i] = v
            data[2 * n + i] = v
        }

        val hidden: Array<Array<FloatArray>>
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, 224, 224)).use { t ->
            encoder.run(mapOf("pixel_values" to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                hidden = out[0].value as Array<Array<FloatArray>> // [1][seq][dim]
            }
        }

        var inputIds = longArrayOf(START_TOKEN)
        val tokens = mutableListOf<Long>()
        var finished = false
        while (!finished && tokens.size < 300) {
            val ids = inputIds.copyOf()
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { idTensor ->
                OnnxTensor.createTensor(env, hidden).use { hiddenTensor ->
                    decoder.run(mapOf("input_ids" to idTensor, "encoder_hidden_states" to hiddenTensor)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = (out[0].value as Array<Array<FloatArray>>)[0] // [t][vocab]
                        val last = logits[logits.size - 1]
                        var best = 0
                        var bestScore = Float.NEGATIVE_INFINITY
                        for (c in last.indices) {
                            if (last[c] > bestScore) {
                                bestScore = last[c]
                                best = c
                            }
                        }
                        val id = best.toLong()
                        if (id == EOS_TOKEN) {
                            finished = true
                        } else {
                            tokens.add(id)
                            inputIds = ids + id
                        }
                    }
                }
            }
        }
        return decodeTokens(tokens)
    }

    private fun decodeTokens(tokens: List<Long>): String {
        val sb = StringBuilder()
        for (id in tokens) {
            if (id < 0 || id >= vocab.size || id <= 4) continue // [PAD][UNK][CLS][SEP][MASK]
            val token = vocab[id.toInt()]
            sb.append(token.removePrefix("##"))
        }
        return sb.toString().trim()
    }

    /** 整块二次识别：裁剪块区域喂模型，文本替换为单行 */
    /**
     * 按行二次识别（对齐 mokuro）：每行单独裁剪识别，竖排列旋转 90° 变横排。
     * 逐行回填译文（修复旧实现整块识别只替换 lines.first()、丢弃其余行坐标的 bug）。
     */
    fun refine(
        blocks: List<OcrBlock>,
        bitmap: Bitmap,
    ): List<OcrBlock> =
        blocks.map { block ->
            val lines =
                block.lines.map { line ->
                    val crop = cropBmp(bitmap, line.left, line.top, line.right, line.bottom, 4)
                    val horizontal =
                        if (line.height > line.width * 1.2f) {
                            rotateToHorizontal(crop)
                        } else {
                            crop
                        }
                    val text = recognizeLine(horizontal)
                    if (text.isBlank()) line else line.copy(text = text)
                }
            block.copy(lines = lines)
        }

    /** 竖排（列）顺时针旋转 90° 成横排（mokuro 同款：ROTATE_90_CLOCKWISE） */
    private fun rotateToHorizontal(bmp: Bitmap): Bitmap {
        val m = android.graphics.Matrix()
        m.postRotate(90f)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}

// ---------- 模型下载（manga-ocr，hf-mirror 优先） ----------

object ModelDownloader {
    // dhleong/manga-ocr-android（Mihon 集成）的 ai_edge_torch full-int8 动态量化版：
    // 卷积被重写为 MatMulInteger（无 ConvInteger——onnx-community 的 int8/fp16
    // 在 onnxruntime-android 均无法加载：ConvInteger 无 CPU kernel、fp16 Cast 类型错）；
    // encoder 89MB + decoder 30MB，输入输出名与官方一致（按索引取输出）
    private val files =
        listOf(
            MangaOcrRecognizer.ENCODER to "https://hf-mirror.com/dhleong/manga-ocr-android/resolve/main/manga-ocr.converted.encoder.preprocessed.quant.onnx",
            MangaOcrRecognizer.DECODER to "https://hf-mirror.com/dhleong/manga-ocr-android/resolve/main/manga-ocr.converted.decoder.preprocessed.quant.onnx",
            MangaOcrRecognizer.VOCAB to "https://hf-mirror.com/kha-white/manga-ocr-base/resolve/main/vocab.txt",
        )

    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    /** 同步下载（调用方放 IO 协程），onProgress(0..1, 描述) */
    fun download(
        context: Context,
        onProgress: (Float, String) -> Unit,
    ): Result<Unit> {
        val dir = MangaOcrRecognizer.modelDir(context)
        if (!dir.mkdirs() && !dir.isDirectory) return Result.failure(IllegalStateException("创建目录失败"))
        cancelled.set(false)
        for ((idx, pair) in files.withIndex()) {
            val (name, url) = pair
            val dest = File(dir, name)
            if (dest.exists() && dest.length() > 10_000) continue
            val ok =
                runCatching {
                    fetch(url, dest, dir) { frac, desc -> onProgress((idx + frac) / files.size, "$name $desc") }
                }.recoverCatching {
                    if (url.contains("hf-mirror.com")) {
                        fetch(url.replace("hf-mirror.com", "huggingface.co"), dest, dir) { frac, desc ->
                            onProgress((idx + frac) / files.size, "$name（官方源）$desc")
                        }
                    } else {
                        throw it
                    }
                }
            if (ok.isFailure) return Result.failure(ok.exceptionOrNull() ?: IllegalStateException("下载失败 $name"))
        }
        onProgress(1f, "完成")
        return Result.success(Unit)
    }

    private fun fetch(
        url: String,
        dest: File,
        dir: File,
        onProgress: (Float, String) -> Unit,
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            error("HTTP $code")
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            val tmp = File(dir, "${dest.name}.part")
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var read = 0L
                while (true) {
                    if (cancelled.get()) {
                        tmp.delete()
                        conn.disconnect()
                        error("已取消")
                    }
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    if (total > 0) onProgress(read.toFloat() / total, "${read / 1048576}MB/${total / 1048576}MB")
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                error("保存失败")
            }
        }
        conn.disconnect()
    }
}
