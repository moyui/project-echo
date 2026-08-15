package com.echo.android.translate

/**
 * 翻译网关接口。默认实现走 UniFFI 调 crates/echo-translator 的 Rust Gateway
 * （缓存/术语表/provider 路由都在 Rust 侧）；加载失败时退回桩实现。
 */
interface TranslationGateway {
    /** 引擎名（mock/deepl/baidu/youdao/llm/stub），用于界面展示 */
    val name: String

    suspend fun translate(texts: List<String>): List<String>
}

class StubTranslationGateway : TranslationGateway {
    override val name: String = "stub"

    override suspend fun translate(texts: List<String>): List<String> = texts.map { "[Rust 网关待接入] $it" }
}
