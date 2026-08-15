//! PaddleOCR DBNet 文本检测（ch_PP-OCRv4_det_infer.onnx）。
//! 预处理：长边缩到 ≤960（32 的倍数），ImageNet 均值方差归一化。
//! 后处理：概率图 >0.3 二值化 → 连通域 → 轴对齐外接框，日文竖排按从右到左排序。

use anyhow::{anyhow, Context, Result};
use ndarray::Array4;
use ort::session::Session;
use std::path::Path;

#[derive(Debug, Clone)]
pub struct DetBox {
    pub x: u32,
    pub y: u32,
    pub w: u32,
    pub h: u32,
    pub score: f32,
}

const MAX_SIDE: u32 = 1600;
const THRESHOLD: f32 = 0.3;
const MIN_AREA: u32 = 40;
/// 检测框外扩（近似 DBNet 的 unclip），避免裁剪切掉字符边缘
const BOX_PAD: u32 = 4;

pub struct Detector {
    session: Session,
}

impl Detector {
    pub fn load(path: &Path) -> Result<Self> {
        let session = Session::builder()?
            .commit_from_file(path)
            .with_context(|| format!("加载 {}", path.display()))?;
        Ok(Self { session })
    }

    pub fn detect(&mut self, img: &image::DynamicImage) -> Result<Vec<DetBox>> {
        use image::GenericImageView;
        let (iw, ih) = img.dimensions();
        let scale = MAX_SIDE as f32 / iw.max(ih) as f32;
        let dw = ((iw as f32 * scale) as u32).max(32) / 32 * 32;
        let dh = ((ih as f32 * scale) as u32).max(32) / 32 * 32;
        let dw = dw.min(MAX_SIDE);
        let dh = dh.min(MAX_SIDE);

        let rgb = image::imageops::resize(
            &img.to_rgb8(),
            dw,
            dh,
            image::imageops::FilterType::Triangle,
        );
        let mean = [0.485f32, 0.456, 0.406];
        let std = [0.229f32, 0.224, 0.225];
        let mut input = Array4::<f32>::zeros((1, 3, dh as usize, dw as usize));
        for (x, y, px) in rgb.enumerate_pixels() {
            for c in 0..3 {
                input[[0, c, y as usize, x as usize]] =
                    (px[c] as f32 / 255.0 - mean[c]) / std[c];
            }
        }

        let outputs = self
            .session
            .run(ort::inputs![
                "x" => ort::value::Value::from_array(input)
                    .map_err(|e| anyhow!("det 输入张量: {e}"))?,
            ])
            .map_err(|e| anyhow!("det 运行: {e}"))?;
        let prob = outputs
            .values()
            .next()
            .ok_or_else(|| anyhow!("det 无输出"))?
            .try_extract_array::<f32>()
            .map_err(|e| anyhow!("读取 det 输出: {e}"))?
            .to_owned();
        // 输出 [1,1,H,W] → 概率图
        let shape = prob.shape().to_vec();
        let (oh, ow) = (shape[shape.len() - 2], shape[shape.len() - 1]);
        let flat = prob.into_raw_vec();

        let mut boxes = connected_boxes(&flat, ow, oh);
        // 坐标映射回原图并外扩
        let sx = iw as f32 / ow as f32;
        let sy = ih as f32 / oh as f32;
        for b in &mut boxes {
            let x = (b.x as f32 * sx) as i32 - BOX_PAD as i32;
            let y = (b.y as f32 * sy) as i32 - BOX_PAD as i32;
            let w = (b.w as f32 * sx).max(1.0) as u32 + BOX_PAD * 2;
            let h = (b.h as f32 * sy).max(1.0) as u32 + BOX_PAD * 2;
            b.x = x.clamp(0, iw as i32 - 1) as u32;
            b.y = y.clamp(0, ih as i32 - 1) as u32;
            b.w = w.min(iw - b.x);
            b.h = h.min(ih - b.y);
        }
        // 日文竖排阅读顺序：从右到左，同列从上到下
        boxes.sort_by(|a, b| b.x.cmp(&a.x).then(a.y.cmp(&b.y)));
        Ok(merge_fragment_boxes(boxes))
    }
}

/// 碎片合并：DBNet 会把同一文本区域切成多条竖列/字块，
/// 相邻框（膨胀 6px 后相交）传递合并成一个区域，manga-ocr 按整区域识别。
fn merge_fragment_boxes(mut boxes: Vec<DetBox>) -> Vec<DetBox> {
    const GAP: i32 = 6;
    loop {
        let mut merged: Vec<DetBox> = Vec::with_capacity(boxes.len());
        let mut changed = false;
        'outer: for b in boxes.drain(..) {
            for m in merged.iter_mut() {
                let la = m.x as i32 - GAP;
                let ta = m.y as i32 - GAP;
                let ra = (m.x + m.w) as i32 + GAP;
                let ba = (m.y + m.h) as i32 + GAP;
                let lb = b.x as i32 - GAP;
                let tb = b.y as i32 - GAP;
                let rb = (b.x + b.w) as i32 + GAP;
                let bb = (b.y + b.h) as i32 + GAP;
                if la < rb && ra > lb && ta < bb && ba > tb {
                    let x1 = m.x.min(b.x);
                    let y1 = m.y.min(b.y);
                    let x2 = (m.x + m.w).max(b.x + b.w);
                    let y2 = (m.y + m.h).max(b.y + b.h);
                    m.x = x1;
                    m.y = y1;
                    m.w = x2 - x1;
                    m.h = y2 - y1;
                    m.score = m.score.max(b.score);
                    changed = true;
                    continue 'outer;
                }
            }
            merged.push(b);
        }
        boxes = merged;
        if !changed {
            return boxes;
        }
    }
}

/// 二值图连通域 → 外接框（迭代洪泛，避免递归爆栈）
fn connected_boxes(prob: &[f32], w: usize, h: usize) -> Vec<DetBox> {
    let mut visited = vec![0u8; w * h];
    let mut boxes = Vec::new();
    let mut stack: Vec<usize> = Vec::new();
    for start in 0..w * h {
        if visited[start] != 0 || prob[start] <= THRESHOLD {
            continue;
        }
        stack.clear();
        stack.push(start);
        visited[start] = 1;
        let (mut min_x, mut max_x, mut min_y, mut max_y) = (usize::MAX, 0usize, usize::MAX, 0usize);
        let mut sum = 0f32;
        let mut count = 0usize;
        while let Some(idx) = stack.pop() {
            let (x, y) = (idx % w, idx / w);
            min_x = min_x.min(x);
            max_x = max_x.max(x);
            min_y = min_y.min(y);
            max_y = max_y.max(y);
            sum += prob[idx];
            count += 1;
            for (dx, dy) in [(-1i32, 0i32), (1, 0), (0, -1), (0, 1)] {
                let nx = x as i32 + dx;
                let ny = y as i32 + dy;
                if nx < 0 || ny < 0 || nx >= w as i32 || ny >= h as i32 {
                    continue;
                }
                let n = ny as usize * w + nx as usize;
                if visited[n] == 0 && prob[n] > THRESHOLD {
                    visited[n] = 1;
                    stack.push(n);
                }
            }
        }
        let bw = (max_x - min_x + 1) as u32;
        let bh = (max_y - min_y + 1) as u32;
        if bw * bh < MIN_AREA {
            continue;
        }
        boxes.push(DetBox {
            x: min_x as u32,
            y: min_y as u32,
            w: bw,
            h: bh,
            score: sum / count as f32,
        });
    }
    boxes
}
