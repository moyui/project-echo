use async_trait::async_trait;
use echo_core::Lang;

use crate::{Result, Translator};

/// 离线演示 provider：不联网，返回 `[mock] 原文`。
pub struct MockProvider;

#[async_trait]
impl Translator for MockProvider {
    async fn translate_batch(&self, texts: &[String], _from: Lang, _to: Lang) -> Result<Vec<String>> {
        Ok(texts.iter().map(|t| format!("[mock] {t}")).collect())
    }

    fn name(&self) -> &str {
        "mock"
    }
}
