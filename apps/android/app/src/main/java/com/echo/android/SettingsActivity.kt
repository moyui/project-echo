package com.echo.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.echo.android.ocr.OcrLang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.echo.EchoTranslator
import java.io.File

/**
 * 翻译引擎设置：provider 切换（Mock/DeepL/百度/有道/LLM）+ 各自配置 + OCR 识别语言。
 * 各 provider 的配置独立保存，切换不丢失；保存到内部私有目录（外部 adb 推送的配置优先级更高）。
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen() }
    }
}

private const val CONFIG_NAME = "echo-translator.config.json"

private val PROVIDERS = listOf(
    "mock" to "Mock 离线",
    "llm" to "LLM",
    "deepl" to "DeepL",
    "baidu" to "百度",
    "youdao" to "有道",
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("echo", Context.MODE_PRIVATE) }

    val existing = remember { readConfig(context) }
    var provider by remember { mutableStateOf(existing?.optString("provider").orEmpty().ifBlank { "mock" }) }

    var deeplKey by remember { mutableStateOf(existing?.optJSONObject("deepl")?.optString("api_key").orEmpty()) }
    var deeplFree by remember { mutableStateOf(existing?.optJSONObject("deepl")?.optBoolean("use_free_api", true) ?: true) }
    var baiduId by remember { mutableStateOf(existing?.optJSONObject("baidu")?.optString("app_id").orEmpty()) }
    var baiduSecret by remember { mutableStateOf(existing?.optJSONObject("baidu")?.optString("secret").orEmpty()) }
    var youdaoKey by remember { existing?.optJSONObject("youdao")?.optString("app_key").orEmpty()?.let { mutableStateOf(it) } ?: mutableStateOf("") }
    var youdaoSecret by remember { mutableStateOf(existing?.optJSONObject("youdao")?.optString("secret").orEmpty()) }
    var llmEndpoint by remember { mutableStateOf(existing?.optJSONObject("llm")?.optString("endpoint").orEmpty()) }
    var llmKey by remember { mutableStateOf(existing?.optJSONObject("llm")?.optString("api_key").orEmpty()) }
    var llmModel by remember { mutableStateOf(existing?.optJSONObject("llm")?.optString("model").orEmpty()) }
    var llmExtra by remember { mutableStateOf(existing?.optJSONObject("llm")?.optString("extra_system_prompt").orEmpty()) }
    var llmProfile by remember {
        mutableStateOf(
            existing?.optJSONObject("llm")?.optString("profile").orEmpty().ifBlank { "gal" }
        )
    }

    var ocrLangName by remember { mutableStateOf(prefs.getString("ocr_lang", OcrLang.Ja.name) ?: OcrLang.Ja.name) }
    var displayMode by remember { mutableStateOf(prefs.getString("display_mode", "below") ?: "below") }
    var fontScale by remember { mutableStateOf(prefs.getString("font_scale", "1.0") ?: "1.0") }
    var scrimAlpha by remember { mutableStateOf(prefs.getString("scrim_alpha", "0.55") ?: "0.55") }
    // 裁剪区域：自动/手动开关，自动时输入框禁用并展示系统实际值
    var cropTopAuto by remember { mutableStateOf(prefs.getString("crop_top", null)?.toIntOrNull() == null) }
    var cropTopText by remember {
        mutableStateOf(prefs.getString("crop_top", null)?.toIntOrNull()?.toString() ?: "")
    }
    var cropBottomAuto by remember { mutableStateOf(prefs.getString("crop_bottom", null)?.toIntOrNull() == null) }
    var cropBottomText by remember {
        mutableStateOf(prefs.getString("crop_bottom", null)?.toIntOrNull()?.toString() ?: "")
    }
    val autoTopPx = remember { com.echo.android.util.CropRegion.statusBarHeight(context) }
    val autoBottomPx = remember { com.echo.android.util.CropRegion.navigationBarHeight(context) }
    var saved by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    /** 由表单状态构建完整配置（保留未编辑 provider 的既有段落） */
    fun buildConfig(): JSONObject {
        val config = readConfig(context) ?: JSONObject()
        if (!config.has("source_lang")) config.put("source_lang", "Auto")
        if (!config.has("target_lang")) config.put("target_lang", "ZhHans")
        config.put("provider", provider)
        when (provider) {
            "deepl" -> config.put(
                "deepl",
                JSONObject().put("api_key", deeplKey.trim()).put("use_free_api", deeplFree),
            )
            "baidu" -> config.put(
                "baidu",
                JSONObject().put("app_id", baiduId.trim()).put("secret", baiduSecret.trim()),
            )
            "youdao" -> config.put(
                "youdao",
                JSONObject().put("app_key", youdaoKey.trim()).put("secret", youdaoSecret.trim()),
            )
                "llm" -> {
                    val llm = JSONObject()
                        .put("endpoint", llmEndpoint.trim())
                        .put("api_key", llmKey.trim())
                        .put("model", llmModel.trim())
                        .put("profile", llmProfile)
                    if (llmExtra.isNotBlank()) llm.put("extra_system_prompt", llmExtra.trim())
                    config.put("llm", llm)
                }
        }
        return config
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("翻译引擎设置", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            Text("翻译服务", style = MaterialTheme.typography.bodySmall)
            FlowRow {
                PROVIDERS.forEach { (key, label) ->
                    FilterChip(
                        selected = provider == key,
                        onClick = { provider = key },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            when (provider) {
                "deepl" -> {
                    OutlinedTextField(deeplKey, { deeplKey = it }, label = { Text("DeepL API Key") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = deeplFree, onCheckedChange = { deeplFree = it })
                        Spacer(Modifier.height(0.dp))
                        Text("  使用免费版端点（api-free.deepl.com）")
                    }
                }
                "baidu" -> {
                    OutlinedTextField(baiduId, { baiduId = it }, label = { Text("百度 APP ID") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(baiduSecret, { baiduSecret = it }, label = { Text("百度密钥") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
                "youdao" -> {
                    OutlinedTextField(youdaoKey, { youdaoKey = it }, label = { Text("有道应用 ID") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(youdaoSecret, { youdaoSecret = it }, label = { Text("有道应用密钥") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
                "llm" -> {
                    OutlinedTextField(llmEndpoint, { llmEndpoint = it }, label = { Text("API 端点（OpenAI 兼容）") },
                        placeholder = { Text("https://opencode.ai/zen/go/v1") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(llmKey, { llmKey = it }, label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(llmModel, { llmModel = it }, label = { Text("模型名") },
                        placeholder = { Text("mimo-v2.5") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(llmExtra, { llmExtra = it }, label = { Text("附加提示词（可选）") },
                        placeholder = { Text("漫画与游戏文本，译文简短口语化") },
                        modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("翻译方案（两套提示词与处理策略，可对比效果）", style = MaterialTheme.typography.bodySmall)
                    Row {
                        FilterChip(
                            selected = llmProfile == "gal",
                            onClick = { llmProfile = "gal" },
                            label = { Text("GAL（对话+说话人分离）") },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        FilterChip(
                            selected = llmProfile == "manga",
                            onClick = { llmProfile = "manga" },
                            label = { Text("漫画（整页气泡）") },
                        )
                    }
                }
                else -> {
                    Text("离线演示用，返回原文加前缀，不联网。", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("OCR 识别语言（按内容主语言选，日语模型也覆盖英文）", style = MaterialTheme.typography.bodySmall)
            Row {
                OcrLang.entries.forEach { l ->
                    FilterChip(
                        selected = ocrLangName == l.name,
                        onClick = { ocrLangName = l.name },
                        label = { Text(l.display) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("译文展示方式（悬浮球与 app 内同步）", style = MaterialTheme.typography.bodySmall)
            Row {
                FilterChip(
                    selected = displayMode == "below",
                    onClick = { displayMode = "below" },
                    label = { Text("下方对照") },
                    modifier = Modifier.padding(end = 4.dp),
                )
                FilterChip(
                    selected = displayMode == "cover",
                    onClick = { displayMode = "cover" },
                    label = { Text("原位覆盖") },
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("译文字号（悬浮球与 app 内同步）", style = MaterialTheme.typography.bodySmall)
            Row {
                listOf("0.8" to "小", "1.0" to "标准", "1.2" to "大", "1.4" to "特大").forEach { (value, label) ->
                    FilterChip(
                        selected = fontScale == value,
                        onClick = { fontScale = value },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("底衬浓度（译文背景的不透明度）", style = MaterialTheme.typography.bodySmall)
            Row {
                listOf("0.35" to "淡", "0.55" to "标准", "0.75" to "浓").forEach { (value, label) ->
                    FilterChip(
                        selected = scrimAlpha == value,
                        onClick = { scrimAlpha = value },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("裁剪区域（跳过状态栏时间/电量等）", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("顶部", modifier = Modifier.weight(0.4f))
                FilterChip(
                    selected = cropTopAuto,
                    onClick = { cropTopAuto = true },
                    label = { Text("自动") },
                    modifier = Modifier.padding(end = 4.dp),
                )
                FilterChip(
                    selected = !cropTopAuto,
                    onClick = { cropTopAuto = false },
                    label = { Text("手动") },
                )
            }
            if (cropTopAuto) {
                Text(
                    "自动 · 跳过状态栏 ${autoTopPx}px",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                OutlinedTextField(
                    value = cropTopText,
                    onValueChange = { cropTopText = it },
                    label = { Text("像素值") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("底部", modifier = Modifier.weight(0.4f))
                FilterChip(
                    selected = cropBottomAuto,
                    onClick = { cropBottomAuto = true },
                    label = { Text("自动") },
                    modifier = Modifier.padding(end = 4.dp),
                )
                FilterChip(
                    selected = !cropBottomAuto,
                    onClick = { cropBottomAuto = false },
                    label = { Text("手动") },
                )
            }
            if (cropBottomAuto) {
                Text(
                    "自动 · 跳过导航栏 ${autoBottomPx}px",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                OutlinedTextField(
                    value = cropBottomText,
                    onValueChange = { cropBottomText = it },
                    label = { Text("像素值") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row {
                Button(
                    onClick = {
                        File(context.filesDir, CONFIG_NAME).writeText(buildConfig().toString())
                        prefs.edit()
                            .putString("ocr_lang", ocrLangName)
                            .putString("display_mode", displayMode)
                            .putString("font_scale", fontScale)
                            .putString("scrim_alpha", scrimAlpha)
                            .putString(
                                "crop_top",
                                if (cropTopAuto) "" else cropTopText.trim().toIntOrNull()?.toString() ?: "",
                            )
                            .putString(
                                "crop_bottom",
                                if (cropBottomAuto) "" else cropBottomText.trim().toIntOrNull()?.toString() ?: "",
                            )
                            .apply()
                        saved = true
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
                Spacer(Modifier.height(0.dp).padding(start = 8.dp))
                OutlinedButton(
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            testResult = withContext(Dispatchers.IO) {
                                try {
                                    val translator = EchoTranslator(buildConfig().toString())
                                    val result = translator.translate(listOf("少女は静かに呟いた"))
                                    translator.close()
                                    "✓ ${result.firstOrNull().orEmpty()}"
                                } catch (e: Exception) {
                                    "✗ ${e.message}"
                                }
                            }
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                ) { Text(if (testing) "测试中…" else "测试配置") }
            }

            testResult?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "测试：$it",
                    color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            if (saved) {
                Spacer(Modifier.height(8.dp))
                Text("已保存。重启 app 或重新开启悬浮球后生效。", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun readConfig(context: Context): JSONObject? {
    val external = File(context.getExternalFilesDir(null), CONFIG_NAME)
    val internal = File(context.filesDir, CONFIG_NAME)
    val file = when {
        external.exists() -> external
        internal.exists() -> internal
        else -> return null
    }
    return runCatching { JSONObject(file.readText()) }.getOrNull()
}
