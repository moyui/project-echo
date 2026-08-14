pub mod baidu;
pub mod deepl;
pub mod llm;
pub mod mock;
pub mod youdao;

pub use baidu::BaiduProvider;
pub use deepl::DeepLProvider;
pub use llm::LlmProvider;
pub use mock::MockProvider;
pub use youdao::YoudaoProvider;

/// 统一的 HTTP 客户端：连接 10s、单请求总时长 90s，防止卡死上游拖住调用方
pub(crate) fn http_client() -> reqwest::Client {
    reqwest::Client::builder()
        .connect_timeout(std::time::Duration::from_secs(10))
        .timeout(std::time::Duration::from_secs(90))
        .build()
        .unwrap_or_default()
}
