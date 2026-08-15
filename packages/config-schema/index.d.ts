// 由 generate.mjs 从 crates/echo-translator 的 Rust 结构生成，勿手改；运行 pnpm schema 重新生成。

export type ProviderKind = ("deepl" | "baidu" | "youdao") | "mock" | "llm"
/**
 * 语言。`Auto` 表示让翻译服务自动检测。
 */
export type Lang = "Auto" | "Ja" | "ZhHans" | "ZhHant" | "En" | "Ko"

export interface TranslatorConfig {
  baidu?: BaiduConfig | null
  cache?: CacheConfig
  deepl?: DeepLConfig | null
  /**
   * 术语表：发送给 provider 前做原文侧替换，LLM 额外注入提示词。
   */
  glossary?: GlossaryEntry[]
  llm?: LlmConfig | null
  provider?: ProviderKind & string
  source_lang?: Lang & string
  target_lang?: Lang & string
  youdao?: YoudaoConfig | null
}
export interface BaiduConfig {
  app_id: string
  secret: string
}
export interface CacheConfig {
  /**
   * 内存 LRU 容量（条）。
   */
  capacity?: number
  /**
   * 磁盘缓存文件路径（JSON），设置后 CLI 会在退出时持久化。
   */
  disk_path?: string | null
}
export interface DeepLConfig {
  api_key: string
  /**
   * true 走 api-free.deepl.com（免费 key），false 走 api.deepl.com（Pro）。
   */
  use_free_api?: boolean
}
export interface GlossaryEntry {
  /**
   * 原文术语，如「エルフ」
   */
  source: string
  /**
   * 期望译文，如「精灵」
   */
  target: string
}
export interface LlmConfig {
  api_key: string
  /**
   * OpenAI 兼容 base url，如 `https://api.openai.com/v1` 或 `https://open.bigmodel.cn/api/paas/v4`。
   */
  endpoint: string
  /**
   * 追加到系统提示词末尾的自定义要求（口吻、风格等）。
   */
  extra_system_prompt?: string | null
  model: string
  /**
   * 翻译方案：`gal`（游戏对话，说话人分离+长上下文）或 `manga`（漫画整页，简短译文）。默认 gal。
   */
  profile?: string | null
}
export interface YoudaoConfig {
  app_key: string
  secret: string
}
