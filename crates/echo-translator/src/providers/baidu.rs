use async_trait::async_trait;
use echo_core::Lang;
use md5::{Digest, Md5};
use serde::Deserialize;

use crate::config::BaiduConfig;
use crate::providers::http_client;
use crate::{Result, TranslateError, Translator};

/// 百度翻译开放平台（标准版免费额度足够个人使用）。
/// https://fanyi-api.baidu.com/doc/21
pub struct BaiduProvider {
    app_id: String,
    secret: String,
    client: reqwest::Client,
}

impl BaiduProvider {
    pub fn new(config: BaiduConfig) -> Self {
        Self { app_id: config.app_id, secret: config.secret, client: http_client() }
    }
}

fn lang_code(lang: Lang) -> Result<&'static str> {
    match lang {
        Lang::Auto => Ok("auto"),
        Lang::ZhHans => Ok("zh"),
        Lang::ZhHant => Ok("cht"),
        Lang::Ja => Ok("jp"),
        Lang::En => Ok("en"),
        Lang::Ko => Ok("kor"),
    }
}

pub fn md5_hex(input: &str) -> String {
    let mut hasher = Md5::new();
    hasher.update(input.as_bytes());
    hex::encode(hasher.finalize())
}

#[derive(Deserialize)]
struct BaiduResponse {
    #[serde(default)]
    trans_result: Option<Vec<BaiduItem>>,
    #[serde(default)]
    error_code: Option<String>,
}

#[derive(Deserialize)]
struct BaiduItem {
    #[serde(default)]
    dst: String,
}

#[async_trait]
impl Translator for BaiduProvider {
    async fn translate_batch(&self, texts: &[String], from: Lang, to: Lang) -> Result<Vec<String>> {
        // 百度接口一次一段，逐条请求
        let mut results = Vec::with_capacity(texts.len());
        for text in texts {
            let salt = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.subsec_nanos())
                .unwrap_or(0)
                .to_string();
            let sign = md5_hex(&format!("{}{}{}{}", self.app_id, text, salt, self.secret));

            let response = self
                .client
                .post("https://fanyi.baidu.com/api/trans/vip/translate")
                .form(&[
                    ("q", text.as_str()),
                    ("from", lang_code(from)?),
                    ("to", lang_code(to)?),
                    ("appid", self.app_id.as_str()),
                    ("salt", salt.as_str()),
                    ("sign", sign.as_str()),
                ])
                .send()
                .await?;
            let status = response.status();
            if !status.is_success() {
                let body = response.text().await.unwrap_or_default();
                return Err(TranslateError::Api(format!("百度翻译 {status}: {body}")));
            }
            let parsed: BaiduResponse = response.json().await?;
            if let Some(code) = parsed.error_code {
                return Err(TranslateError::Api(format!("百度翻译错误码 {code}")));
            }
            let items = parsed.trans_result.ok_or_else(|| TranslateError::Parse("百度翻译缺少 trans_result".into()))?;
            // q 含换行时会拆成多条结果，拼回来
            results.push(items.into_iter().map(|item| item.dst).collect::<Vec<_>>().join("\n"));
        }
        Ok(results)
    }

    fn name(&self) -> &str {
        "baidu"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn md5_sign() {
        assert_eq!(md5_hex("hello"), "5d41402abc4b2a76b9719d911017c592");
    }
}
