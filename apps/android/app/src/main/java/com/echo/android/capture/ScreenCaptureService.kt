package com.echo.android.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.echo.android.MainActivity
import com.echo.android.R
import com.echo.android.ocr.OcrEngine
import com.echo.android.ocr.OcrLang
import com.echo.android.palette.TextPalette
import com.echo.android.translate.TranslationGateway
import com.echo.android.translate.UniffiTranslationGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 屏幕翻译悬浮球服务：
 * 常驻可拖动小球 —— 点击 = 翻译当前屏幕（取帧前自动隐藏球避免入镜），再点 = 清除译文，长按 = 关闭。
 * MediaProjection 授权一次后保持，按需单帧捕获，无持续扫描。
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.echo.android.capture.START"
        const val ACTION_STOP = "com.echo.android.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_LANG = "lang"

        @Volatile
        var isRunning = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayView: OverlayView? = null
    private var ballView: TextView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var gateway: TranslationGateway? = null
    private var lang: OcrLang = OcrLang.Ja

    private var captureWidth = 0
    private var captureHeight = 0
    private var cropRegion = com.echo.android.util.CropRegion(0, 0)

    @Volatile
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                lang = intent.getStringExtra(EXTRA_LANG)
                    ?.let { runCatching { OcrLang.valueOf(it) }.getOrNull() }
                    ?: OcrLang.Ja
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground()
                startCapture(resultCode, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val channelId = "echo_capture"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "屏幕翻译悬浮球", NotificationManager.IMPORTANCE_LOW),
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Echo 悬浮球已开启")
            .setContentText("点击小球翻译屏幕，长按关闭")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun startCapture(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        projection = projectionManager.getMediaProjection(resultCode, data).also { proj ->
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, null)
        }

        val metrics = android.util.DisplayMetrics()
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "echo-capture",
            captureWidth,
            captureHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            null,
        )

        gateway = UniffiTranslationGateway.load(this)
        // 展示方式（下方对照/原位覆盖）与裁剪区域（状态栏/导航栏）
        val prefs = getSharedPreferences("echo", MODE_PRIVATE)
        overlayView?.displayBelow = prefs.getString("display_mode", "below") != "cover"
        cropRegion = com.echo.android.util.CropRegion.fromPrefs(this)
        addOverlayView()
        addBallView()

        isRunning = true
    }

    private fun addOverlayView() {
        val wm = getSystemService(WindowManager::class.java)
        overlayView = OverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        )
        wm.addView(overlayView, params)
    }

    private fun addBallView() {
        val wm = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density
        val ballSize = (52 * density).toInt()

        ballView = TextView(this).apply {
            text = "译"
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE62F6FED.toInt())
            }
        }

        val point = Point()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(point)
        ballParams = WindowManager.LayoutParams(
            ballSize,
            ballSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = point.x - ballSize - (16 * density).toInt()
            y = point.y / 3
        }

        var downX = 0f
        var downY = 0f
        var paramX = 0
        var paramY = 0
        var dragged = false
        var longPressed = false
        val touchSlop = 8 * density

        // 触摸监听消费了事件，系统长按回调不会触发，这里自己实现长按关服务
        val longPressRunnable = Runnable {
            longPressed = true
            stopSelf()
        }

        ballView?.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    paramX = ballParams!!.x
                    paramY = ballParams!!.y
                    dragged = false
                    longPressed = false
                    view.postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragged && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        dragged = true
                        view.removeCallbacks(longPressRunnable)
                    }
                    if (dragged) {
                        ballParams!!.x = paramX + dx.toInt()
                        ballParams!!.y = paramY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, ballParams) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPressRunnable)
                    if (!dragged && !longPressed && event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        ballView?.setOnClickListener { onBallTapped() }

        wm.addView(ballView, ballParams)
    }

    private fun onBallTapped() {
        val overlay = overlayView ?: return
        if (overlay.hasContent) {
            // 再点一次：清除译文
            overlay.update(emptyList(), captureWidth, captureHeight)
            return
        }
        if (busy) return
        scope.launch { translateNow() }
    }

    private suspend fun translateNow() {
        busy = true
        val ball = ballView
        try {
            // 1) 隐藏球取帧（只有这一小段需要藏）
            ball?.post { ball.visibility = View.GONE }
            overlayView?.post { overlayView?.update(emptyList(), captureWidth, captureHeight) }
            // 虚拟屏只在画面变化时产帧：球的隐藏本身就是一次变化，等它渲染出来
            delay(450)

            val reader = imageReader ?: run {
                android.util.Log.d("EchoBall", "无 ImageReader")
                return
            }
            var image = try {
                reader.acquireLatestImage()
            } catch (_: Exception) {
                null
            }
            if (image == null) {
                delay(600)
                image = try {
                    reader.acquireLatestImage()
                } catch (_: Exception) {
                    null
                }
            }
            if (image == null) {
                android.util.Log.d("EchoBall", "取帧失败（无新帧）")
                return
            }
            val bitmap = try {
                image.toBitmap()
            } catch (_: Exception) {
                null
            } finally {
                image.close()
            }
            if (bitmap == null) {
                android.util.Log.d("EchoBall", "帧转 Bitmap 失败")
                return
            }

            // 2) 帧已到手，球立即回来并进入忙碌态（OCR/翻译阶段可能十几秒）
            ball?.post {
                ball.visibility = View.VISIBLE
                ball.text = "…"
                ball.alpha = 0.6f
            }

            // 小字辅助：小分辨率屏放大后再识别，坐标映射回原尺寸
            val ocrScale = if (minOf(bitmap.width, bitmap.height) < 1200) 2f else 1f
            val ocrBitmap = if (ocrScale > 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ocrScale).toInt(),
                    (bitmap.height * ocrScale).toInt(),
                    true,
                )
            } else {
                bitmap
            }
            val rawBlocks = try {
                OcrEngine.recognize(ocrBitmap, lang)
            } catch (_: Exception) {
                emptyList()
            }
            val blocks = if (ocrScale > 1f) rawBlocks.map { it.scaledBy(1f / ocrScale) } else rawBlocks
            // 裁剪状态栏/导航栏（时间、电量等不再进入翻译，也省 token）
            val cropped = cropRegion.filter(blocks, captureHeight)
            if (cropped.isEmpty()) {
                android.util.Log.d("EchoBall", "OCR 无结果（裁剪后）")
                return
            }

            val g = gateway ?: run {
                android.util.Log.d("EchoBall", "网关未加载")
                return
            }
            val translations = try {
                g.translate(cropped.map { it.text })
            } catch (e: Exception) {
                android.util.Log.d("EchoBall", "翻译失败: ${e.message}")
                null
            } ?: return
            val items = cropped.mapIndexedNotNull { index, block ->
                val text = translations.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                OverlayView.Item(block, text, TextPalette.sample(bitmap, block))
            }
            overlayView?.post { overlayView?.update(items, captureWidth, captureHeight) }
        } finally {
            // 3) 无论如何球都恢复常驻
            ball?.post {
                ball.visibility = View.VISIBLE
                ball.text = "译"
                ball.alpha = 1f
            }
            busy = false
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        val wm = getSystemService(WindowManager::class.java)
        overlayView?.let { runCatching { wm.removeView(it) } }
        overlayView = null
        ballView?.let { runCatching { wm.removeView(it) } }
        ballView = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}

/** RGBA_8888 的 Image → Bitmap（处理行对齐填充） */
private fun Image.toBitmap(): Bitmap? {
    val plane = planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width
    val full = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
    buffer.rewind()
    full.copyPixelsFromBuffer(buffer)
    return if (rowPadding == 0) full else Bitmap.createBitmap(full, 0, 0, width, height)
}
