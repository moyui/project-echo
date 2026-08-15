//! echo-translator：Echo 全平台共享的翻译网关。
//!
//! 分层：
//! - [`Translator`] trait：具体翻译服务（DeepL / 百度 / 有道 / LLM / Mock）；
//! - [`Gateway`]：面向调用方的门面——LRU 缓存、术语表、provider 路由都在这层，
//!   各端（Tauri / UniFFI / napi-rs）只对接 `Gateway`。
//!
//! 与 echo-core 的分工：`PipelineState` 负责"这帧哪些文本是新的"，
//! `Gateway` 负责"新文本的译文怎么拿、拿过的怎么缓存"。

pub mod config;
pub mod error;
pub mod providers;

mod gateway;

pub use config::{
    apply_glossary, BaiduConfig, CacheConfig, DeepLConfig, GlossaryEntry, LlmConfig, ProviderKind, TranslatorConfig,
    YoudaoConfig,
};
pub use error::{Result, TranslateError};
pub use gateway::Gateway;

/// 配置的权威位置：系统配置目录（Windows: %APPDATA%\echo，Linux: ~/.config/echo，macOS: ~/Library/Application Support/echo）。
/// 桌面端设置页与 CLI 共用这一份；安卓端使用 app 私有目录（见 Android 侧代码）。
pub fn default_config_path() -> std::path::PathBuf {
    dirs::config_dir()
        .unwrap_or_else(|| std::path::PathBuf::from("."))
        .join("echo")
        .join("echo-translator.config.json")
}

/// 从默认位置读取配置；不存在时返回默认 mock 配置
pub fn load_default_config() -> TranslatorConfig {
    let path = default_config_path();
    std::fs::read_to_string(path)
        .ok()
        .and_then(|content| serde_json::from_str(&content).ok())
        .unwrap_or_default()
}

/// 保存配置到默认位置（校验由调用方完成）
pub fn save_default_config(config: &TranslatorConfig) -> std::io::Result<std::path::PathBuf> {
    let path = default_config_path();
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(&path, serde_json::to_vec_pretty(config)?)?;
    Ok(path)
}

use async_trait::async_trait;
use echo_core::Lang;

/// 单个翻译服务的抽象。`translate_batch` 保证返回与输入等长的译文数组。
#[async_trait]
pub trait Translator: Send + Sync {
    async fn translate_batch(&self, texts: &[String], from: Lang, to: Lang) -> Result<Vec<String>>;

    fn name(&self) -> &str;
}
