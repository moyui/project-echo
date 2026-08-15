//! echo-bindings：UniFFI 导出层。
//!
//! 对移动端暴露极小的 API 表面：JSON 配置进，字符串数组出。
//! Rust 侧的完整类型（provider 配置等）不跨 FFI，避免维护两份类型图。
//!
//! 翻译是阻塞接口，移动端请在 IO 线程调用（Kotlin 侧包一层 suspend）。

use std::sync::Mutex;

use echo_translator::{Gateway, TranslatorConfig};

/// FFI 错误：Kotlin 侧表现为 EchoException
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum EchoError {
    #[error("配置错误: {0}")]
    Config(String),
    #[error("翻译错误: {0}")]
    Translate(String),
    #[error("内部错误: {0}")]
    Internal(String),
}

#[derive(uniffi::Object)]
pub struct EchoTranslator {
    runtime: tokio::runtime::Runtime,
    gateway: Mutex<Gateway>,
}

#[uniffi::export]
impl EchoTranslator {
    /// 从 JSON 配置构建（结构与 echo-translator.config.example.json 一致）
    #[uniffi::constructor]
    pub fn new(config_json: String) -> Result<Self, EchoError> {
        let config: TranslatorConfig =
            serde_json::from_str(&config_json).map_err(|e| EchoError::Config(e.to_string()))?;
        let gateway = Gateway::new(config).map_err(|e| EchoError::Config(e.to_string()))?;
        // current_thread：翻译请求天然串行（单 gateway 互斥），多线程 runtime 的
        // block_on 要等 worker 线程调度，在部分 Android 系统上 worker 被后台冻结
        // 时延迟可达数十秒；单线程 runtime 由 block_on 调用线程直接驱动，无此问题。
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|e| EchoError::Internal(e.to_string()))?;
        Ok(Self { runtime, gateway: Mutex::new(gateway) })
    }

    /// 批量翻译（阻塞）。缓存/去重/术语表都在 Rust 网关内部。
    pub fn translate(&self, texts: Vec<String>) -> Result<Vec<String>, EchoError> {
        let mut gateway = self.gateway.lock().map_err(|_| EchoError::Internal("网关锁获取失败".into()))?;
        self.runtime
            .block_on(gateway.translate(&texts))
            .map_err(|e| EchoError::Translate(e.to_string()))
    }

    /// 当前 provider 名（mock/deepl/baidu/youdao/llm）
    pub fn provider_name(&self) -> String {
        self.gateway
            .lock()
            .map(|g| g.provider_name().to_string())
            .unwrap_or_else(|_| "unknown".into())
    }

    /// 持久化磁盘缓存（配置了 cache.disk_path 才有效）
    pub fn save_cache(&self) -> Result<(), EchoError> {
        let gateway = self.gateway.lock().map_err(|_| EchoError::Internal("网关锁获取失败".into()))?;
        gateway.save_cache().map_err(|e| EchoError::Internal(e.to_string()))
    }
}

uniffi::setup_scaffolding!("echo");
