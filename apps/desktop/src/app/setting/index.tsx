"use client"
import { useEffect, useState } from "react"
import { invoke } from "@tauri-apps/api/core"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import Link from "next/link"

interface LlmCfg {
  endpoint: string
  api_key: string
  model: string
  profile?: string
  extra_system_prompt?: string
}
interface DeepLCfg {
  api_key: string
  use_free_api: boolean
}
interface BaiduCfg {
  app_id: string
  secret: string
}
interface YoudaoCfg {
  app_key: string
  secret: string
}
interface EchoConfig {
  provider: string
  source_lang?: string
  target_lang?: string
  llm?: LlmCfg | null
  deepl?: DeepLCfg | null
  baidu?: BaiduCfg | null
  youdao?: YoudaoCfg | null
}

const PROVIDERS: Array<[string, string]> = [
  ["mock", "Mock 离线"],
  ["llm", "LLM"],
  ["deepl", "DeepL"],
  ["baidu", "百度"],
  ["youdao", "有道"],
]

function Setting() {
  const [provider, setProvider] = useState("mock")
  const [llm, setLlm] = useState<LlmCfg>({
    endpoint: "",
    api_key: "",
    model: "",
    profile: "gal",
  })
  const [deepl, setDeepl] = useState<DeepLCfg>({
    api_key: "",
    use_free_api: true,
  })
  const [baidu, setBaidu] = useState<BaiduCfg>({ app_id: "", secret: "" })
  const [youdao, setYoudao] = useState<YoudaoCfg>({ app_key: "", secret: "" })
  const [status, setStatus] = useState("")
  const [savedPath, setSavedPath] = useState("")
  const [testing, setTesting] = useState(false)
  const [loaded, setLoaded] = useState(false)

  useEffect(() => {
    invoke<string>("get_config")
      .then(json => {
        const cfg: EchoConfig = JSON.parse(json)
        setProvider(cfg.provider || "mock")
        if (cfg.llm) setLlm({ ...cfg.llm, profile: cfg.llm.profile || "gal" })
        if (cfg.deepl) setDeepl(cfg.deepl)
        if (cfg.baidu) setBaidu(cfg.baidu)
        if (cfg.youdao) setYoudao(cfg.youdao)
        setLoaded(true)
      })
      .catch(e => {
        setStatus(`读取配置失败: ${e}`)
        setLoaded(true)
      })
  }, [])

  function buildConfig(): EchoConfig {
    const config: EchoConfig = {
      provider,
      source_lang: "Auto",
      target_lang: "ZhHans",
    }
    if (provider === "llm") config.llm = llm
    if (provider === "deepl") config.deepl = deepl
    if (provider === "baidu") config.baidu = baidu
    if (provider === "youdao") config.youdao = youdao
    return config
  }

  async function handleSave() {
    setStatus("")
    setSavedPath("")
    try {
      const path = await invoke<string>("save_config", {
        configJson: JSON.stringify(buildConfig()),
      })
      setSavedPath(path)
      setStatus("已保存")
    } catch (e) {
      setStatus(`保存失败: ${e}`)
    }
  }

  async function handleTest() {
    setTesting(true)
    setStatus("")
    try {
      const result = await invoke<string>("test_config", {
        configJson: JSON.stringify(buildConfig()),
      })
      setStatus(`✓ ${result}`)
    } catch (e) {
      setStatus(`✗ ${e}`)
    } finally {
      setTesting(false)
    }
  }

  return (
    <div className="flex h-screen flex-col p-6">
      <div className="mb-4 flex items-center gap-4">
        <Link href="/">
          <Button variant="outline" size="sm">
            返回
          </Button>
        </Link>
        <h1 className="text-lg font-semibold">翻译引擎设置</h1>
      </div>

      {!loaded ? (
        <p className="text-sm text-muted-foreground">加载配置中…</p>
      ) : (
        <div className="flex max-w-xl flex-col gap-4">
          <div>
            <p className="mb-2 text-sm text-muted-foreground">
              翻译服务（与安卓端配置同构）
            </p>
            <div className="flex flex-wrap gap-2">
              {PROVIDERS.map(([key, label]) => (
                <Button
                  key={key}
                  size="sm"
                  variant={provider === key ? "default" : "secondary"}
                  onClick={() => setProvider(key)}
                >
                  {label}
                </Button>
              ))}
            </div>
          </div>

          {provider === "llm" && (
            <div className="flex flex-col gap-3 rounded-md border p-4">
              <Input
                value={llm.endpoint}
                onChange={e => setLlm({ ...llm, endpoint: e.target.value })}
                placeholder="API 端点（OpenAI 兼容），如 https://opencode.ai/zen/go/v1"
              />
              <Input
                type="password"
                value={llm.api_key}
                onChange={e => setLlm({ ...llm, api_key: e.target.value })}
                placeholder="API Key"
              />
              <Input
                value={llm.model}
                onChange={e => setLlm({ ...llm, model: e.target.value })}
                placeholder="模型名，如 mimo-v2.5"
              />
              <Input
                value={llm.extra_system_prompt || ""}
                onChange={e =>
                  setLlm({ ...llm, extra_system_prompt: e.target.value })
                }
                placeholder="附加提示词（口吻/术语要求，可选）"
              />
              <div>
                <p className="mb-2 text-sm text-muted-foreground">
                  翻译方案（两套提示词与处理策略，可对比效果）
                </p>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant={llm.profile !== "manga" ? "default" : "secondary"}
                    onClick={() => setLlm({ ...llm, profile: "gal" })}
                  >
                    GAL（对话+说话人分离）
                  </Button>
                  <Button
                    size="sm"
                    variant={llm.profile === "manga" ? "default" : "secondary"}
                    onClick={() => setLlm({ ...llm, profile: "manga" })}
                  >
                    漫画（整页气泡）
                  </Button>
                </div>
              </div>
            </div>
          )}

          {provider === "deepl" && (
            <div className="flex flex-col gap-3 rounded-md border p-4">
              <Input
                type="password"
                value={deepl.api_key}
                onChange={e => setDeepl({ ...deepl, api_key: e.target.value })}
                placeholder="DeepL API Key"
              />
              <Button
                size="sm"
                variant={deepl.use_free_api ? "default" : "secondary"}
                className="w-fit"
                onClick={() =>
                  setDeepl({ ...deepl, use_free_api: !deepl.use_free_api })
                }
              >
                免费版端点（api-free）：{deepl.use_free_api ? "开" : "关"}
              </Button>
            </div>
          )}

          {provider === "baidu" && (
            <div className="flex flex-col gap-3 rounded-md border p-4">
              <Input
                value={baidu.app_id}
                onChange={e => setBaidu({ ...baidu, app_id: e.target.value })}
                placeholder="百度 APP ID"
              />
              <Input
                type="password"
                value={baidu.secret}
                onChange={e => setBaidu({ ...baidu, secret: e.target.value })}
                placeholder="百度密钥"
              />
            </div>
          )}

          {provider === "youdao" && (
            <div className="flex flex-col gap-3 rounded-md border p-4">
              <Input
                value={youdao.app_key}
                onChange={e =>
                  setYoudao({ ...youdao, app_key: e.target.value })
                }
                placeholder="有道应用 ID"
              />
              <Input
                type="password"
                value={youdao.secret}
                onChange={e => setYoudao({ ...youdao, secret: e.target.value })}
                placeholder="有道应用密钥"
              />
            </div>
          )}

          {provider === "mock" && (
            <p className="text-sm text-muted-foreground">
              离线演示用，返回原文加前缀，不联网。
            </p>
          )}

          <div className="flex gap-2">
            <Button onClick={handleSave}>保存</Button>
            <Button variant="outline" onClick={handleTest} disabled={testing}>
              {testing ? "测试中…" : "测试配置"}
            </Button>
          </div>

          {status && (
            <p
              className={`text-sm ${
                status.startsWith("✓") || status === "已保存"
                  ? "text-primary"
                  : "text-destructive"
              }`}
            >
              {status}
            </p>
          )}
          {savedPath && (
            <p className="break-all text-xs text-muted-foreground">
              写入位置：{savedPath}
            </p>
          )}
        </div>
      )}
    </div>
  )
}

export default Setting
