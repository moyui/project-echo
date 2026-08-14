# project-echo（回声）

游戏/屏幕翻译工具，目标覆盖 PC、安卓、iOS、鸿蒙。monorepo：**一份 Rust 核心 × N 个薄原生壳**。

## 结构

```
crates/                      # Rust 核心（全平台共享）
├── echo-core/              # 领域类型（TextBlock/Lang/BBox）+ OCR 文本去重/缓存管线
└── echo-translator/        # 翻译网关：DeepL / 百度 / 有道 / LLM（OpenAI 兼容）+ 术语表 + LRU/磁盘缓存

apps/
└── desktop/                # PC 壳（Next.js + Tauri 1.x），Textractor 文本钩取在 apps/desktop/public/static

packages/
└── config-schema/          # 从 echo-translator 的 Rust 配置生成 TS 类型（单一事实来源）
```

后续：`apps/android`（UniFFI 复用核心）、`apps/ios`、`apps/harmony`（napi-rs）。

## 常用命令

```bash
pnpm install                # 安装 Node 依赖（workspace）
pnpm dev                    # 启动 desktop 前端（next dev）
cargo test --workspace      # Rust 全部测试
pnpm schema                 # 重新生成 packages/config-schema 的 TS 类型（需要 cargo）

# 翻译 CLI（调试/演示；默认 mock provider，不联网）
cargo run -q -p echo-translator -- translate こんにちは
cargo run -q -p echo-translator -- schema
```

真实翻译：复制 `crates/echo-translator/echo-translator.config.example.json` 为
`crates/echo-translator/echo-translator.config.json`（已 gitignore），填入任一 provider 的 key，
然后 `cargo run -p echo-translator -- translate <文本>`。

## 架构约定

- 业务逻辑只进 `crates/`，各端壳只做采集 / OCR 引擎适配 / 展示，不得绕过核心自实现翻译或去重
- 控制流留在原生壳（前台服务、生命周期、权限都是原生概念），Rust 核心提供纯管线 + 网关
- 配置结构以 Rust（`TranslatorConfig`）为单一事实来源，TS 类型由 `pnpm schema` 生成
