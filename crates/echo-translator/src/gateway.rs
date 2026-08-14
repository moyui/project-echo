use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::Arc;

use echo_core::{fingerprint, normalize_text};
use lru::LruCache;
use serde::{Deserialize, Serialize};

use crate::config::{apply_glossary, ProviderKind, TranslatorConfig};
use crate::error::{Result, TranslateError};
use crate::providers::{BaiduProvider, DeepLProvider, LlmProvider, MockProvider, YoudaoProvider};
use crate::Translator;

/// 翻译网关：缓存 + 术语表 + provider 路由。
/// 固定使用构建时配置里的源/目标语言；换语言请用新配置重建。
///
/// 缓存条目为（归一化原文, 译文），LRU 淘汰同时带走原文，
/// 磁盘持久化因此不需要跨进程稳定的指纹（加载时重算）。
pub struct Gateway {
    config: TranslatorConfig,
    provider: Arc<dyn Translator>,
    cache: LruCache<u64, (String, String)>,
    disk_path: Option<PathBuf>,
}

impl Gateway {
    /// 按配置构建（provider 依 `config.provider` 路由）。
    pub fn new(config: TranslatorConfig) -> Result<Self> {
        let provider: Arc<dyn Translator> = match config.provider {
            ProviderKind::Mock => Arc::new(MockProvider),
            ProviderKind::DeepL => {
                let c = config
                    .deepl
                    .clone()
                    .ok_or_else(|| TranslateError::Config("provider = deepl，但配置里没有 deepl 段".into()))?;
                Arc::new(DeepLProvider::new(c))
            }
            ProviderKind::Baidu => {
                let c = config
                    .baidu
                    .clone()
                    .ok_or_else(|| TranslateError::Config("provider = baidu，但配置里没有 baidu 段".into()))?;
                Arc::new(BaiduProvider::new(c))
            }
            ProviderKind::Youdao => {
                let c = config
                    .youdao
                    .clone()
                    .ok_or_else(|| TranslateError::Config("provider = youdao，但配置里没有 youdao 段".into()))?;
                Arc::new(YoudaoProvider::new(c))
            }
            ProviderKind::Llm => {
                let c = config
                    .llm
                    .clone()
                    .ok_or_else(|| TranslateError::Config("provider = llm，但配置里没有 llm 段".into()))?;
                Arc::new(LlmProvider::new(c, config.glossary.clone()))
            }
        };
        Ok(Self::with_provider(config, provider))
    }

    /// 注入自定义 provider（测试/扩展用）。
    pub fn with_provider(config: TranslatorConfig, provider: Arc<dyn Translator>) -> Self {
        let disk_path = config.cache.disk_path.as_ref().map(PathBuf::from);
        let capacity = config.cache.capacity.max(1).try_into().expect("capacity > 0");
        let mut gateway = Self { config, provider, cache: LruCache::new(capacity), disk_path };
        gateway.load_cache();
        gateway
    }

    /// 翻译一批文本：归一化去重 → 缓存查 miss → provider 批量请求 → 回填缓存 → 按输入顺序返回。
    /// 同一批里的重复文本只请求一次；空白文本原样返回空串。
    pub async fn translate(&mut self, texts: &[String]) -> Result<Vec<String>> {
        if texts.is_empty() {
            return Ok(Vec::new());
        }

        let entries: Vec<(u64, String)> =
            texts.iter().map(|t| (fingerprint(t), normalize_text(t))).collect();

        let mut seen = HashSet::new();
        let misses: Vec<(u64, String)> = entries
            .iter()
            .filter(|(_, normalized)| !normalized.is_empty())
            .filter(|(fp, _)| !self.cache.contains(fp))
            .filter(|(fp, _)| seen.insert(*fp))
            .cloned()
            .collect();

        if !misses.is_empty() {
            let prepared: Vec<String> =
                misses.iter().map(|(_, text)| apply_glossary(text, &self.config.glossary)).collect();
            let translations = self
                .provider
                .translate_batch(&prepared, self.config.source_lang, self.config.target_lang)
                .await?;
            for ((fp, normalized), translation) in misses.iter().zip(translations) {
                self.cache.put(*fp, (normalized.clone(), translation));
            }
        }

        Ok(entries
            .into_iter()
            .map(|(fp, normalized)| {
                if normalized.is_empty() {
                    String::new()
                } else {
                    self.cache.get(&fp).map(|(_, translation)| translation.clone()).unwrap_or_default()
                }
            })
            .collect())
    }

    pub fn provider_name(&self) -> &str {
        self.provider.name()
    }

    pub fn config(&self) -> &TranslatorConfig {
        &self.config
    }

    pub fn cache_len(&self) -> usize {
        self.cache.len()
    }

    /// 持久化磁盘缓存（配置了 `cache.disk_path` 才会写文件）。条目按缓存的新近度排序。
    pub fn save_cache(&self) -> std::io::Result<()> {
        let Some(path) = &self.disk_path else {
            return Ok(());
        };
        let entries: Vec<CacheEntry> = self
            .cache
            .iter()
            .map(|(_, (text, translation))| CacheEntry { text: text.clone(), translation: translation.clone() })
            .collect();
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                std::fs::create_dir_all(parent)?;
            }
        }
        std::fs::write(path, serde_json::to_vec_pretty(&entries)?)
    }

    fn load_cache(&mut self) {
        let Some(path) = self.disk_path.clone() else {
            return;
        };
        let Ok(content) = std::fs::read(&path) else {
            return;
        };
        let Ok(entries) = serde_json::from_slice::<Vec<CacheEntry>>(&content) else {
            return;
        };
        // 倒序插入，让文件里最旧的先进 LRU、最新的最后进（保持新近度语义）
        for entry in entries.into_iter().rev() {
            let normalized = normalize_text(&entry.text);
            if normalized.is_empty() {
                continue;
            }
            self.cache.put(fingerprint(&entry.text), (normalized, entry.translation));
        }
    }
}

#[derive(Serialize, Deserialize)]
struct CacheEntry {
    text: String,
    translation: String,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::GlossaryEntry;
    use async_trait::async_trait;
    use echo_core::Lang;
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// 记录调用次数与最近一次输入的假 provider。
    struct CountingMock {
        calls: Arc<AtomicUsize>,
        received: std::sync::Mutex<Vec<String>>,
        prefix: &'static str,
    }

    impl CountingMock {
        fn new(prefix: &'static str) -> (Arc<Self>, Arc<AtomicUsize>) {
            let calls = Arc::new(AtomicUsize::new(0));
            (Arc::new(Self { calls: calls.clone(), received: std::sync::Mutex::new(vec![]), prefix }), calls)
        }
    }

    #[async_trait]
    impl Translator for CountingMock {
        async fn translate_batch(&self, texts: &[String], _from: Lang, _to: Lang) -> Result<Vec<String>> {
            self.calls.fetch_add(texts.len(), Ordering::SeqCst);
            *self.received.lock().unwrap() = texts.to_vec();
            Ok(texts.iter().map(|t| format!("{}{}", self.prefix, t)).collect())
        }

        fn name(&self) -> &str {
            "counting-mock"
        }
    }

    fn test_config() -> TranslatorConfig {
        TranslatorConfig::default()
    }

    #[tokio::test]
    async fn second_call_hits_cache() {
        let (mock, calls) = CountingMock::new("[甲]");
        let mut gateway = Gateway::with_provider(test_config(), mock);

        let first = gateway.translate(&["こんにちは".into(), "世界".into()]).await.unwrap();
        assert_eq!(first, vec!["[甲]こんにちは", "[甲]世界"]);
        assert_eq!(calls.load(Ordering::SeqCst), 2);

        let second = gateway.translate(&["こんにちは".into(), "世界".into()]).await.unwrap();
        assert_eq!(second, first);
        assert_eq!(calls.load(Ordering::SeqCst), 2, "缓存命中不应再调 provider");
    }

    #[tokio::test]
    async fn duplicates_in_one_batch_request_once() {
        let (mock, calls) = CountingMock::new("[乙]");
        let mut gateway = Gateway::with_provider(test_config(), mock);

        let out = gateway.translate(&["同じ".into(), "違う".into(), "同じ".into()]).await.unwrap();
        assert_eq!(calls.load(Ordering::SeqCst), 2, "重复文本只请求一次");
        assert_eq!(out.len(), 3);
        assert_eq!(out[0], out[2], "重复位置结果一致");
        assert_eq!(out[0], "[乙]同じ");
    }

    #[tokio::test]
    async fn order_preserved() {
        let (mock, _) = CountingMock::new("[丙]");
        let mut gateway = Gateway::with_provider(test_config(), mock);

        let out = gateway.translate(&["a".into(), "b".into(), "c".into()]).await.unwrap();
        assert_eq!(out, vec!["[丙]a", "[丙]b", "[丙]c"]);
    }

    #[tokio::test]
    async fn blank_texts_return_empty_without_provider_call() {
        let (mock, calls) = CountingMock::new("[丁]");
        let mut gateway = Gateway::with_provider(test_config(), mock);

        let out = gateway.translate(&["   ".into()]).await.unwrap();
        assert_eq!(out, vec![String::new()]);
        assert_eq!(calls.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn glossary_applied_before_provider() {
        let config = TranslatorConfig {
            glossary: vec![GlossaryEntry { source: "エルフ".into(), target: "精灵".into() }],
            ..test_config()
        };
        let (mock, _) = CountingMock::new("[戊]");
        let received = {
            // 提前克隆记录通道：直接用 mock 内部 received，在翻译后读取
            let m = mock.clone();
            let mut gateway = Gateway::with_provider(config, mock);
            gateway.translate(&["エルフの森".into()]).await.unwrap();
            let received = m.received.lock().unwrap().clone();
            received
        };
        assert_eq!(received, vec!["精灵の森".to_string()], "术语表应在请求 provider 前替换原文");
    }

    #[tokio::test]
    async fn switching_provider_changes_output() {
        let mut a = Gateway::with_provider(test_config(), CountingMock::new("[A]").0);
        let mut b = Gateway::with_provider(test_config(), CountingMock::new("[B]").0);
        let out_a = a.translate(&["テスト".into()]).await.unwrap();
        let out_b = b.translate(&["テスト".into()]).await.unwrap();
        assert_eq!(out_a[0], "[A]テスト");
        assert_eq!(out_b[0], "[B]テスト");
    }

    #[tokio::test]
    async fn disk_cache_roundtrip() {
        let dir = std::env::temp_dir().join(format!("echo-test-{}", std::process::id()));
        let cache_path = dir.join("cache.json");
        let config = TranslatorConfig {
            cache: crate::config::CacheConfig {
                capacity: 16,
                disk_path: Some(cache_path.to_string_lossy().to_string()),
            },
            ..test_config()
        };

        let (mock, calls) = CountingMock::new("[己]");
        let mut gateway = Gateway::with_provider(config, mock);
        gateway.translate(&["永続化テスト".into()]).await.unwrap();
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        gateway.save_cache().unwrap();

        // 新实例从磁盘加载，不再请求 provider
        let (mock2, calls2) = CountingMock::new("[庚]");
        let mut gateway2 = Gateway::with_provider(
            TranslatorConfig {
                cache: crate::config::CacheConfig {
                    capacity: 16,
                    disk_path: Some(cache_path.to_string_lossy().to_string()),
                },
                ..test_config()
            },
            mock2,
        );
        let out = gateway2.translate(&["永続化テスト".into()]).await.unwrap();
        assert_eq!(out[0], "[己]永続化テスト", "应命中磁盘缓存里的旧译文");
        assert_eq!(calls2.load(Ordering::SeqCst), 0);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn missing_provider_config_is_error() {
        let err = Gateway::new(TranslatorConfig { provider: ProviderKind::DeepL, ..test_config() });
        assert!(err.is_err());
    }
}
