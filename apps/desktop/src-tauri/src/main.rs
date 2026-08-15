// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::io::{Read, Write};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;

use tauri::{AppHandle, Emitter, State};

use echo_translator::{load_default_config, save_default_config, Gateway, TranslatorConfig};

/// 全局状态：翻译网关（懒加载）+ TextractorCLI 子进程
struct AppState {
    gateway: Mutex<Option<Gateway>>,
    textractor: Mutex<Option<Child>>,
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(AppState { gateway: Mutex::new(None), textractor: Mutex::new(None) })
        .invoke_handler(tauri::generate_handler![
            find_pids,
            textractor_start,
            textractor_attach,
            textractor_detach,
            textractor_hook,
            translate,
            get_config,
            save_config,
            test_config,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

// ---------- PID 探测 ----------

/// 按镜像名查询 PID（tasklist 参数数组形式，输出 CSV 解析）。
/// 游戏由用户自行启动，Echo 轮询本接口检测进程。
#[tauri::command]
fn find_pids(exe_name: String) -> Result<Vec<u32>, String> {
    let name = exe_name.trim().trim_matches('"');
    if name.is_empty() || name.contains(|c: char| c.is_whitespace() || c == '"') {
        return Err("非法的进程名".into());
    }
    let filter = format!("imagename eq {name}");
    let output = Command::new("tasklist")
        .args(["/nh", "/fo", "csv", "/fi", &filter])
        .output()
        .map_err(|e| format!("tasklist 执行失败: {e}"))?;
    let stdout = String::from_utf8_lossy(&output.stdout);
    let mut pids = Vec::new();
    for line in stdout.lines() {
        // CSV 形如 "game.exe","1234","Console","1","123,456 K"
        let fields: Vec<&str> = line.split("\",\"").collect();
        if fields.len() >= 2 {
            let pid = fields[1].trim_matches('"').parse::<u32>().unwrap_or(0);
            if pid > 0 {
                pids.push(pid);
            }
        }
    }
    Ok(pids)
}

// ---------- Textractor ----------

/// TextractorCLI 所在目录的候选路径（dev 与打包后的布局）
fn textractor_dir() -> Option<PathBuf> {
    let cwd = std::env::current_dir().ok()?;
    let exe_dir = std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.to_path_buf()));
    let mut candidates: Vec<PathBuf> = vec![
        cwd.join("../public/static/Textractor"),
        cwd.join("public/static/Textractor"),
    ];
    if let Some(dir) = exe_dir {
        candidates.push(dir.join("public/static/Textractor"));
        candidates.push(dir.join("../../../public/static/Textractor"));
    }
    candidates.into_iter().find(|p| p.join("TextractorCLI.exe").exists())
}

/// 启动 TextractorCLI，后台线程解析 UTF-16LE stdout 并向前端广播 echo://text 事件
#[tauri::command]
fn textractor_start(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    let mut guard = state.textractor.lock().map_err(|_| "状态锁获取失败".to_string())?;
    if guard.is_some() {
        return Ok(()); // 已在运行
    }
    let dir = textractor_dir().ok_or_else(|| "找不到 Textractor 目录".to_string())?;
    // 命令为固定字面量，目录通过 current_dir 控制
    let mut child = Command::new("TextractorCLI.exe")
        .current_dir(&dir)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("TextractorCLI 启动失败: {e}"))?;

    let stdout = child.stdout.take();
    *guard = Some(child);
    drop(guard);

    std::thread::spawn(move || {
        let Some(mut stdout) = stdout else { return };
        let mut buffer: Vec<u8> = Vec::new();
        let mut chunk = [0u8; 4096];
        loop {
            match stdout.read(&mut chunk) {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    buffer.extend_from_slice(&chunk[..n]);
                    // UTF-16LE 的行结束符 \r\n = 0D 00 0A 00
                    while let Some(pos) = find_crlf_utf16(&buffer) {
                        let line_bytes: Vec<u8> = buffer.drain(..pos + 4).collect();
                        if let Some(text) = decode_utf16le_line(&line_bytes) {
                            if let Some(payload) = parse_output(&text) {
                                let _ = app.emit("echo://text", payload);
                            }
                        }
                    }
                }
            }
        }
    });
    Ok(())
}

fn find_crlf_utf16(buf: &[u8]) -> Option<usize> {
    if buf.len() < 4 {
        return None;
    }
    (0..=buf.len() - 4).find(|&i| buf[i] == 0x0D && buf[i + 1] == 0x00 && buf[i + 2] == 0x0A && buf[i + 3] == 0x00)
}

fn decode_utf16le_line(bytes: &[u8]) -> Option<String> {
    let units: Vec<u16> = bytes
        .chunks_exact(2)
        .map(|pair| u16::from_le_bytes([pair[0], pair[1]]))
        .collect();
    Some(String::from_utf16_lossy(&units).trim().to_string())
}

/// 解析 `[handle:pid:addr:ctx:ctx2:name:code] text` 输出行
fn parse_output(line: &str) -> Option<serde_json::Value> {
    if line.starts_with("Usage") || !line.starts_with('[') {
        return None;
    }
    let re = regex::Regex::new(
        r"^\[(?P<handle>[^:]*):(?P<pid>[^:]*):(?P<addr>[^:]*):(?P<ctx>[^:]*):(?P<ctx2>[^:]*):(?P<name>[^:]*):(?P<code>[^:]*)\]\s?(?P<text>[\s\S]*)$",
    )
    .ok()?;
    let caps = re.captures(line)?;
    let text = caps.name("text")?.as_str().trim();
    if text.is_empty() {
        return None;
    }
    Some(serde_json::json!({
        "handle": caps.name("handle").map(|m| m.as_str()).unwrap_or_default(),
        "pid": caps.name("pid").map(|m| m.as_str()).unwrap_or_default(),
        "name": caps.name("name").map(|m| m.as_str()).unwrap_or_default(),
        "code": caps.name("code").map(|m| m.as_str()).unwrap_or_default(),
        "text": text,
    }))
}

/// 向 TextractorCLI stdin 写控制指令（UTF-16LE）。前缀白名单校验。
fn send_command(state: &State<'_, AppState>, prefix: &str, pid: u32) -> Result<(), String> {
    let command = format!("{prefix} -P{pid}");
    let mut guard = state.textractor.lock().map_err(|_| "状态锁获取失败".to_string())?;
    let Some(child) = guard.as_mut() else {
        return Err("Textractor 未启动".into());
    };
    let Some(stdin) = child.stdin.as_mut() else {
        return Err("Textractor stdin 不可用".into());
    };
    let mut encoded: Vec<u8> = command.encode_utf16().flat_map(|u| u.to_le_bytes()).collect();
    encoded.extend_from_slice(&[0x0D, 0x00, 0x0A, 0x00]);
    stdin.write_all(&encoded).map_err(|e| format!("指令写入失败: {e}"))
}

#[tauri::command]
fn textractor_attach(state: State<'_, AppState>, pid: u32) -> Result<(), String> {
    send_command(&state, "attach", pid)
}

#[tauri::command]
fn textractor_detach(state: State<'_, AppState>, pid: u32) -> Result<(), String> {
    send_command(&state, "detach", pid)
}

/// 特殊码 hook，code 必须以 /H 或 /R 开头
#[tauri::command]
fn textractor_hook(state: State<'_, AppState>, pid: u32, code: String) -> Result<(), String> {
    let trimmed = code.trim();
    if !(trimmed.starts_with("/H") || trimmed.starts_with("/R")) {
        return Err("特殊码必须以 /H 或 /R 开头".into());
    }
    send_command(&state, trimmed, pid)
}

// ---------- 翻译网关 ----------

#[tauri::command]
fn translate(state: State<'_, AppState>, texts: Vec<String>) -> Result<Vec<String>, String> {
    if texts.is_empty() {
        return Ok(Vec::new());
    }
    let mut guard = state.gateway.lock().map_err(|_| "状态锁获取失败".to_string())?;
    if guard.is_none() {
        let gateway = Gateway::new(load_default_config()).map_err(|e| e.to_string())?;
        *guard = Some(gateway);
    }
    let gateway = guard.as_mut().expect("网关已初始化");
    tauri::async_runtime::block_on(gateway.translate(&texts)).map_err(|e| e.to_string())
}

// ---------- 设置页 ----------

/// 当前生效的配置 JSON（无文件时返回默认 mock 配置）
#[tauri::command]
fn get_config() -> Result<String, String> {
    serde_json::to_string_pretty(&load_default_config()).map_err(|e| e.to_string())
}

/// 保存配置（校验合法性后写入系统配置目录），并重置网关使其下次翻译时生效
#[tauri::command]
fn save_config(state: State<'_, AppState>, config_json: String) -> Result<String, String> {
    let config: TranslatorConfig =
        serde_json::from_str(&config_json).map_err(|e| format!("配置不合法: {e}"))?;
    let target = save_default_config(&config).map_err(|e| format!("写入失败: {e}"))?;
    // 重置网关，下次 translate 重新加载
    *state.gateway.lock().map_err(|e| e.to_string())? = None;
    Ok(target.display().to_string())
}

/// 用表单里的配置（未保存也可测）试翻一句
#[tauri::command]
fn test_config(config_json: String) -> Result<String, String> {
    let config: TranslatorConfig =
        serde_json::from_str(&config_json).map_err(|e| format!("配置不合法: {e}"))?;
    let mut gateway = Gateway::new(config).map_err(|e| e.to_string())?;
    let result = tauri::async_runtime::block_on(gateway.translate(&["少女は静かに呟いた".into()]))
        .map_err(|e| e.to_string())?;
    Ok(result.into_iter().next().unwrap_or_default())
}
