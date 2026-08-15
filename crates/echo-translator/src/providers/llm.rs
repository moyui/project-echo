use std::collections::VecDeque;
use std::sync::Mutex;

use async_trait::async_trait;
use echo_core::Lang;
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::config::{LlmConfig, GlossaryEntry};
use crate::providers::http_client;
use crate::{Result, TranslateError, Translator};

/// 翻译方案（借鉴 LunaTranslator 的游戏流式与 manga-image-translator 的整页思路）
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LlmProfile {
    /// 游戏（galgame）：逐句对话为主，说话人分离 + 较长历史上下文 + 口语化连贯
    Gal,
    /// 漫画：整页气泡为主，较短的上下文 + 先通读整页 + 译文简短
    Manga,
}

/// 历史上下文条数（原文+译文对），按方案区分
const HISTORY_LIMIT_GAL: usize = 12;
const HISTORY_LIMIT_MANGA: usize = 4;

/// 说话人分离结果：(说话人, 正文)
struct SpeakerSplit {
    name: String,
    body: String,
}

/// 任意 OpenAI 兼容 `/chat/completions` 端点的 LLM 翻译。
///
/// 协议（manga-image-translator 同款行 ID + CoT 思路，比裸 JSON 对小模型更友好）：
/// - 输入：`<|0|>文本`、`<|1|>文本`…编号行，可附前文参考
/// - 提示：要求先通读全部行理解角色/剧情，再逐行翻译
/// - 输出：每行 `<|id|>译文`，ID 一一对应；解析宽容——缺行回填原文，不整体作废
pub struct LlmProvider {
    endpoint: String,
    api_key: String,
    model: String,
    glossary: Vec<GlossaryEntry>,
    extra_system_prompt: Option<String>,
    profile: LlmProfile,
    client: reqwest::Client,
    history: Mutex<VecDeque<(String, String)>>,
}

impl LlmProvider {
    pub fn new(config: LlmConfig, glossary: Vec<GlossaryEntry>) -> Self {
        let profile = match config.profile.as_deref() {
            Some("manga") => LlmProfile::Manga,
            _ => LlmProfile::Gal,
        };
        Self {
            endpoint: config.endpoint.trim_end_matches('/').to_string(),
            api_key: config.api_key,
            model: config.model,
            glossary,
            extra_system_prompt: config.extra_system_prompt,
            profile,
            client: http_client(),
            history: Mutex::new(VecDeque::new()),
        }
    }

    pub fn profile(&self) -> LlmProfile {
        self.profile
    }

    fn history_limit(&self) -> usize {
        match self.profile {
            LlmProfile::Gal => HISTORY_LIMIT_GAL,
            LlmProfile::Manga => HISTORY_LIMIT_MANGA,
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
        let scenario = match self.profile {
            LlmProfile::Gal => format!(
                "你是资深的{}→{}游戏（galgame）文本翻译。用户会给出带编号 <|id|> 的文本行，多为角色对话。",
                lang_name(from),
                lang_name(to)
            ),
            LlmProfile::Manga => format!(
                "你是资深的{}→{}漫画翻译。用户会给出带编号 <|id|> 的文本行，来自同一页漫画的气泡、旁白与拟声词。",
                lang_name(from),
                lang_name(to)
            ),
        };
        let mut prompt = format!(
            "{scenario}\
请先通读全部行，理解角色、场景与剧情走向，再逐行翻译成自然流畅、符合中文表达习惯的{}。\
游戏对话保持角色语气与前后连贯、口语化；漫画气泡译文务必简短（气泡空间有限）、语气生动、拟声词给出中文等效。\
专名与数字不要改动。\
输出格式：每行 <|编号|>译文，编号与输入一一对应，除此之外不要输出任何内容。",
            lang_name(to),
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

    /** 术语表是否命中任一待翻译文本（未命中时由 translate_batch 决定不注入） */
    fn glossary_hit(&self, texts: &[String]) -> bool {
        self.glossary.iter().any(|entry| texts.iter().any(|t| t.contains(&entry.source)))
    }

    /** 用户消息：可选前文参考 + 带编号的待翻译行 */
    fn build_user_content(&self, texts: &[String]) -> String {
        let mut content = String::new();
        if let Ok(history) = self.history.lock() {
            if !history.is_empty() {
                content.push_str("## 前文参考（帮助理解剧情，不要翻译、不要输出它们）\n");
                for (source, translated) in history.iter() {
                    content.push_str(source.trim());
                    content.push_str("\n=> ");
                    content.push_str(translated.trim());
                    content.push('\n');
                }
                content.push('\n');
            }
        }
        content.push_str("## 待翻译（逐行输出 <|编号|>译文）\n");
        for (index, text) in texts.iter().enumerate() {
            content.push_str(&format!("<|{index}|>{}\n", text));
        }
        content
    }

    /// 说话人分离（仅 GAL 方案）：支持「名前」正文 / 名前：正文 两种形态。
    /// 纯数字（如时间 11:48）、含 URL 或过长前缀不拆分。
    fn split_speaker(text: &str) -> Option<SpeakerSplit> {
        let t = text.trim();
        if t.len() < 3 || t.contains("://") {
            return None;
        }
        // 「名前」正文
        if let Some(rest) = t.strip_prefix('「') {
            if let Some(end) = rest.find('」') {
                let name = &rest[..end];
                let body = rest[end + 3..].trim_start_matches(['：', ':', ' ']).trim();
                if valid_speaker(name, body) {
                    return Some(SpeakerSplit { name: name.to_string(), body: body.to_string() });
                }
            }
        }
        // 名前：正文
        if let Some(idx) = t.find(['：', ':']) {
            let sep_len = t[idx..].chars().next().map(|c| c.len_utf8()).unwrap_or(1);
            let name = t[..idx].trim();
            let body = t[idx + sep_len..].trim();
            if valid_speaker(name, body) {
                return Some(SpeakerSplit { name: name.to_string(), body: body.to_string() });
            }
        }
        None
    }
}

fn valid_speaker(name: &str, body: &str) -> bool {
    if name.is_empty() || body.is_empty() {
        return false;
    }
    if name.chars().count() > 12 {
        return false;
    }
    // 纯数字/时间戳形态不拆（11:48）
    if name.chars().all(|c| c.is_ascii_digit()) {
        return false;
    }
    // 含路径/URL 特征不拆
    if name.contains('/') || name.contains('\\') || name.contains('.') {
        return false;
    }
    // 前缀里带句末标点说明是完整句子，不是人名
    if name.contains(['。', '！', '？', '，', '…']) {
        return false;
    }
    true
}

/// 解析模型的行 ID 输出：`<|0|>译文`（宽容：忽略无法解析的行）
fn parse_id_lines(output: &str) -> Vec<(usize, String)> {
    let cleaned = strip_code_fences(output);
    cleaned
        .lines()
        .filter_map(|line| {
            let l = line.trim();
            let rest = l.strip_prefix("<|")?;
            let (id, text) = rest.split_once("|>")?;
            let id: usize = id.trim().parse().ok()?;
            Some((id, text.trim().to_string()))
        })
        .collect()
}

/// 剥掉模型可能加的 ```json 围栏。
pub fn strip_code_fences(content: &str) -> String {
    let trimmed = content.trim();
    let without_prefix = if let Some(rest) = trimmed.strip_prefix("```") {
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

        // 说话人分离（仅 GAL）：正文送去翻译，说话人原样保留
        let speakers: Vec<Option<SpeakerSplit>> = texts
            .iter()
            .map(|t| {
                if self.profile == LlmProfile::Gal {
                    Self::split_speaker(t)
                } else {
                    None
                }
            })
            .collect();
        let bodies: Vec<String> = texts
            .iter()
            .zip(&speakers)
            .map(|(t, s)| s.as_ref().map(|sp| sp.body.clone()).unwrap_or_else(|| t.clone()))
            .collect();

        // 术语表未命中则不注入（LunaTranslator 同款降噪策略）
        let mut system = self.system_prompt(from, to);
        if !self.glossary.is_empty() && !self.glossary_hit(&bodies) {
            // 重写提示词：去掉术语表段
            if let Some(pos) = system.find("\n术语表（必须遵守）：") {
                let tail = system.find("\n附加要求：").unwrap_or(system.len());
                let extra = if tail < system.len() { system[tail..].to_string() } else { String::new() };
                system = format!("{}{}", &system[..pos], extra);
            }
        }

        let url = if self.endpoint.ends_with("/chat/completions") {
            self.endpoint.clone()
        } else {
            format!("{}/chat/completions", self.endpoint)
        };
        let body = json!({
            "model": self.model,
            "temperature": 0.2,
            "messages": [
                ChatMessage { role: "system", content: system },
                ChatMessage { role: "user", content: self.build_user_content(&bodies) },
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

        // 宽容解析：按 ID 回填，缺行回填原文
        let id_lines = parse_id_lines(&content);
        if id_lines.is_empty() {
            return Err(TranslateError::Parse(format!("LLM 输出无编号行:\n{content}")));
        }
        let mut slots: Vec<Option<String>> = vec![None; bodies.len()];
        let mut duplicate_ids = 0usize;
        for (id, text) in id_lines {
            if id < slots.len() {
                if slots[id].is_some() {
                    duplicate_ids += 1;
                }
                slots[id] = Some(text);
            }
        }
        if duplicate_ids > 0 && duplicate_ids >= slots.len() / 2 {
            return Err(TranslateError::Parse("LLM 输出编号严重重复".into()));
        }
        let mut results: Vec<String> = Vec::with_capacity(bodies.len());
        for (index, slot) in slots.into_iter().enumerate() {
            let translated = slot.unwrap_or_else(|| bodies[index].clone());
            // 重组说话人
            match &speakers[index] {
                Some(sp) => results.push(format!("{}：{}", sp.name, translated)),
                None => results.push(translated),
            }
        }

        // 记录对话历史（重组后的完整原文/译文对）
        if let Ok(mut history) = self.history.lock() {
            for (source, translated) in texts.iter().zip(results.iter()) {
                if source.trim().is_empty() {
                    continue;
                }
                history.push_back((source.clone(), translated.clone()));
            }
            while history.len() > self.history_limit() {
                history.pop_front();
            }
        }
        Ok(results)
    }

    fn name(&self) -> &str {
        "llm"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_id_lines_with_fence_and_missing() {
        let output = "```json\n<|0|>你好\n<|2|>世界\n```";
        let parsed = parse_id_lines(output);
        assert_eq!(parsed, vec![(0, "你好".to_string()), (2, "世界".to_string())]);
    }

    #[test]
    fn parses_plain_id_lines() {
        let output = "<|0|> 第一行 \n<|1|>第二行\n无关行会被忽略";
        let parsed = parse_id_lines(output);
        assert_eq!(parsed.len(), 2);
        assert_eq!(parsed[0].1, "第一行");
        assert_eq!(parsed[1].1, "第二行");
    }

    #[test]
    fn speaker_split_brackets() {
        let sp = LlmProvider::split_speaker("「真奈」：こんにちは、先輩").unwrap();
        assert_eq!(sp.name, "真奈");
        assert_eq!(sp.body, "こんにちは、先輩");
    }

    #[test]
    fn speaker_split_colon() {
        let sp = LlmProvider::split_speaker("真奈：また明日").unwrap();
        assert_eq!(sp.name, "真奈");
        assert_eq!(sp.body, "また明日");
    }

    #[test]
    fn speaker_split_rejects_time_and_urls() {
        assert!(LlmProvider::split_speaker("11:48").is_none());
        assert!(LlmProvider::split_speaker("https://example.com：测试").is_none());
        // 纯正文无分隔符
        assert!(LlmProvider::split_speaker("ただのセリフです").is_none());
    }

    #[test]
    fn glossary_hit_detection() {
        let provider = LlmProvider::new(
            LlmConfig {
                endpoint: "http://localhost".into(),
                api_key: String::new(),
                model: "test".into(),
                extra_system_prompt: None,
                profile: Some("gal".into()),
            },
            vec![GlossaryEntry { source: "エルフ".into(), target: "精灵".into() }],
        );
        assert!(provider.glossary_hit(&["エルフの森へ".to_string()]));
        assert!(!provider.glossary_hit(&["ただの文".to_string()]));
    }

    #[test]
    fn strips_fences() {
        assert_eq!(strip_code_fences("```json\n[\"a\"]\n```"), "[\"a\"]");
        assert_eq!(strip_code_fences("[\"a\"]"), "[\"a\"]");
    }
}
