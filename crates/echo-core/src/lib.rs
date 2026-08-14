//! echo-core：Echo 全平台共享的领域类型与「OCR 文本 → 翻译」管线。
//!
//! 设计约束：控制流留在各端原生壳（安卓前台服务 / iOS 监听器 / PC Tauri），
//! 本 crate 只提供无生命周期的纯函数与显式状态（`PipelineState` 由调用方持有）。

use std::collections::hash_map::DefaultHasher;
use std::hash::{Hash, Hasher};

use lru::LruCache;
use serde::{Deserialize, Serialize};
use schemars::JsonSchema;

/// 语言。`Auto` 表示让翻译服务自动检测。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, JsonSchema, Default)]
pub enum Lang {
    #[default]
    Auto,
    Ja,
    ZhHans,
    ZhHant,
    En,
    Ko,
}

/// OCR 文本块在图像/屏幕中的位置，单位为像素。
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct BBox {
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
}

impl BBox {
    pub fn center(&self) -> (f32, f32) {
        (self.x + self.width / 2.0, self.y + self.height / 2.0)
    }

    pub fn area(&self) -> f32 {
        self.width * self.height
    }

    /// 交并比，用于跨帧判断"同一区域"。
    pub fn iou(&self, other: &BBox) -> f32 {
        let (x1, y1) = (self.x.max(other.x), self.y.max(other.y));
        let (x2, y2) =
            ((self.x + self.width).min(other.x + other.width), (self.y + self.height).min(other.y + other.height));
        if x2 <= x1 || y2 <= y1 {
            return 0.0;
        }
        let inter = (x2 - x1) * (y2 - y1);
        let union = self.area() + other.area() - inter;
        if union <= 0.0 {
            0.0
        } else {
            inter / union
        }
    }
}

/// 一个 OCR 识别出的文本块：文本 + 可选位置 + 可选语言提示。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, JsonSchema)]
pub struct TextBlock {
    pub text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bbox: Option<BBox>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub lang_hint: Option<Lang>,
}

impl TextBlock {
    pub fn new(text: impl Into<String>) -> Self {
        Self { text: text.into(), bbox: None, lang_hint: None }
    }

    pub fn with_bbox(mut self, bbox: BBox) -> Self {
        self.bbox = Some(bbox);
        self
    }

    pub fn with_lang(mut self, lang: Lang) -> Self {
        self.lang_hint = Some(lang);
        self
    }
}

/// 归一化文本：去首尾空白、压缩连续空白为单空格、剔除零宽字符。
/// 同一文案经 OCR 得到不同空白布局时应命中同一缓存。
pub fn normalize_text(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    let mut in_ws = false;
    for ch in input.chars() {
        // 零宽与控制类噪声
        if matches!(ch, '\u{200B}' | '\u{200C}' | '\u{200D}' | '\u{FEFF}' | '\u{00AD}') {
            continue;
        }
        if ch.is_whitespace() {
            if !in_ws {
                out.push(' ');
                in_ws = true;
            }
        } else {
            out.push(ch);
            in_ws = false;
        }
    }
    out.trim().to_string()
}

/// 文本指纹。仅保证同进程内稳定（用于内存态去重），不要用于持久化 key。
pub fn fingerprint(text: &str) -> u64 {
    let normalized = normalize_text(text);
    let mut hasher = DefaultHasher::new();
    normalized.hash(&mut hasher);
    hasher.finish()
}

/// 管线状态：已翻译文本的 LRU 缓存（指纹 → 译文）。
/// 由原生壳持有并跨帧复用；`Gateway`（echo-translator）另有一层 provider 缓存，两层互不感知。
pub struct PipelineState {
    translations: LruCache<u64, String>,
}

impl Default for PipelineState {
    fn default() -> Self {
        Self::new(512)
    }
}

impl PipelineState {
    pub fn new(capacity: usize) -> Self {
        Self { translations: LruCache::new(capacity.max(1).try_into().expect("capacity > 0")) }
    }

    /// 处理一帧 OCR 结果：
    /// - 空白/噪声文本直接丢弃；
    /// - 译文缓存命中的块进入 `resolved`（调用方直接上屏）；
    /// - 其余进入 `pending`（调用方送 `Gateway` 翻译后调用 [`PipelineState::store`] 回填）。
    /// `pending` 内部已按指纹去重（同帧重复文本只出现一次）。
    pub fn process(&mut self, blocks: Vec<TextBlock>) -> PipelineOutput {
        let mut output = PipelineOutput::default();
        let mut seen_in_batch = std::collections::HashSet::new();

        for block in blocks {
            let normalized = normalize_text(&block.text);
            if normalized.is_empty() {
                continue;
            }
            let fp = fingerprint(&block.text);
            if let Some(translation) = self.translations.get(&fp) {
                output.resolved.push((block, translation.clone()));
            } else if seen_in_batch.insert(fp) {
                output.pending.push(block);
            }
        }
        output
    }

    /// 回填译文（文本会先归一化再取指纹）。
    pub fn store(&mut self, text: &str, translation: impl Into<String>) {
        self.translations.put(fingerprint(text), translation.into());
    }

    pub fn cache_len(&self) -> usize {
        self.translations.len()
    }

    pub fn clear(&mut self) {
        self.translations.clear();
    }
}

#[derive(Debug, Default)]
pub struct PipelineOutput {
    /// 需要送去翻译的文本块（已按文本指纹去重）。
    pub pending: Vec<TextBlock>,
    /// 缓存命中的文本块及其译文。
    pub resolved: Vec<(TextBlock, String)>,
}

impl PipelineOutput {
    pub fn is_empty(&self) -> bool {
        self.pending.is_empty() && self.resolved.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_text_is_pending() {
        let mut state = PipelineState::default();
        let out = state.process(vec![TextBlock::new("こんにちは")]);
        assert_eq!(out.pending.len(), 1);
        assert_eq!(out.resolved.len(), 0);
    }

    #[test]
    fn stored_text_resolves_from_cache() {
        let mut state = PipelineState::default();
        state.store("こんにちは", "你好");

        let out = state.process(vec![TextBlock::new("こんにちは")]);
        assert!(out.pending.is_empty());
        assert_eq!(out.resolved.len(), 1);
        assert_eq!(out.resolved[0].1, "你好");
    }

    #[test]
    fn whitespace_variants_hit_same_entry() {
        let mut state = PipelineState::default();
        state.store("hello world", "你好，世界");

        // OCR 常见的换行/多空格差异不应导致重复翻译
        let out = state.process(vec![TextBlock::new("  hello   world\n")]);
        assert!(out.pending.is_empty());
        assert_eq!(out.resolved[0].1, "你好，世界");
    }

    #[test]
    fn blank_text_is_dropped() {
        let mut state = PipelineState::default();
        let out = state.process(vec![TextBlock::new("   \n\t "), TextBlock::new("\u{200B}")]);
        assert!(out.is_empty());
    }

    #[test]
    fn duplicate_text_in_one_batch_dedupes() {
        let mut state = PipelineState::default();
        let out = state.process(vec![
            TextBlock::new("同じ文").with_bbox(BBox { x: 0.0, y: 0.0, width: 10.0, height: 10.0 }),
            TextBlock::new("同じ文").with_bbox(BBox { x: 50.0, y: 80.0, width: 10.0, height: 10.0 }),
        ]);
        assert_eq!(out.pending.len(), 1);
    }

    #[test]
    fn changed_region_only_pending_for_changed_text() {
        let mut state = PipelineState::default();
        let bbox = BBox { x: 0.0, y: 0.0, width: 100.0, height: 20.0 };
        state.store("旧テキスト", "旧文本");
        state.store("変わらない", "不变");

        // 同一区域换成了新文案：只有新文案 pending
        let out = state.process(vec![
            TextBlock::new("新テキスト").with_bbox(bbox),
            TextBlock::new("変わらない").with_bbox(BBox { x: 0.0, y: 40.0, width: 100.0, height: 20.0 }),
        ]);
        state.store("新テキスト", "新文本");

        let out2 = state.process(vec![
            TextBlock::new("新テキスト").with_bbox(bbox),
            TextBlock::new("変わらない").with_bbox(BBox { x: 0.0, y: 40.0, width: 100.0, height: 20.0 }),
        ]);
        assert_eq!(out.pending.len(), 1);
        assert!(out2.pending.is_empty());
        assert_eq!(out2.resolved.len(), 2);
    }

    #[test]
    fn lru_eviction() {
        let mut state = PipelineState::new(1);
        state.store("a", "甲");
        state.store("b", "乙"); // 容量 1，"a" 被淘汰
        let out = state.process(vec![TextBlock::new("a"), TextBlock::new("b")]);
        assert_eq!(out.resolved.len(), 1);
        assert_eq!(out.pending.len(), 1);
    }

    #[test]
    fn bbox_iou() {
        let a = BBox { x: 0.0, y: 0.0, width: 10.0, height: 10.0 };
        let b = BBox { x: 5.0, y: 0.0, width: 10.0, height: 10.0 };
        let none = BBox { x: 100.0, y: 100.0, width: 5.0, height: 5.0 };
        // 交集 5×10=50，并集 100+100-50=150
        assert!((a.iou(&b) - (50.0 / 150.0)).abs() < 1e-6);
        assert_eq!(a.iou(&none), 0.0);
        assert!((a.iou(&a) - 1.0).abs() < 1e-6);
    }
}
