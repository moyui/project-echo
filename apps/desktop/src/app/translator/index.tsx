import { useEffect, useState } from "react"
import { listen } from "@tauri-apps/api/event"
import { invoke } from "@tauri-apps/api/core"
import { Badge } from "@/components/ui/badge"
import { ScrollArea } from "@/components/ui/scroll-area"

interface TextractorOutput {
  handle: string
  pid: string
  name: string
  code: string
  text: string
}

interface TranslationPair {
  key: number
  original: string
  translated: string
  source: string
}

const MAX_ITEMS = 200

function Translator() {
  const [items, setItems] = useState<TranslationPair[]>([])

  useEffect(() => {
    let counter = 0
    const unlisten = listen<TextractorOutput>("echo://text", async event => {
      const { text, name } = event.payload
      if (!text.trim()) {
        return
      }
      const key = counter++
      setItems(prev =>
        [{ key, original: text, translated: "", source: name }, ...prev].slice(
          0,
          MAX_ITEMS
        )
      )

      try {
        const [translated] = await invoke<string[]>("translate", {
          texts: [text],
        })
        setItems(prev =>
          prev.map(item => (item.key === key ? { ...item, translated } : item))
        )
      } catch (e) {
        setItems(prev =>
          prev.map(item =>
            item.key === key ? { ...item, translated: `翻译失败: ${e}` } : item
          )
        )
      }
    })
    return () => {
      unlisten.then(fn => fn())
    }
  }, [])

  return (
    <div className="flex h-screen flex-col p-4">
      <h1 className="mb-2 text-lg font-semibold">Echo 翻译</h1>
      <ScrollArea className="flex-1 rounded-md border p-3">
        {items.length === 0 ? (
          <p className="py-12 text-center text-sm text-muted-foreground">
            等待游戏文本…（Textractor 钩取后自动出现）
          </p>
        ) : (
          <div className="flex flex-col gap-3">
            {items.map(item => (
              <div key={item.key} className="border-b pb-2 last:border-b-0">
                <p className="whitespace-pre-wrap text-[15px] leading-6">
                  {item.original}
                </p>
                {item.translated ? (
                  <p className="mt-1 whitespace-pre-wrap text-sm leading-5 text-muted-foreground">
                    {item.translated}
                  </p>
                ) : (
                  <Badge variant="secondary" className="mt-1">
                    翻译中…
                  </Badge>
                )}
              </div>
            ))}
          </div>
        )}
      </ScrollArea>
    </div>
  )
}

export default Translator
