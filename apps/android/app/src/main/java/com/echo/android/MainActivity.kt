package com.echo.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.echo.android.capture.ScreenCaptureService
import com.echo.android.ocr.OcrEngine
import com.echo.android.ocr.OcrLang
import com.echo.android.ocr.ocrEngine
import com.echo.android.translate.StubTranslationGateway
import com.echo.android.translate.UniffiTranslationGateway
import com.echo.android.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** 通知栏点击带来的待翻译图片（截图监听入口） */
    private val pendingUri = mutableStateOf<Uri?>(null)

    /** 进程级单例：截图监听随进程存活，不随 Activity 重建重复注册 */
    private var screenshotObserver: com.echo.android.observe.ScreenshotObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { pendingUri.value = it }

        if (screenshotObserver == null) {
            screenshotObserver =
                com.echo.android.observe
                    .ScreenshotObserver(this) { uri ->
                        runOnUiThread {
                            com.echo.android.notify.Notifications
                                .showScreenshot(this, uri)
                        }
                    }.also { it.register() }
        }

        setContent {
            com.echo.android.ui
                .EchoTheme { EchoApp(pendingUri = pendingUri.value) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { pendingUri.value = it }
    }
}

/** OCR 识别语言从设置读取（默认日语，覆盖漫画/游戏主场景） */
fun Context.ocrLang(): OcrLang {
    val name = getSharedPreferences("echo", Context.MODE_PRIVATE).getString("ocr_lang", OcrLang.Ja.name)
    return runCatching { OcrLang.valueOf(name!!) }.getOrNull() ?: OcrLang.Ja
}

@Composable
fun EchoApp(pendingUri: Uri? = null) {
    val context = LocalContext.current
    val gateway = remember { UniffiTranslationGateway.load(context) ?: StubTranslationGateway() }
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var allFilesGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())
    }

    // 通知栏入口的图片处理（OCR→翻译→发布到 CaptureStore 供主页预览）
    val processImage: (Uri) -> Unit = { uri ->
        busy = true
        error = null
        scope.launch {
            try {
                val image =
                    withContext(Dispatchers.IO) {
                        Images.decodeScaled(context, uri)
                    }

                // 小字辅助：小分辨率图放大后再识别，坐标映射回原图
                val ocrScale = if (minOf(image.width, image.height) < 1200) 2f else 1f
                val ocrBitmap =
                    if (ocrScale > 1f) {
                        Bitmap.createScaledBitmap(
                            image,
                            (image.width * ocrScale).toInt(),
                            (image.height * ocrScale).toInt(),
                            true,
                        )
                    } else {
                        image
                    }
                val rawBlocks = OcrEngine.recognize(ocrBitmap, context.ocrLang())
                val mapped = if (ocrScale > 1f) rawBlocks.map { it.scaledBy(1f / ocrScale) } else rawBlocks
                // 行级裁剪状态栏/导航栏 + 气泡聚类 + 二次识别（引擎档位见设置）
                val blocks =
                    com.echo.android.ocr.BlockMerge.merge(
                        com.echo.android.util.CropRegion
                            .fromPrefs(context)
                            .filterByLines(mapped, image.height),
                    )
                val finalBlocks =
                    com.echo.android.ocr.refineBlocks(
                        context,
                        blocks,
                        image,
                        context.ocrEngine(),
                    )
                val translations =
                    try {
                        gateway.translate(finalBlocks.map { it.text })
                    } catch (e: Exception) {
                        error = "翻译失败（已用原文占位）：${e.message}"
                        finalBlocks.map { it.text }
                    }
                com.echo.android.ui.CaptureStore
                    .publish(image, finalBlocks, translations)
            } catch (e: Exception) {
                error = "识别失败：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    // 读取兜底链的最后一级（文件路径直读）需要存储权限；Android 13+ 改用媒体权限，picker 流程不需要
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < 33) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                permLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 通知栏点击入口：带图片 uri 打开时自动翻译
    LaunchedEffect(pendingUri) {
        pendingUri?.let(processImage)
    }

    // 屏幕翻译悬浮球：投屏授权 → 前台服务
    // 按钮状态直接观察服务全局 state：悬浮球菜单里关闭服务也能即时同步
    val projectionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val service =
                    Intent(context, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_START
                        putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                        putExtra(ScreenCaptureService.EXTRA_LANG, context.ocrLang().name)
                    }
                context.startService(service)
            }
        }

    fun toggleCapture() {
        if (ScreenCaptureService.isRunning) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).apply { action = ScreenCaptureService.ACTION_STOP },
            )
        } else if (!Settings.canDrawOverlays(context)) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Echo 翻译", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) {
                    Text("设置")
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "翻译引擎：${gateway.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!allFilesGranted) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "部分系统截图需要「所有文件访问」权限才能读取",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    }) { Text("去设置") }
                    TextButton(onClick = {
                        allFilesGranted = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
                    }) { Text("刷新") }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { toggleCapture() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (ScreenCaptureService.runningState.value) "关闭屏幕翻译悬浮球" else "开启屏幕翻译悬浮球")
            }

            Spacer(Modifier.height(8.dp))
            // 识别结果独立页面：数据来源 = 悬浮球点按抓取的屏幕帧（CaptureStore）
            val count =
                com.echo.android.ui.CaptureStore.latest
                    ?.results
                    ?.size ?: 0
            OutlinedButton(
                onClick = { context.startActivity(Intent(context, ResultActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (count > 0) "查看识别结果（$count 条）" else "识别结果")
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
