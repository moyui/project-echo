use thiserror::Error;

#[derive(Error, Debug)]
pub enum TranslateError {
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),

    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),

    #[error("invalid config: {0}")]
    Config(String),

    #[error("provider api error: {0}")]
    Api(String),

    #[error("unexpected provider response: {0}")]
    Parse(String),

    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
}

pub type Result<T> = std::result::Result<T, TranslateError>;
