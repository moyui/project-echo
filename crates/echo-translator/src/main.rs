use std::io::BufRead;
use std::path::PathBuf;

use clap::{Parser, Subcommand, ValueEnum};
use echo_core::Lang;
use echo_translator::{Gateway, ProviderKind, TranslatorConfig};

#[derive(Parser)]
#[command(name = "echo-translator", about = "Echo 翻译网关 CLI（调试 / 脱端演示用）", version)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// 翻译一段或多段文本
    Translate {
        /// 文本；全部省略时从 stdin 逐行读取
        text: Vec<String>,
        /// 配置文件路径（JSON），默认尝试 ./echo-translator.config.json
        #[arg(short, long)]
        config: Option<PathBuf>,
        /// 覆盖配置中的 provider
        #[arg(short, long)]
        provider: Option<CliProvider>,
        /// 覆盖目标语言
        #[arg(short, long)]
        to: Option<CliLang>,
    },
    /// 输出 TranslatorConfig 的 JSON Schema（供 packages/config-schema 生成 TS 类型）
    Schema,
}

#[derive(ValueEnum, Clone, Copy)]
enum CliProvider {
    Mock,
    Deepl,
    Baidu,
    Youdao,
    Llm,
}

impl From<CliProvider> for ProviderKind {
    fn from(value: CliProvider) -> Self {
        match value {
            CliProvider::Mock => ProviderKind::Mock,
            CliProvider::Deepl => ProviderKind::DeepL,
            CliProvider::Baidu => ProviderKind::Baidu,
            CliProvider::Youdao => ProviderKind::Youdao,
            CliProvider::Llm => ProviderKind::Llm,
        }
    }
}

#[derive(ValueEnum, Clone, Copy)]
enum CliLang {
    Auto,
    Ja,
    Zh,
    ZhT,
    En,
    Ko,
}

impl From<CliLang> for Lang {
    fn from(value: CliLang) -> Self {
        match value {
            CliLang::Auto => Lang::Auto,
            CliLang::Ja => Lang::Ja,
            CliLang::Zh => Lang::ZhHans,
            CliLang::ZhT => Lang::ZhHant,
            CliLang::En => Lang::En,
            CliLang::Ko => Lang::Ko,
        }
    }
}

fn load_config(path: Option<&PathBuf>) -> TranslatorConfig {
    let effective = path.cloned().or_else(|| {
        let default = PathBuf::from("echo-translator.config.json");
        default.exists().then_some(default)
    });
    let Some(path) = effective else {
        return TranslatorConfig::default();
    };
    match std::fs::read_to_string(&path) {
        Ok(content) => match serde_json::from_str(&content) {
            Ok(config) => config,
            Err(err) => {
                eprintln!("配置文件 {} 解析失败：{err}", path.display());
                std::process::exit(1);
            }
        },
        Err(err) => {
            eprintln!("配置文件 {} 读取失败：{err}", path.display());
            std::process::exit(1);
        }
    }
}

#[tokio::main]
async fn main() {
    let cli = Cli::parse();
    match cli.command {
        Command::Schema => {
            let schema = schemars::schema_for!(TranslatorConfig);
            println!("{}", serde_json::to_string_pretty(&schema).expect("schema 序列化失败"));
        }
        Command::Translate { text, config, provider, to } => {
            let mut cfg = load_config(config.as_ref());
            if let Some(provider) = provider {
                cfg.provider = provider.into();
            }
            if let Some(to) = to {
                cfg.target_lang = to.into();
            }

            let mut texts = text;
            if texts.is_empty() {
                let stdin = std::io::stdin();
                texts = stdin.lock().lines().map_while(std::result::Result::ok).collect();
            }
            if texts.is_empty() {
                eprintln!("没有待翻译文本");
                std::process::exit(1);
            }

            let mut gateway = match Gateway::new(cfg) {
                Ok(gateway) => gateway,
                Err(err) => {
                    eprintln!("初始化失败：{err}");
                    std::process::exit(1);
                }
            };

            match gateway.translate(&texts).await {
                Ok(translations) => {
                    for (source, translated) in texts.iter().zip(&translations) {
                        if source.trim().is_empty() {
                            continue;
                        }
                        println!("{source}");
                        println!("  → {translated}");
                    }
                    if let Err(err) = gateway.save_cache() {
                        eprintln!("磁盘缓存写入失败：{err}");
                    }
                }
                Err(err) => {
                    eprintln!("翻译失败：{err}");
                    std::process::exit(1);
                }
            }
        }
    }
}
