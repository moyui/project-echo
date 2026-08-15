package com.echo.android.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.echo.EchoTranslator
import java.io.File

/**
 * Rust 翻译网关（UniFFI）。
 *
 * 配置唯一来源：app 内部私有目录 files/echo-translator.config.json（设置页读写）。
 */
class UniffiTranslationGateway private constructor(
    private val translator: EchoTranslator,
) : TranslationGateway {
    override val name: String
        get() = translator.providerName()

    override suspend fun translate(texts: List<String>): List<String> {
        val t0 = System.currentTimeMillis()
        val out =
            withContext(Dispatchers.IO) {
                val t1 = System.currentTimeMillis()
                val r = translator.translate(texts)
                android.util.Log.d("EchoBall", "Rust translate ${texts.size} 条耗时 ${System.currentTimeMillis() - t1}ms")
                r
            }
        android.util.Log.d("EchoBall", "Kotlin 桥接总耗时 ${System.currentTimeMillis() - t0}ms")
        return out
    }

    companion object {
        private const val DEFAULT_CONFIG =
            """{"provider":"mock","source_lang":"Auto","target_lang":"ZhHans"}"""

        fun load(context: Context): UniffiTranslationGateway? =
            try {
                val configJson =
                    File(context.filesDir, CONFIG_NAME)
                        .takeIf { it.exists() }
                        ?.readText() ?: DEFAULT_CONFIG
                UniffiTranslationGateway(EchoTranslator(configJson))
            } catch (_: Throwable) {
                null
            }

        private const val CONFIG_NAME = "echo-translator.config.json"
    }
}
