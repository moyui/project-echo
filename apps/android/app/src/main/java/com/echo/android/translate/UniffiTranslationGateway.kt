package com.echo.android.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.echo.EchoTranslator
import java.io.File

/**
 * Rust 翻译网关（UniFFI）。
 *
 * 配置查找顺序：外部私有目录 → 内部私有目录 → 默认 mock 配置。
 * adb 推送真实配置（GLM/DeepL 等 key）：
 *   adb push echo-translator.config.json /storage/emulated/0/Android/data/com.echo.android/files/
 */
class UniffiTranslationGateway private constructor(
    private val translator: EchoTranslator,
) : TranslationGateway {

    override val name: String
        get() = translator.providerName()

    override suspend fun translate(texts: List<String>): List<String> =
        withContext(Dispatchers.IO) { translator.translate(texts) }

    companion object {
        private const val DEFAULT_CONFIG =
            """{"provider":"mock","source_lang":"Auto","target_lang":"ZhHans"}"""

        fun load(context: Context): UniffiTranslationGateway? = try {
            // 内部（设置页保存）优先；外部目录作为 adb 首次导入的入口
            val internal = File(context.filesDir, CONFIG_NAME)
            val external = File(context.getExternalFilesDir(null), CONFIG_NAME)
            val configJson = when {
                internal.exists() -> internal.readText()
                external.exists() -> external.readText()
                else -> DEFAULT_CONFIG
            }
            UniffiTranslationGateway(EchoTranslator(configJson))
        } catch (_: Throwable) {
            null
        }

        private const val CONFIG_NAME = "echo-translator.config.json"
    }
}
