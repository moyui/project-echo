//! 模型文件的按需下载与缓存（%APPDATA%\echo\models）。
//! 国内网络优先 hf-mirror，失败回退 huggingface 官方。

use anyhow::{Context, Result};
use std::io::Read;
use std::path::{Path, PathBuf};

/// manga-ocr 管线（检测 + 识别）所需文件清单：文件名 → 下载地址
/// 识别模型用 onnx-community 从原版 kha-white/manga-ocr-base 导出的 int8 量化版
/// （ViT-base encoder 87MB + 2 层 BERT decoder 30MB）；vocab 取自原版仓库。
const MANGA_OCR_FILES: &[(&str, &str)] = &[
    (
        "manga-ocr-encoder-int8.onnx",
        "https://hf-mirror.com/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/encoder_model_int8.onnx",
    ),
    (
        "manga-ocr-decoder-int8.onnx",
        "https://hf-mirror.com/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/decoder_model_int8.onnx",
    ),
    (
        "manga-ocr-vocab.txt",
        "https://hf-mirror.com/kha-white/manga-ocr-base/resolve/main/vocab.txt",
    ),
    (
        "ppocr-det.onnx",
        "https://hf-mirror.com/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx",
    ),
];

/// 官方域名回退（hf-mirror 失败时）
const FALLBACK_HOST: &str = "huggingface.co";

pub fn default_model_dir() -> PathBuf {
    dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("echo")
        .join("models")
}

/// 确保模型齐备（存在即跳过），返回模型目录
pub fn ensure_manga_ocr_pipeline() -> Result<PathBuf> {
    let dir = default_model_dir();
    std::fs::create_dir_all(&dir).with_context(|| format!("创建 {}", dir.display()))?;
    for (name, url) in MANGA_OCR_FILES {
        let path = dir.join(name);
        if path.exists() && std::fs::metadata(&path)?.len() > 10_000 {
            continue;
        }
        println!("下载模型 {name} …");
        download_with_fallback(url, &path, name)?;
        println!("完成 {name}");
    }
    Ok(dir)
}

fn download_with_fallback(url: &str, dest: &Path, name: &str) -> Result<()> {
    match download(url, dest) {
        Ok(()) => Ok(()),
        Err(primary) => {
            let fallback = url.replace("hf-mirror.com", FALLBACK_HOST);
            if fallback != url {
                eprintln!("{name} 镜像失败（{primary}），回退官方源");
                download(&fallback, dest)
            } else {
                Err(primary)
            }
        }
    }
}

fn download(url: &str, dest: &Path) -> Result<()> {
    let resp = ureq::get(url).call().with_context(|| format!("请求 {url}"))?;
    let total: u64 = resp
        .header("content-length")
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);
    let mut reader = resp.into_reader();
    let tmp = dest.with_extension("part");
    let mut buf = Vec::new();
    reader
        .read_to_end(&mut buf)
        .with_context(|| format!("下载 {url}"))?;
    std::fs::write(&tmp, &buf)?;
    std::fs::rename(&tmp, dest)?;
    if total > 0 && buf.len() as u64 != total {
        std::fs::remove_file(dest).ok();
        anyhow::bail!("下载数据不完整: {url}");
    }
    Ok(())
}

/// 开发环境：直接用仓库根 models-dev 目录（避免重复下载）
pub fn dev_model_dir() -> Option<PathBuf> {
    let dir = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../models-dev");
    dir.canonicalize().ok().filter(|d| d.join("manga-ocr-decoder.onnx").exists())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_list_complete() {
        assert!(MANGA_OCR_FILES.len() >= 4);
    }
}
