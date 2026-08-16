//! echo-ocr：图片文字识别（漫画场景）。
//! 检测用 PaddleOCR DBNet（ONNX），识别用 manga-ocr（ViT 编码器 + BERT 解码器，专为日文漫画训练）。
//! 模型文件按需下载到 %APPDATA%\echo\models（见 model_store）。

pub mod det;
pub mod manga_ocr;
pub mod model_store;

/// 一个识别出的文本框：文字 + 原图像素坐标
#[derive(Debug, Clone)]
pub struct TextBox {
    pub text: String,
    pub x: u32,
    pub y: u32,
    pub w: u32,
    pub h: u32,
}

/// 完整管线：DBNet 检测 → 逐框 manga-ocr 识别
pub struct MangaOcrPipeline {
    pub det: det::Detector,
    pub ocr: manga_ocr::MangaOcr,
}

impl MangaOcrPipeline {
    /// 从目录加载（文件清单见 model_store::ensure_manga_ocr_pipeline）
    pub fn load(model_dir: &std::path::Path) -> anyhow::Result<Self> {
        Ok(Self {
            det: det::Detector::load(&model_dir.join("ppocr-det.onnx"))?,
            ocr: manga_ocr::MangaOcr::load(
                &model_dir.join("manga-ocr-encoder-fp32.onnx"),
                &model_dir.join("manga-ocr-decoder-fp32.onnx"),
                &model_dir.join("manga-ocr-vocab.txt"),
            )?,
        })
    }

    /// 识别整图：返回文本框（日文竖排按从右到左的阅读顺序排列）
    pub fn recognize(&mut self, img: &image::DynamicImage) -> anyhow::Result<Vec<TextBox>> {
        let boxes = self.det.detect(img)?;
        let mut out = Vec::with_capacity(boxes.len());
        for b in boxes {
            let crop = crop_with_padding(img, b.x, b.y, b.w, b.h, 4);
            let text = self.ocr.recognize(&crop)?;
            if !text.trim().is_empty() {
                out.push(TextBox { text, x: b.x, y: b.y, w: b.w, h: b.h });
            }
        }
        Ok(out)
    }
}

fn crop_with_padding(
    img: &image::DynamicImage,
    x: u32,
    y: u32,
    w: u32,
    h: u32,
    pad: u32,
) -> image::DynamicImage {
    use image::GenericImageView;
    let (iw, ih) = img.dimensions();
    let x0 = x.saturating_sub(pad);
    let y0 = y.saturating_sub(pad);
    let x1 = (x + w + pad).min(iw);
    let y1 = (y + h + pad).min(ih);
    img.crop_imm(x0, y0, x1 - x0, y1 - y0)
}
