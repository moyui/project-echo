use async_trait::async_trait;
use echo_core::Lang;
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::config::{LlmConfig, GlossaryEntry};
use crate::providers::http_client;
use crate::{Result, TranslateError, Translator};

/// 任意 OpenAI 兼容 `/chat/completions` 端点的 LLM 翻译。
/// 批量输入以 JSON 数组传递，要求模型严格回等长 JSON 数组。
pub struct LlmProvider {
    endpoint: String,
    api_key: String,
    model: String,
    glossary: Vec<GlossaryEntry>,
    extra_system_prompt: Option<String>,
    client: reqwest::Client,
}

impl LlmProvider {
    pub fn new(config: LlmConfig, glossary: Vec<GlossaryEntry>) -> Self {
        Self {
            endpoint: config.endpoint.trim_end_matches('/').to_string(),
            api_key: config.api_key,
            model: config.model,
            glossary,
            extra_system_prompt: config.extra_system_prompt,
            client: http_client(),
        }
    }

    fn system_prompt(&self, from: Lang, to: Lang) -> String {
        let lang_name = |lang: Lang| -> &'static str {
            match lang {
                Lang::Auto => "自动检测的源语言",
                Lang::Ja => "日语",
                Lang::ZhHans => "简体中文",
                Lang::ZhHant => "繁体中文",
                Lang::En => "英语",
                Lang::Ko => "韩语",
            }
        };
        let mut prompt = format!(
            "你是专业的翻译引擎。用户会给出一个 JSON 字符串数组，数组元素是同一屏幕上的一组{}文本\
（可能是对话、界面文案、人名等），请结合整屏上下文互相参照理解后，把每个元素翻译成{}，\
保持原文的语气与格式，专名与数字不要改动。\
只输出一个与输入等长的 JSON 字符串数组作为全部回复，不要输出解释、markdown 或其他任何内容。",
            lang_name(from),
            lang_name(to)
        );
        if !self.glossary.is_empty() {
            let entries: Vec<String> =
                self.glossary.iter().map(|g| format!("  {} => {}", g.source, g.target)).collect();
            prompt.push_str(&format!("\n术语表（必须遵守）：\n{}", entries.join("\n")));
        }
        if let Some(extra) = &self.extra_system_prompt {
            prompt.push_str(&format!("\n附加要求：{extra}"));
        }
        prompt
    }
}

/// 剥掉模型可能加的 ```json 围栏。
pub fn strip_code_fences(content: &str) -> String {
    let trimmed = content.trim();
    let without_prefix = if let Some(rest) = trimmed.strip_prefix("```") {
        // 去掉 ```json 这一行
        match rest.find('\n') {
            Some(idx) => &rest[idx + 1..],
            None => rest,
        }
    } else {
        trimmed
    };
    without_prefix.trim().trim_end_matches("```").trim().to_string()
}

#[derive(Serialize)]
struct ChatMessage {
    role: &'static str,
    content: String,
}

#[derive(Deserialize)]
struct ChatResponse {
    choices: Vec<ChatChoice>,
}

#[derive(Deserialize)]
struct ChatChoice {
    message: ChatMessageContent,
}

#[derive(Deserialize)]
struct ChatMessageContent {
    content: String,
}

#[async_trait]
impl Translator for LlmProvider {
    async fn translate_batch(&self, texts: &[String], from: Lang, to: Lang) -> Result<Vec<String>> {
        if texts.is_empty() {
            return Ok(Vec::new());
        }
        let url = if self.endpoint.ends_with("/chat/completions") {
            self.endpoint.clone()
        } else {
            format!("{}/chat/completions", self.endpoint)
        };
        let body = json!({
            "model": self.model,
            "temperature": 0.1,
            "messages": [
                ChatMessage { role: "system", content: self.system_prompt(from, to) },
                ChatMessage { role: "user", content: serde_json::to_string(&texts)? },
            ],
        });

        let response = self
            .client
            .post(url)
            .header("Authorization", format!("Bearer {}", self.api_key))
            .json(&body)
            .send()
            .await?;
        let status = response.status();
        if !status.is_success() {
            let body = response.text().await.unwrap_or_default();
            return Err(TranslateError::Api(format!("LLM {status}: {body}")));
        }
        let parsed: ChatResponse = response.json().await?;
        let content = parsed
            .choices
            .first()
            .ok_or_else(|| TranslateError::Parse("LLM 返回空 choices".into()))?
            .message
            .content
            .clone();
        let cleaned = strip_code_fences(&content);
        let translations: Vec<String> =
            serde_json::from_str(&cleaned).map_err(|e| TranslateError::Parse(format!("LLM 输出不是合法 JSON 数组: {e}\n{cleaned}")))?;
        if translations.len() != texts.len() {
            return Err(TranslateError::Parse(format!(
                "LLM 返回数量不匹配：期望 {}，实际 {}",
                texts.len(),
                translations.len()
            )));
        }
        Ok(translations)
    }

    fn name(&self) -> &str {
        "llm"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_fences() {
        assert_eq!(strip_code_fences("```json\n[\"a\"]\n```"), "[\"a\"]");
        assert_eq!(strip_code_fences("[\"a\"]"), "[\"a\"]");
        assert_eq!(strip_code_fences("  [\"a\"]  \n"), "[\"a\"]");
    }
}
