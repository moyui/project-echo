package com.echo.android.observe

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Images.Media

/**
 * 监听相册新增截图（路径含 Screenshots）。
 * 新版 SDK 的带参 onChange 均为 final，这里用基类回调 + 反查最新截图的方式。
 * 注意：观察者随进程存活——进程被系统杀掉就收不到事件，M2 前台服务化后补齐。
 */
class ScreenshotObserver(
    private val context: Context,
    private val onScreenshot: (Uri) -> Unit,
) : ContentObserver(null) {
    private var lastId = -1L
    private var lastIdAt = 0L

    fun register() {
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            this,
        )
        android.util.Log.d("EchoObserver", "已注册截图监听")
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
    }

    override fun onChange(selfChange: Boolean) {
        android.util.Log.d("EchoObserver", "onChange 触发")
        val (id, uri) =
            queryLatestScreenshot() ?: run {
                android.util.Log.d("EchoObserver", "反查最新截图失败")
                return
            }
        android.util.Log.d("EchoObserver", "最新截图 id=$id")
        val now = System.currentTimeMillis()
        // 同一张图的入库流程会触发多次 onChange，防抖
        if (id == lastId && now - lastIdAt < 3000) return
        lastId = id
        lastIdAt = now
        onScreenshot(uri)
    }

    /** 最近 30 秒内入库的最新截图 */
    private fun queryLatestScreenshot(): Pair<Long, Uri>? =
        runCatching {
            val cutoff = System.currentTimeMillis() / 1000 - 30
            context.contentResolver
                .query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(Media._ID, Media.DATA, Media.DATE_ADDED),
                    "${Media.DATA} LIKE ? AND ${Media.DATE_ADDED} >= ?",
                    arrayOf("%Screenshots%", cutoff.toString()),
                    "${Media.DATE_ADDED} DESC",
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val id = cursor.getLong(0)
                    val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    id to uri
                }
        }.getOrNull()
}
