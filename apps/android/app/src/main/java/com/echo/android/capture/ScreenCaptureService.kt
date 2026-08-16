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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.echo.android.MainActivity
import com.echo.android.R
import com.echo.android.ocr.BlockMerge
import com.echo.android.ocr.MangaOcrRecognizer
import com.echo.android.ocr.OcrEngine
import com.echo.android.ocr.OcrEngineKind
import com.echo.android.ocr.OcrLang
import com.echo.android.ocr.PpOcrRecognizer
import com.echo.android.ocr.ocrEngine
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
 * 常驻可拖动小球 —— 点击 = 翻译当前屏幕（取帧前自动隐藏球避免入镜），再点 = 清除译文；关闭走 app 内开关。
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

        /** Compose 可观察的运行状态：服务启停（含悬浮球菜单里关闭）即时同步到主页按钮 */
        val runningState = androidx.compose.runtime.mutableStateOf(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayView: OverlayView? = null
    private var ballView: android.view.View? = null

    // 交替用备用球窗口：重建（展开/收起）时它先以新几何上屏、首帧绘制完成后再销毁
    // 旧窗口，全程无"窗口销毁→新窗口首帧"之间的球消失空窗（闪烁根源）
    private var spareBallView: android.view.View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var ballLifecycle: BallLifecycleOwner? = null
    private var snapAnimator: android.animation.ValueAnimator? = null
    private val ballText = androidx.compose.runtime.mutableStateOf("译")
    private val ballDim = androidx.compose.runtime.mutableStateOf(false)
    private val ballHasDot = androidx.compose.runtime.mutableStateOf(false)
    private val menuOpen = androidx.compose.runtime.mutableStateOf(false)
    private val menuAtLeft = androidx.compose.runtime.mutableStateOf(false)
    private val ballPressed = androidx.compose.runtime.mutableStateOf(false)
    private val ballLongFired = androidx.compose.runtime.mutableStateOf(false)
    private val menuHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val menuTimeoutRunnable = Runnable { dismissMenu() }

    // 菜单展开前的窗口位置（收起时恢复）
    private var ballOriginX = 0
    private var ballSizePx = 146
    private var gateway: TranslationGateway? = null
    private var lang: OcrLang = OcrLang.Ja

    private var captureWidth = 0
    private var captureHeight = 0
    private var cropRegion =
        com.echo.android.util
            .CropRegion(0, 0)

    @Volatile
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                android.util.Log.d("EchoBall", "收到 ACTION_STOP")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }
                lang = intent
                    .getStringExtra(EXTRA_LANG)
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
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat
                .Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Echo 悬浮球已开启")
                .setContentText("点击小球翻译屏幕，再点清除；拖动可移动")
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
    private fun startCapture(
        resultCode: Int,
        data: Intent,
    ) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        projection =
            projectionManager.getMediaProjection(resultCode, data).also { proj ->
                proj.registerCallback(
                    object : MediaProjection.Callback() {
                        override fun onStop() {
                            android.util.Log.d("EchoBall", "MediaProjection onStop → 关服务")
                            stopSelf()
                        }
                    },
                    null,
                )
            }

        val metrics = android.util.DisplayMetrics()
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels

        imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay =
            projection?.createVirtualDisplay(
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
        addOverlayView()

        // 展示固定为底部对照面板、字号缩放与裁剪区域
        val prefs = getSharedPreferences("echo", MODE_PRIVATE)
        overlayView?.fontScale = prefs.getString("font_scale", "1.0")?.toFloatOrNull() ?: 1f
        overlayView?.scrimAlpha = prefs.getString("scrim_alpha", "0.75")?.toFloatOrNull() ?: 0.75f
        cropRegion =
            com.echo.android.util.CropRegion
                .fromPrefs(this)
        addBallView()

        isRunning = true
        runningState.value = true
        warmUpOcr()
    }

    /**
     * 预热 OCR：首次翻译慢的根因是冷启动一次性初始化——
     * ML Kit bundled 模型首次 process 才加载（1~3s）、PP-OCRv5/manga-ocr 首次建
     * ONNX session（读模型文件 + 解析，1~2s）。服务启动时后台做完，第一次点球就快。
     */
    private fun warmUpOcr() {
        scope.launch {
            runCatching {
                when (ocrEngine()) {
                    OcrEngineKind.PPOCR -> PpOcrRecognizer.get(this@ScreenCaptureService)
                    OcrEngineKind.MANGA -> MangaOcrRecognizer.getOrNull(this@ScreenCaptureService)
                    OcrEngineKind.MLKIT -> Unit
                }
                // 定位引擎恒为 ML Kit（PPOCR 档的竖排也走 ML Kit），小位图触发模型加载；
                // 过小的图（<16px）ML Kit 会直接报错，64px 纯色图足够触发初始化
                val tiny = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                OcrEngine.recognize(tiny, lang)
            }.onFailure { e ->
                android.util.Log.w("EchoBall", "OCR 预热失败（不影响使用）: ${e.message}")
            }
        }
    }

    private fun addOverlayView() {
        val wm = getSystemService(WindowManager::class.java)
        overlayView = OverlayView(this)
        val params =
            WindowManager.LayoutParams(
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
        ballSizePx = ballSize

        val point = Point()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(point)

        // Compose 球：Service context 没有生命周期树，手动挂 LifecycleOwner。
        // 两个实例交替（见 rebuildBallWindow）：展开/收起时备用窗口先上屏再销毁旧窗口，
        // 避免 ColorOS 上重建窗口的"球消失一帧"闪烁。二者组合同一组状态，内容恒同步。
        val lifecycleOwner = BallLifecycleOwner().also { ballLifecycle = it }

        fun newBallView() =
            androidx.compose.ui.platform.ComposeView(this).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)
                setViewCompositionStrategy(
                    androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow,
                )
                // 手势在 View 层处理（rawX/rawY 屏幕绝对坐标）：Compose 的本地坐标差在窗口随拖动
                // 移动时被抵消（拖不跟手）；长按用 Handler 定时（静止时 Compose 事件流无事件可等）
                setOnTouchListener { _, e -> handleBallTouch(e, wm, point, ballSize) }
                setContent {
                    com.echo.android.ui.EchoTheme {
                        FloatBall(
                            text = ballText.value,
                            dimmed = ballDim.value,
                            hasContent = ballHasDot.value,
                            menuOpen = menuOpen.value,
                            menuAtLeft = menuAtLeft.value,
                            pressed = ballPressed.value,
                            longFired = ballLongFired.value,
                            onClear = {
                                clearTranslations()
                                dismissMenu()
                            },
                            onResults = {
                                dismissMenu()
                                runCatching {
                                    startActivity(
                                        android.content
                                            .Intent(this@ScreenCaptureService, com.echo.android.ResultActivity::class.java)
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                            onCloseService = {
                                android.util.Log.d("EchoBall", "菜单：关闭悬浮球")
                                dismissMenu()
                                stopSelf()
                            },
                        )
                    }
                }
            }

        val prefs = getSharedPreferences("echo", MODE_PRIVATE)
        ballParams =
            WindowManager
                .LayoutParams(
                    ballSize,
                    ballSize,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    android.graphics.PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    // 禁用窗口动画：展开/收起 resize 时系统的 move+resize 动画会把尺寸和位置
                    // 分开插值，中间帧窗口停在原位但已缩窄，球（TopEnd 锚定）被画在窗口左部
                    // （屏幕中间），再随位置动画滑回右缘——视觉上"先弹到中间再滑回边缘"。
                    windowAnimations = 0
                    // overlay 窗口默认会被系统栏 inset 下移（状态栏 80px），
                    // 导致 mAttrs 坐标与实际渲染位置错位、点击打空，禁用 inset 适配
                    if (Build.VERSION.SDK_INT >= 30) {
                        setFitInsetsTypes(0)
                    }
                    // 记忆上次拖放位置（越界则回退默认）
                    val savedX = prefs.getInt("ball_x", -1)
                    val savedY = prefs.getInt("ball_y", -1)
                    if (savedX in 0 until point.x && savedY in 0 until point.y) {
                        x = savedX.coerceAtMost(point.x - ballSize)
                        y = savedY.coerceAtMost(point.y - ballSize)
                    } else {
                        x = point.x - ballSize - (16 * density).toInt()
                        y = point.y / 3
                    }
                }

        ballView = newBallView()
        spareBallView = newBallView()
        // 备用窗口平时不 attach，仅重建切换时由 rebuildBallWindow 先上屏
        wm.addView(ballView!!, ballParams!!)
    }

    /** 球窗口位置轨迹日志：打印窗口几何 + Compose 锚定，用于排查展开/收起/拖动时的弹跳 */
    private fun logBallPos(source: String) {
        val p = ballParams ?: return
        val ballLeft = if (menuAtLeft.value) p.x + p.width - ballSizePx else p.x
        android.util.Log.d(
            "EchoBallPos",
            "$source x=${p.x} y=${p.y} w=${p.width} h=${p.height} " +
                "menuAtLeft=${menuAtLeft.value} menuOpen=${menuOpen.value} ballLeft=$ballLeft",
        )
    }

    /** 松手边缘吸附：滑到较近的一侧并记忆位置；留出 50px 边距避开系统边缘手势热区 */
    private fun snapBallToEdge(
        wm: WindowManager,
        point: Point,
        ballSize: Int,
    ) {
        val params = ballParams ?: return
        val view = ballView ?: return
        // 吸附目标按球大小计算（target = 屏幕宽 - 球 - 边距），展开窗口会把它甩出屏幕；
        // 展开态拖动已被手势层禁止，这里双保险
        if (params.width != ballSize) return
        val edgeGap = 50
        // 球可能贴在窗口右缘（菜单展开/收缩期间窗口比球宽，params.x 是窗口左缘），
        // 吸附方向必须按球的实际屏幕中心判断，否则窗口左移后必然误判吸左
        val ballCenter = params.x + params.width - ballSize / 2
        val target = if (ballCenter < point.x / 2) edgeGap else point.x - ballSize - edgeGap
        snapAnimator?.cancel()
        snapAnimator =
            android.animation.ValueAnimator.ofInt(params.x, target).apply {
                duration = 180
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { anim ->
                    params.x = anim.animatedValue as Int
                    runCatching { wm.updateViewLayout(view, params) }
                    logBallPos("snap")
                }
                addListener(
                    object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            getSharedPreferences("echo", MODE_PRIVATE)
                                .edit()
                                .putInt("ball_x", params.x)
                                .putInt("ball_y", params.y)
                                .apply()
                        }
                    },
                )
                start()
            }
    }

    /** View 层完整手势：tap=翻译 / 长按(Handler 400ms)=菜单 / 拖动(rawX 绝对坐标)=移动 */
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchParamX = 0
    private var touchParamY = 0
    private var touchDragged = false
    private var touchLongFired = false

    // 菜单展开时点击了窗口空白区：本次手势整段吞掉（收起菜单即可，不触发 tap/拖动/吸附）
    private var touchIgnored = false

    // 窗口重建时刻：重建后 300ms 内的触摸是旧窗口销毁时输入系统重定向来的
    // 幽灵事件（无对应 UP），吞掉避免 pressed 卡在按下态
    private var lastRebuildAt = 0L
    private val longPressRunnable =
        Runnable {
            touchLongFired = true
            ballLongFired.value = true
            onBallLongPressed()
        }

    private fun handleBallTouch(
        e: MotionEvent,
        wm: WindowManager,
        point: Point,
        ballSize: Int,
    ): Boolean {
        val params = ballParams ?: return false
        val view = ballView ?: return false
        val slopPx = 8f * resources.displayMetrics.density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 重建后 300ms 内的幽灵触摸（旧窗口销毁时输入系统重定向的事件流）：
                // 整段吞掉，防止 pressed 被置 true 后没有 UP 复位
                if (SystemClock.uptimeMillis() - lastRebuildAt < 300) {
                    touchIgnored = true
                    return true
                }
                logBallPos("down")
                // 窗口处于展开态（含菜单刚收起、窗口尚未缩回的 1 帧）不接收拖动手势：
                // 按菜单外区域 = 收起菜单；宽窗口期间拖动会把窗口左缘当球坐标、松手吸附时甩出屏幕。
                // 用窗口实际尺寸判断而非 menuOpen——menuOpen 已置 false 时窗口可能还没缩回。
                if (params.width > ballSizePx) {
                    touchIgnored = true
                    if (menuOpen.value) dismissMenu()
                    return true
                }
                touchIgnored = false
                touchDownX = e.rawX
                touchDownY = e.rawY
                touchParamX = params.x
                touchParamY = params.y
                touchDragged = false
                touchLongFired = false
                ballPressed.value = true
                menuHandler.removeCallbacks(longPressRunnable)
                menuHandler.postDelayed(longPressRunnable, 400)
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchIgnored || touchLongFired) return true // 长按已触发（菜单展开），忽略余下移动
                val dx = e.rawX - touchDownX
                val dy = e.rawY - touchDownY
                if (!touchDragged && (kotlin.math.abs(dx) > slopPx || kotlin.math.abs(dy) > slopPx)) {
                    touchDragged = true
                    menuHandler.removeCallbacks(longPressRunnable)
                }
                if (touchDragged) {
                    params.x = (touchParamX + dx.toInt()).coerceIn(0, (point.x - params.width).coerceAtLeast(0))
                    params.y = (touchParamY + dy.toInt()).coerceIn(0, (point.y - params.height).coerceAtLeast(0))
                    runCatching { wm.updateViewLayout(view, params) }
                    logBallPos("drag")
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                menuHandler.removeCallbacks(longPressRunnable)
                if (touchIgnored) {
                    touchIgnored = false
                    ballPressed.value = false
                    return true
                }
                ballPressed.value = false
                ballLongFired.value = false
                val wasCancelled = e.actionMasked == MotionEvent.ACTION_CANCEL
                val wasLong = touchLongFired
                touchLongFired = false
                if (wasCancelled) return true
                when {
                    wasLong -> Unit // 长按已处理
                    touchDragged -> snapBallToEdge(wm, point, ballSize)
                    else -> onBallTapped()
                }
            }
        }
        return true
    }

    private fun onBallTapped() {
        android.util.Log.d("EchoBall", "球被点击 busy=$busy")
        // 单击永远翻译当前屏（覆盖旧译文），清除走长按菜单
        if (busy) return
        scope.launch { translateNow() }
    }

    /** 长按 = 操作菜单（清除译文 / 识别结果 / 关闭悬浮球），再长按收起 */
    private fun onBallLongPressed() {
        if (busy) return
        if (menuOpen.value) {
            dismissMenu()
            return
        }
        showMenu()
    }

    private fun clearTranslations() {
        overlayView?.post { overlayView?.update(emptyList(), captureWidth, captureHeight) }
        ballHasDot.value = false
        com.echo.android.ui.CaptureStore
            .clear()
    }

    /**
     * 重建球窗口（展开/收起换几何）。ColorOS 对 updateViewLayout 的位置变化做 move 动画
     * （windowAnimations=0 禁不掉），只能销毁 Surface 按新几何重建；但销毁到新窗口首帧
     * 之间球会消失一帧（闪烁）。这里用双窗口交替：备用窗口先以新几何上屏（透明 Surface
     * 透出旧窗口内容，不可见），等它首帧绘制完成再销毁旧窗口——切换全程无空窗。
     */
    private fun rebuildBallWindow(
        wm: WindowManager,
        params: WindowManager.LayoutParams,
    ) {
        val old = ballView ?: return
        val spare = spareBallView ?: return
        runCatching { wm.addView(spare, params) }
            .onFailure { e ->
                android.util.Log.e("EchoBall", "rebuild addView(spare): $e")
                // 备用窗口不可用（如仍 attach）→ 回退旧路径：销毁后重建，无交替
                runCatching { wm.removeViewImmediate(old) }
                    .onFailure { android.util.Log.e("EchoBall", "rebuild removeViewImmediate: $it") }
                runCatching { wm.addView(old, params) }
                    .onFailure { android.util.Log.e("EchoBall", "rebuild addView(old): $it") }
                lastRebuildAt = SystemClock.uptimeMillis()
                return
            }
        // 备用窗口首帧绘制完成后切换：此时其内容已就绪，销毁旧窗口无任何空窗
        val observer =
            object : android.view.ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    // onDraw 回调期间禁止修改 observer，post 到下一帧再切换——
                    // 此时备用窗口绘制已完成（本回调即绘制完成的信号）
                    spare.post {
                        spare.viewTreeObserver.removeOnDrawListener(this)
                        swapBallViews(wm, old, spare)
                    }
                }
            }
        spare.viewTreeObserver.addOnDrawListener(observer)
        // 兜底：OnDrawListener 未触发（备用窗口未绘制）时 150ms 后强行切换
        spare.postDelayed({
            if (ballView === old && spare.isAttachedToWindow) {
                spare.viewTreeObserver.removeOnDrawListener(observer)
                swapBallViews(wm, old, spare)
            }
        }, 150)
    }

    private fun swapBallViews(
        wm: WindowManager,
        old: View,
        spare: View,
    ) {
        // ColorOS 在 addView 新 overlay 窗口时会自动移除旧窗口，此时 remove 失败是预期
        runCatching { wm.removeViewImmediate(old) }
            .onFailure { android.util.Log.d("EchoBall", "rebuild 旧窗口已被系统移除: $it") }
        ballView = spare
        spareBallView = old
        // 旧窗口销毁时输入系统会把悬空触摸流重定向给新窗口（幽灵 DOWN，见 handleBallTouch）
        lastRebuildAt = SystemClock.uptimeMillis()
    }

    /**
     * 展开菜单 = 扩大球窗口（菜单与球同一 overlay 窗口，无第二窗口，规避
     * ColorOS 多 overlay 窗口下触摸路由漂移）。
     */
    private fun showMenu() {
        val wm = getSystemService(WindowManager::class.java)
        val view = ballView ?: return
        val params = ballParams ?: return
        val density = resources.displayMetrics.density
        val point = Point()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(point)

        // 窗口尺寸与 Compose 布局严格同源（全 dp）：球(52dp) + 间隙(12dp) + 菜单(172dp) = 236dp 宽
        val expandedW = (236 * density).toInt()
        val expandedH = (168 * density).toInt()

        ballOriginX = params.x
        // 按屏幕空间选展开方向：右侧放得下→菜单在球右（球贴窗口左缘）；
        // 放不下→菜单在球左（窗口左移，球靠窗口右缘补偿，屏幕位置不变）
        val fitsRight = ballOriginX + expandedW <= point.x
        // 先置 Compose 状态，后改窗口几何（post 到下一帧，与重组同帧生效）：
        // 顺序反了会出现中间帧——窗口已左移加宽、Compose 还按旧布局把球画在窗口左缘，
        // 球屏幕位置瞬跳一帧再弹回。窗口仍是球大小时球无论 TopStart/TopEnd 都占满窗口，
        // 先置位不产生任何位移；之后无论谁先生效都不会出现错误帧。
        menuAtLeft.value = !fitsRight
        menuOpen.value = true
        view.post {
            // 重建窗口而不是 updateViewLayout：ColorOS 对位置变化会做 move 动画
            // （windowAnimations=0 禁不掉），销毁 Surface 后按新几何重建，系统
            // 没有任何中间帧可插值——展开/收起都是"直接出现/消失"，无弹跳。
            // 长按触发的 UP 事件会随旧窗口销毁而丢失，先复位触摸状态。
            ballPressed.value = false
            ballLongFired.value = false
            touchLongFired = false
            params.width = expandedW
            params.height = expandedH
            // 窗口左移时球靠窗口右缘补偿，屏幕位置不变
            params.x = if (fitsRight) ballOriginX else (ballOriginX + ballSizePx - expandedW).coerceAtLeast(0)
            params.y = params.y.coerceIn(0, (point.y - expandedH).coerceAtLeast(0))
            // 重建窗口而不是 updateViewLayout：ColorOS 对位置变化会做 move 动画
            // （windowAnimations=0 禁不掉），销毁 Surface 后按新几何重建，系统
            // 没有任何中间帧可插值——展开/收起都是"直接出现/消失"，无弹跳。
            // 双窗口交替（rebuildBallWindow）消除重建瞬间的"球消失一帧"闪烁。
            rebuildBallWindow(wm, params)
            logBallPos("show")
        }

        menuHandler.removeCallbacks(menuTimeoutRunnable)
        menuHandler.postDelayed(menuTimeoutRunnable, 6000)
    }

    /** 收起菜单 = 窗口缩回球大小（post 下一帧，与 Compose 移除菜单同帧生效） */
    private fun dismissMenu() {
        menuHandler.removeCallbacks(menuTimeoutRunnable)
        if (!menuOpen.value) return
        menuOpen.value = false
        val wm = getSystemService(WindowManager::class.java)
        val view = ballView
        val params = ballParams
        // 立即缩回：菜单项无退出动画，180ms 延迟只留下窗口仍宽的空窗期——
        // 期间拖动会被 snapBallToEdge 按窗口左缘吸附、球甩出屏幕
        view?.post {
            if (view != null && params != null) {
                // 长按收起时 UP 事件随旧窗口销毁丢失，复位触摸状态避免球卡在缩小态
                ballPressed.value = false
                ballLongFired.value = false
                touchLongFired = false
                params.width = ballSizePx
                params.height = ballSizePx
                params.x = ballOriginX
                // 重建窗口（见 showMenu）：双窗口交替消除"球消失一帧"闪烁
                rebuildBallWindow(wm, params)
                logBallPos("dismiss")
            }
        }
    }

    private suspend fun translateNow() {
        busy = true
        val ball = ballView
        try {
            // 1) 隐藏球取帧（只有这一小段需要藏）
            ball?.post { ball.visibility = View.GONE }
            overlayView?.post { overlayView?.update(emptyList(), captureWidth, captureHeight) }
            ballHasDot.value = false
            // 虚拟屏只在画面变化时产帧：球的隐藏本身就是一次变化，等它渲染出来
            delay(450)

            val reader =
                imageReader ?: run {
                    android.util.Log.d("EchoBall", "无 ImageReader")
                    return
                }
            var image =
                try {
                    reader.acquireLatestImage()
                } catch (_: Exception) {
                    null
                }
            if (image == null) {
                delay(600)
                image =
                    try {
                        reader.acquireLatestImage()
                    } catch (_: Exception) {
                        null
                    }
            }
            if (image == null) {
                android.util.Log.d("EchoBall", "取帧失败（无新帧）")
                return
            }
            val bitmap =
                try {
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
            android.util.Log.d("EchoBall", "取帧完成 ${bitmap.width}x${bitmap.height}")

            // 2) 帧已到手，球立即回来并进入忙碌态（OCR/翻译阶段可能十几秒）
            ball?.post { ball.visibility = View.VISIBLE }
            ballText.value = "…"
            ballDim.value = true

            // 小字辅助：小分辨率屏放大后再识别，坐标映射回原尺寸
            val ocrScale = if (minOf(bitmap.width, bitmap.height) < 1200) 2f else 1f
            val ocrBitmap =
                if (ocrScale > 1f) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * ocrScale).toInt(),
                        (bitmap.height * ocrScale).toInt(),
                        true,
                    )
                } else {
                    bitmap
                }
            val rawBlocks =
                try {
                    OcrEngine.recognize(ocrBitmap, lang)
                } catch (_: Exception) {
                    emptyList()
                }
            val blocks = if (ocrScale > 1f) rawBlocks.map { it.scaledBy(1f / ocrScale) } else rawBlocks
            android.util.Log.d("EchoBall", "OCR 完成 ${blocks.size} 块")
            // 行级裁剪状态栏/导航栏 + 气泡聚类（一个气泡一个译文框）
            val cropped =
                BlockMerge.merge(
                    cropRegion.filterByLines(blocks, captureHeight),
                )
            if (cropped.isEmpty()) {
                android.util.Log.d("EchoBall", "OCR 无结果（裁剪后）")
                return
            }
            // 顺序排障日志：聚类后块的坐标、方向与阅读顺序（v 竖排 / h 横排）
            cropped.forEachIndexed { i, b ->
                val dir = if (b.isVertical) "v" else "h"
                android.util.Log.d(
                    "EchoBallOrder",
                    "顺序[$i] $dir l=${b.left} t=${b.top} r=${b.right} btm=${b.bottom} w=${b.width} h=${b.height}",
                )
            }
            // 二次识别（引擎档位来自设置：ML Kit / PP-OCRv5 / manga-ocr），失败自动回退
            android.util.Log.d("EchoBall", "二次识别开始 engine=${ocrEngine()}")
            val finalBlocks =
                com.echo.android.ocr
                    .refineBlocks(this, cropped, bitmap, ocrEngine())
            android.util.Log.d("EchoBall", "二次识别完成 ${finalBlocks.size} 块")

            val g =
                gateway ?: run {
                    android.util.Log.d("EchoBall", "网关未加载")
                    return
                }
            val translations =
                try {
                    val r = g.translate(finalBlocks.map { it.text })
                    android.util.Log.d("EchoBall", "翻译完成 ${r.size} 条")
                    r
                } catch (e: Exception) {
                    android.util.Log.d("EchoBall", "翻译失败: ${e.message}")
                    null
                } ?: return
            val items =
                finalBlocks.mapIndexedNotNull { index, block ->
                    val text = translations.getOrNull(index)?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                    OverlayView.Item(block, text, TextPalette.sample(bitmap, block))
                }
            overlayView?.post { overlayView?.update(items, captureWidth, captureHeight) }
            ballHasDot.value = items.isNotEmpty()
            // 同步发布到主页预览（图片叠加 + 逐条对照列表）
            com.echo.android.ui.CaptureStore
                .publish(bitmap, finalBlocks, translations)
        } finally {
            // 3) 无论如何球都恢复常驻
            ball?.post { ball.visibility = View.VISIBLE }
            ballText.value = "译"
            ballDim.value = false
            busy = false
        }
    }

    override fun onDestroy() {
        isRunning = false
        runningState.value = false
        scope.cancel()
        menuHandler.removeCallbacks(longPressRunnable)
        menuHandler.removeCallbacks(menuTimeoutRunnable)
        snapAnimator?.cancel()
        val wm = getSystemService(WindowManager::class.java)
        overlayView?.let { runCatching { wm.removeView(it) } }
        overlayView = null
        ballView?.let { runCatching { wm.removeView(it) } }
        ballView = null
        spareBallView?.let { runCatching { wm.removeView(it) } }
        spareBallView = null
        ballLifecycle?.destroy()
        ballLifecycle = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}

/**
 * Compose 悬浮球的生命周期桥：Service context 没有 ViewTreeLifecycleOwner，
 * 手动驱动 Lifecycle + SavedStateRegistry 让 ComposeView 可在 WindowManager 里渲染。
 */
private class BallLifecycleOwner :
    androidx.lifecycle.LifecycleOwner,
    androidx.savedstate.SavedStateRegistryOwner {
    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateController = androidx.savedstate.SavedStateRegistryController.create(this)

    override val lifecycle: androidx.lifecycle.Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        runCatching {
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
        }
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
