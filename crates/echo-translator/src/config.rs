//! 翻译网关配置。该结构是 N 端共享配置的单一事实来源：
//! `schema` 子命令可输出其 JSON Schema，`packages/config-schema` 据此生成 TS 类型。

use echo_core::Lang;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, JsonSchema, Default)]
#[serde(rename_all = "lowercase")]
pub enum ProviderKind {
    /// 离线演示用，返回 `[mock] 原文`，不发起网络请求。
    #[default]
    Mock,
    DeepL,
    Baidu,
    Youdao,
    /// 任意 OpenAI 兼容端点（OpenAI / GLM / DeepSeek / 本地模型等）。
    Llm,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct GlossaryEntry {
    /// 原文术语，如「エルフ」
    pub source: String,
    /// 期望译文，如「精灵」
    pub target: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, JsonSchema)]
#[serde(default)]
pub struct CacheConfig {
    /// 内存 LRU 容量（条）。
    pub capacity: usize,
    /// 磁盘缓存文件路径（JSON），设置后 CLI 会在退出时持久化。
    pub disk_path: Option<String>,
}

impl Default for CacheConfig {
    fn default() -> Self {
        Self { capacity: 512, disk_path: None }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, JsonSchema)]
pub struct DeepLConfig {
    pub api_key: String,
    /// true 走 api-free.deepl.com（免费 key），false 走 api.deepl.com（Pro）。
    #[serde(default = "default_true")]
    pub use_free_api: bool,
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize, JsonSchema)]
pub struct BaiduConfig {
    pub app_id: String,
    pub secret: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, JsonSchema)]
pub struct YoudaoConfig {
    pub app_key: String,
    pub secret: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, JsonSchema)]
pub struct LlmConfig {
    /// OpenAI 兼容 base url，如 `https://api.openai.com/v1` 或 `https://open.bigmodel.cn/api/paas/v4`。
    pub endpoint: String,
    pub api_key: String,
    pub model: String,
    /// 追加到系统提示词末尾的自定义要求（口吻、风格等）。
    pub extra_system_prompt: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, JsonSchema)]
#[serde(default)]
pub struct TranslatorConfig {
    pub provider: ProviderKind,
    pub source_lang: Lang,
    pub target_lang: Lang,
    /// 术语表：发送给 provider 前做原文侧替换，LLM 额外注入提示词。
    pub glossary: Vec<GlossaryEntry>,
    pub cache: CacheConfig,
    pub deepl: Option<DeepLConfig>,
    pub baidu: Option<BaiduConfig>,
    pub youdao: Option<YoudaoConfig>,
    pub llm: Option<LlmConfig>,
}

impl Default for TranslatorConfig {
    fn default() -> Self {
        Self {
            provider: ProviderKind::default(),
            source_lang: Lang::Auto,
            target_lang: Lang::ZhHans,
            glossary: Vec::new(),
            cache: CacheConfig::default(),
            deepl: None,
            baidu: None,
            youdao: None,
            llm: None,
        }
    }
}

/// 原文侧术语替换（长的术语优先，避免短术语截断长术语）。
pub fn apply_glossary(text: &str, glossary: &[GlossaryEntry]) -> String {
    if glossary.is_empty() {
        return text.to_string();
    }
    let mut sorted: Vec<&GlossaryEntry> = glossary.iter().collect();
    sorted.sort_by(|a, b| b.source.chars().count().cmp(&a.source.chars().count()));
    let mut result = text.to_string();
    for entry in sorted {
        if !entry.source.is_empty() {
            result = result.replace(&entry.source, &entry.target);
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn glossary_longest_first() {
        let glossary = vec![
            GlossaryEntry { source: "魔法".into(), target: "MAGIC".into() },
            GlossaryEntry { source: "魔法使い".into(), target: "mage".into() },
        ];
        assert_eq!(apply_glossary("魔法使いが来た", &glossary), "mageが来た");
        assert_eq!(apply_glossary("魔法が見える", &glossary), "MAGICが見える");
    }

    #[test]
    fn partial_json_uses_defaults() {
        let cfg: TranslatorConfig = serde_json::from_str(r#"{"provider":"llm"}"#).unwrap();
        assert_eq!(cfg.provider, ProviderKind::Llm);
        assert_eq!(cfg.target_lang, Lang::ZhHans);
        assert_eq!(cfg.cache.capacity, 512);
    }
}
