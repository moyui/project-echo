package com.echo.android.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

object Images {

    /**
     * 解码图片并按最长边降采样（叠加渲染 + 取色 + OCR 共用同一份位图）。
     * 读取顺序（实测 ColorOS 的 root 属主截图会被 content provider 拒绝，路径直读反而稳定）：
     *  1) DocumentsContract id → MediaStore _data → 文件路径直读（需「所有文件访问」）
     *  2) contentResolver.openInputStream（常规 typed 路径）
     *  3) openFileDescriptor 原始 fd
     */
    fun decodeScaled(context: Context, uri: Uri, maxDim: Int = 4096): Bitmap {
        val resolver = context.contentResolver
        val displayName = diagnoseName(context, uri)
        val trace = StringBuilder()

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // 注意：只对流本身判空。inJustDecodeBounds 模式下 decodeStream 恒返回 null，
        // 不能把 decode 结果接在 use 后面用 elvis 判定（之前两轮"读取失败"都是这个误判）
        val boundsStream = openStream(context, uri, trace) ?: throw IllegalStateException(
            "所有读取方式均失败 [$displayName] allFiles=${Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()} trace=$trace uri=$uri",
        )
        boundsStream.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decodeStream = openStream(context, uri, trace)
            if (decodeStream != null) {
                decodeStream.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)?.let { return it }
                }
            }
        }

        // BitmapFactory 解不了（AVIF/动图等特殊格式），ImageDecoder 兜底
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(resolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                var sampleSize = 1
                while (info.size.width / sampleSize > maxDim || info.size.height / sampleSize > maxDim) sampleSize *= 2
                decoder.setTargetSampleSize(sampleSize)
                // 取色需要读像素，必须软件位图
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }

        throw IllegalStateException(
            "图片解码失败 [$displayName] bounds=${bounds.outWidth}x${bounds.outHeight} uri=$uri",
        )
    }

    private fun openStream(context: Context, uri: Uri, trace: StringBuilder): InputStream? {
        // 1) 文件路径直读
        trace.append("[P1 path:")
        try {
            val path = resolveFilePath(context, uri)
            trace.append("resolve=${path ?: "null"}")
            if (path != null) {
                val file = File(path)
                trace.append(" exists=${file.exists()} canRead=${file.canRead()}")
                if (file.exists() && file.canRead()) {
                    return file.inputStream()
                }
            }
        } catch (e: Exception) {
            trace.append(" exc=${e.message}")
        }
        trace.append("]")

        val resolver = context.contentResolver

        // 2) 常规 typed 流
        trace.append("[P2 typed:")
        try {
            resolver.openInputStream(uri)?.let {
                trace.append("ok]")
                return it
            }
            trace.append("null]")
        } catch (e: Exception) {
            trace.append("exc:${e.message}]")
        }

        // 3) 原始 fd
        trace.append("[P3 fd:")
        try {
            val pfd: ParcelFileDescriptor? = resolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                trace.append("ok]")
                return ParcelFileDescriptor.AutoCloseInputStream(pfd)
            }
            trace.append("null]")
        } catch (e: Exception) {
            trace.append("exc:${e.message}]")
        }
        return null
    }

    /** DocumentsUI 的 media 文档 uri（image:578460）→ MediaStore 实际文件路径 */
    private fun resolveFilePath(context: Context, uri: Uri): String? {
        // 注意：isDocumentUri 的 context 不能传 null，部分版本会直接返回 false
        if (!DocumentsContract.isDocumentUri(context, uri)) return null
        val docId = DocumentsContract.getDocumentId(uri)
        val type = docId.substringBefore(":", "")
        val id = docId.substringAfter(":", "")
        if (type != "image" || id.isEmpty()) return null
        return context.contentResolver.query(
            Uri.parse("content://media/external/images/media/$id"),
            arrayOf(android.provider.MediaStore.Images.Media.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun diagnoseName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else uri.lastPathSegment
        } ?: uri.lastPathSegment
    }.getOrNull() ?: "unknown"
}
