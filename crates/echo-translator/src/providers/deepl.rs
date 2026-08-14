use async_trait::async_trait;
use echo_core::Lang;
use serde::Deserialize;

use crate::config::DeepLConfig;
use crate::providers::http_client;
use crate::{Result, TranslateError, Translator};

pub struct DeepLProvider {
    api_key: String,
    host: String,
    client: reqwest::Client,
}

impl DeepLProvider {
    pub fn new(config: DeepLConfig) -> Self {
        let host = if config.use_free_api {
            "https://api-free.deepl.com".to_string()
        } else {
            "https://api.deepl.com".to_string()
        };
        Self {
            api_key: config.api_key,
            host,
            client: http_client(),
        }
    }
}

fn lang_code(lang: Lang) -> Option<&'static str> {
    match lang {
        Lang::Auto => None, // 省略 source_lang 让 DeepL 自动检测
        Lang::Ja => Some("JA"),
        Lang::ZhHans | Lang::ZhHant => Some("ZH"),
        Lang::En => Some("EN"),
        Lang::Ko => Some("KO"),
    }
}

#[derive(Deserialize)]
struct DeepLResponse {
    translations: Vec<DeepLItem>,
}

#[derive(Deserialize)]
struct DeepLItem {
    text: String,
}

#[async_trait]
impl Translator for DeepLProvider {
    async fn translate_batch(&self, texts: &[String], from: Lang, to: Lang) -> Result<Vec<String>> {
        let target = lang_code(to).ok_or_else(|| TranslateError::Api("DeepL 不支持该目标语言".into()))?;
        let mut form: Vec<(&str, &str)> = Vec::with_capacity(texts.len() + 2);
        for text in texts {
            form.push(("text", text.as_str()));
        }
        if let Some(source) = lang_code(from) {
            form.push(("source_lang", source));
        }
        form.push(("target_lang", target));

        let url = format!("{}/v2/translate", self.host);
        let response = self
            .client
            .post(url)
            .header("Authorization", format!("DeepL-Auth-Key {}", self.api_key))
            .form(&form)
            .send()
            .await?;
        let status = response.status();
        if !status.is_success() {
            let body = response.text().await.unwrap_or_default();
            return Err(TranslateError::Api(format!("DeepL {status}: {body}")));
        }
        let parsed: DeepLResponse = response.json().await?;
        if parsed.translations.len() != texts.len() {
            return Err(TranslateError::Parse(format!(
                "DeepL 返回数量不匹配：期望 {}，实际 {}",
                texts.len(),
                parsed.translations.len()
            )));
        }
        Ok(parsed.translations.into_iter().map(|item| item.text).collect())
    }

    fn name(&self) -> &str {
        "deepl"
    }
}
