# project-echo（回声）

游戏/屏幕翻译工具，目标覆盖 PC、安卓、iOS、鸿蒙。monorepo：**一份 Rust 核心 × N 个薄原生壳**。

## 结构

```
crates/                      # Rust 核心（全平台共享）
├── echo-core/              # 领域类型（TextBlock/Lang/BBox）+ OCR 文本去重/缓存管线
└── echo-translator/        # 翻译网关：DeepL / 百度 / 有道 / LLM（OpenAI 兼容）+ 术语表 + LRU/磁盘缓存

apps/
└── desktop/                # PC 壳（Next.js + Tauri 2.x），Textractor 文本钩取在 apps/desktop/public/static

packages/
└── config-schema/          # 从 echo-translator 的 Rust 配置生成 TS 类型（单一事实来源）
```

后续：`apps/android`（UniFFI 复用核心）、`apps/ios`、`apps/harmony`（napi-rs）。

## 常用命令

```bash
pnpm install                # 安装 Node 依赖（workspace）
pnpm dev                    # 启动 desktop 前端（next dev，端口 3200）
cargo test --workspace      # Rust 全部测试
pnpm schema                 # 重新生成 packages/config-schema 的 TS 类型（需要 cargo）

# 翻译 CLI（调试；配置见下）
cargo run -q -p echo-translator -- translate こんにちは
cargo run -q -p echo-translator -- schema
```

## 配置（内化，无配置文件复制流程）

配置的唯一入口是**各端设置页**，保存在系统标准位置：

| 端                 | 配置位置                                                |
| ------------------ | ------------------------------------------------------- |
| 安卓               | app 私有目录（设置页读写，外部不可见）                  |
| PC 桌面 / CLI 共用 | `%APPDATA%\echo\echo-translator.config.json`（Windows） |

CLI 找不到配置时自动回退 mock 并提示路径；`--config <文件>` 可显式指定。
字段结构参考 `crates/echo-translator/echo-translator.config.example.json`。

## 架构约定

- 业务逻辑只进 `crates/`，各端壳只做采集 / OCR 引擎适配 / 展示，不得绕过核心自实现翻译或去重
- 控制流留在原生壳（前台服务、生命周期、权限都是原生概念），Rust 核心提供纯管线 + 网关
- 配置结构以 Rust（`TranslatorConfig`）为单一事实来源，TS 类型由 `pnpm schema` 生成
