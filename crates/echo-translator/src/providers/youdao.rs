use async_trait::async_trait;
use echo_core::Lang;
use serde::Deserialize;
use sha2::{Digest, Sha256};

use crate::config::YoudaoConfig;
use crate::providers::http_client;
use crate::{Result, TranslateError, Translator};

/// 有道智云文本翻译（signType=v3）。
/// https://ai.youdao.com/DOCSIRMA/html/trans/api/wbfy/index.html
pub struct YoudaoProvider {
    app_key: String,
    secret: String,
    client: reqwest::Client,
}

impl YoudaoProvider {
    pub fn new(config: YoudaoConfig) -> Self {
        Self { app_key: config.app_key, secret: config.secret, client: http_client() }
    }
}

fn lang_code(lang: Lang) -> Result<&'static str> {
    match lang {
        Lang::Auto => Ok("auto"),
        Lang::ZhHans => Ok("zh-CHS"),
        Lang::ZhHant => Ok("zh-CHT"),
        Lang::Ja => Ok("ja"),
        Lang::En => Ok("en"),
        Lang::Ko => Ok("ko"),
    }
}

fn sha256_hex(input: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(input.as_bytes());
    hex::encode(hasher.finalize())
}

/// 有道 v3 签名的 input 规则：长度（字符数）≤20 原样；否则 前10字符 + 长度 + 后10字符。
pub fn youdao_input(q: &str) -> String {
    let chars: Vec<char> = q.chars().collect();
    if chars.len() <= 20 {
        return q.to_string();
    }
    let head: String = chars[..10].iter().collect();
    let tail: String = chars[chars.len() - 10..].iter().collect();
    format!("{head}{}{tail}", chars.len())
}

#[derive(Deserialize)]
struct YoudaoResponse {
    #[serde(default)]
    translation: Option<Vec<String>>,
    #[serde(default)]
    error_code: Option<String>,
}

#[async_trait]
impl Translator for YoudaoProvider {
    async fn translate_batch(&self, texts: &[String], from: Lang, to: Lang) -> Result<Vec<String>> {
        let mut results = Vec::with_capacity(texts.len());
        for text in texts {
            let curtime = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0)
                .to_string();
            let salt = curtime.clone() + "000"; // 毫秒级即可，格式无硬性要求
            let sign = sha256_hex(&format!(
                "{}{}{}{}{}",
                self.app_key,
                youdao_input(text),
                salt,
                curtime,
                self.secret
            ));

            let response = self
                .client
                .post("https://openapi.youdaoz.com/api.php")
                .form(&[
                    ("q", text.as_str()),
                    ("from", lang_code(from)?),
                    ("to", lang_code(to)?),
                    ("appKey", self.app_key.as_str()),
                    ("salt", salt.as_str()),
                    ("signType", "v3"),
                    ("curtime", curtime.as_str()),
                    ("sign", sign.as_str()),
                ])
                .send()
                .await?;
            let status = response.status();
            if !status.is_success() {
                let body = response.text().await.unwrap_or_default();
                return Err(TranslateError::Api(format!("有道翻译 {status}: {body}")));
            }
            let parsed: YoudaoResponse = response.json().await?;
            if parsed.error_code.as_deref().is_some_and(|code| code != "0") {
                return Err(TranslateError::Api(format!("有道翻译错误码 {}", parsed.error_code.unwrap())));
            }
            let translation = parsed
                .translation
                .and_then(|mut list| if list.is_empty() { None } else { Some(list.remove(0)) })
                .ok_or_else(|| TranslateError::Parse("有道翻译缺少 translation".into()))?;
            results.push(translation);
        }
        Ok(results)
    }

    fn name(&self) -> &str {
        "youdao"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn input_rule_short() {
        assert_eq!(youdao_input("short text"), "short text");
    }

    #[test]
    fn input_rule_long() {
        let q = "0123456789abcdefghijZZZZ"; // 24 字符
        let expected = format!("0123456789{}efghijZZZZ", 24);
        assert_eq!(youdao_input(q), expected);
    }

    #[test]
    fn sha256_known_vector() {
        assert_eq!(
            sha256_hex("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
