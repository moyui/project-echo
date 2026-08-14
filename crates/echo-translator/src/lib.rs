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

use async_trait::async_trait;
use echo_core::Lang;

/// 单个翻译服务的抽象。`translate_batch` 保证返回与输入等长的译文数组。
#[async_trait]
pub trait Translator: Send + Sync {
    async fn translate_batch(&self, texts: &[String], from: Lang, to: Lang) -> Result<Vec<String>>;

    fn name(&self) -> &str;
}
