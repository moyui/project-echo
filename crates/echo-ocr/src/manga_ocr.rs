//! manga-ocr 推理：ViT 编码器（224×224, mean/std 0.5）+ BERT 解码器（自回归贪心）。
//! 模型：onnx-community/manga-ocr-base-ONNX fp32 完整版（原版 ViT-base 架构，
//! 编码器 343MB / 解码器 117MB）。

use anyhow::{anyhow, Context, Result};
use ndarray::{Array2, Array4};
use ort::session::Session;
use ort::value::Value;
use std::path::Path;

const START_TOKEN: i64 = 2; // decoder_start_token_id（generation_config）
const EOS_TOKEN: i64 = 3;
const MAX_LEN: usize = 300;

pub struct MangaOcr {
    encoder: Session,
    decoder: Session,
    /// id → token 字符串
    vocab: Vec<String>,
}

impl MangaOcr {
    pub fn load(encoder_path: &Path, decoder_path: &Path, vocab_path: &Path) -> Result<Self> {
        let encoder = Session::builder()
            .with_context(|| "创建 encoder session")?
            .commit_from_file(encoder_path)
            .with_context(|| format!("加载 {}", encoder_path.display()))?;
        let decoder = Session::builder()
            .with_context(|| "创建 decoder session")?
            .commit_from_file(decoder_path)
            .with_context(|| format!("加载 {}", decoder_path.display()))?;

        let vocab: Vec<String> = std::fs::read_to_string(vocab_path)
            .with_context(|| format!("读取 {}", vocab_path.display()))?
            .lines()
            .map(|l| l.to_string())
            .collect();
        if vocab.len() < 1000 {
            return Err(anyhow!("vocab 太小: {} 行", vocab.len()));
        }
        Ok(Self { encoder, decoder, vocab })
    }

    /// 打印编码/解码会话输入输出名（排障用）
    pub fn dump_io(&self) {
        dump_session_io("encoder", &self.encoder);
        dump_session_io("decoder", &self.decoder);
    }

    /// 识别一个文本区域裁剪图（横竖排均可，模型原生支持日文竖排）
    pub fn recognize(&mut self, crop: &image::DynamicImage) -> Result<String> {
        let pixel_values = preprocess(crop);
        let enc_outputs = self
            .encoder
            .run(ort::inputs![
                "pixel_values" => Value::from_array(pixel_values)?,
            ])
            .map_err(|e| anyhow!("encoder 运行: {e}"))?;
        let hidden_view = enc_outputs["last_hidden_state"]
            .try_extract_array::<f32>()
            .map_err(|e| anyhow!("读取 encoder 输出: {e}"))?;
        let hshape = hidden_view.shape().to_vec();
        let (seq, hidden_dim) = (*hshape.get(1).unwrap_or(&0), *hshape.get(2).unwrap_or(&0));
        let hidden = hidden_view
            .to_owned()
            .into_shape_with_order((1, seq, hidden_dim))?;

        let mut input_ids = vec![START_TOKEN];
        let mut tokens: Vec<i64> = Vec::new();
        for _ in 0..MAX_LEN {
            let ids = Array2::<i64>::from_shape_vec((1, input_ids.len()), input_ids.clone())?;
            let dec_outputs = self
                .decoder
                .run(ort::inputs![
                    "input_ids" => Value::from_array(ids)?,
                    "encoder_hidden_states" => Value::from_array(hidden.clone())?,
                ])
                .map_err(|e| anyhow!("decoder 运行: {e}"))?;
            let logits = dec_outputs["logits"]
                .try_extract_array::<f32>()
                .map_err(|e| anyhow!("读取 logits: {e}"))?;
            // logits [1, t, vocab]：取最后一步的 argmax
            let dims = logits.shape();
            let t = dims.get(1).copied().unwrap_or(1);
            let v = *dims.get(2).unwrap_or(&0);
            let mut best = 0usize;
            let mut best_score = f32::NEG_INFINITY;
            for j in 0..v {
                let score = logits[[0, t - 1, j]];
                if score > best_score {
                    best_score = score;
                    best = j;
                }
            }
            let id = best as i64;
            if id == EOS_TOKEN {
                break;
            }
            tokens.push(id);
            input_ids.push(id);
        }
        Ok(decode(&self.vocab, &tokens))
    }
}

/// ViT 预处理：与 manga-ocr 官方一致——先转灰度（PIL convert("L")，ITU-R 601-2
/// 亮度系数）再缩放到 224×224、三通道归一化 (x/255-0.5)/0.5、NCHW。
/// 模型在灰度图上训练，彩色直出属于分布偏移，会掉识别率。
fn preprocess(crop: &image::DynamicImage) -> Array4<f32> {
    let rgb = crop.to_rgb8();
    let mut gray = image::GrayImage::new(rgb.width(), rgb.height());
    for (x, y, px) in rgb.enumerate_pixels() {
        let v =
            (0.299 * px[0] as f32 + 0.587 * px[1] as f32 + 0.114 * px[2] as f32).round() as u8;
        gray.put_pixel(x, y, image::Luma([v]));
    }
    let scaled = image::imageops::resize(&gray, 224, 224, image::imageops::FilterType::Triangle);
    let mut out = Array4::<f32>::zeros((1, 3, 224, 224));
    for (x, y, px) in scaled.enumerate_pixels() {
        let v = px[0] as f32 / 255.0;
        let norm = (v - 0.5) / 0.5;
        for c in 0..3 {
            out[[0, c, y as usize, x as usize]] = norm;
        }
    }
    out
}

/// BERT 词表解码：跳过特殊 token，合并 "##" 子词，日文逐字词元直接拼接
fn decode(vocab: &[String], ids: &[i64]) -> String {
    const SPECIALS: [&str; 5] = ["[CLS]", "[SEP]", "[PAD]", "[MASK]", "[UNK]"];
    let mut text = String::new();
    for id in ids {
        let token = vocab.get(*id as usize).cloned().unwrap_or_default();
        if token.is_empty() || SPECIALS.contains(&token.as_str()) {
            continue;
        }
        if let Some(stripped) = token.strip_prefix("##") {
            text.push_str(stripped);
        } else {
            text.push_str(&token);
        }
    }
    text.trim().to_string()
}

/// 打印会话输入输出名（排障用）
#[allow(dead_code)]
pub fn dump_session_io(name: &str, s: &Session) {
    let inputs: Vec<String> = s.inputs().iter().map(|i| i.name().to_string()).collect();
    let outputs: Vec<String> = s.outputs().iter().map(|o| o.name().to_string()).collect();
    println!("{name} inputs={inputs:?} outputs={outputs:?}");
}
