//! CLI：识别一张图片并打印文本框。用法：echo-ocr <image> [--models-dir <dir>]

use echo_ocr::model_store;
use std::time::Instant;

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    let image_path = args.get(1).expect("用法: echo-ocr <image> [--models-dir <dir>]");
    let dir_flag = args.iter().position(|a| a == "--models-dir");
    let models_dir = match dir_flag {
        Some(i) => std::path::PathBuf::from(&args[i + 1]),
        None => model_store::dev_model_dir()
            .or_else(|| model_store::ensure_manga_ocr_pipeline().ok())
            .expect("模型不可用：先运行一次自动下载，或用 --models-dir 指定"),
    };

    let img = image::open(image_path)?;
    println!("图片 {image_path} {}x{}，模型目录 {}", img.width(), img.height(), models_dir.display());

    let t0 = Instant::now();
    let mut pipeline = echo_ocr::MangaOcrPipeline::load(&models_dir)?;
    println!("模型加载 {}ms", t0.elapsed().as_millis());

    let t1 = Instant::now();
    let boxes = pipeline.recognize(&img)?;
    println!("识别 {}ms，{} 个文本框", t1.elapsed().as_millis(), boxes.len());
    for b in &boxes {
        println!("[{},{} {},{}] {}", b.x, b.y, b.w, b.h, b.text.replace('\n', "⏎"));
    }
    Ok(())
}
