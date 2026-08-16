"use client"

import { useState } from "react"
import Link from "next/link"
import { Button } from "@/components/ui/button"
import { open } from "@tauri-apps/plugin-dialog"
import { convertFileSrc, invoke } from "@tauri-apps/api/core"

interface OcrTextResult {
  x: number
  y: number
  w: number
  h: number
  original: string
  translation: string
}

function ImageTranslate() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [preview, setPreview] = useState<string | null>(null)
  const [results, setResults] = useState<OcrTextResult[]>([])

  const pickAndTranslate = async () => {
    setError(null)
    setResults([])
    try {
      const selected = await open({
        multiple: false,
        filters: [
          { name: "图片", extensions: ["png", "jpg", "jpeg", "webp", "bmp"] },
        ],
      })
      if (typeof selected !== "string") return
      setPreview(convertFileSrc(selected))
      setBusy(true)
      const out = await invoke<OcrTextResult[]>("translate_image", {
        path: selected,
      })
      setResults(out)
    } catch (e) {
      setError(String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex h-screen flex-col gap-4 p-6">
      <div className="flex items-center gap-3">
        <Link href="/">
          <Button variant="outline" size="sm">
            返回
          </Button>
        </Link>
        <h1 className="text-xl font-bold">图片翻译（manga-ocr）</h1>
        <div className="flex-1" />
        <Button onClick={pickAndTranslate} disabled={busy}>
          {busy ? "识别翻译中…" : "选择图片"}
        </Button>
      </div>

      {error && <p className="text-sm text-destructive">{error}</p>}
      {busy && (
        <p className="text-sm text-muted-foreground">
          首次使用会下载模型（约 460MB），之后每次识别约几秒
        </p>
      )}

      <div className="flex min-h-0 flex-1 gap-4">
        {preview && (
          /* eslint-disable-next-line @next/next/no-img-element */
          <img
            src={preview}
            alt="原图"
            className="min-h-0 flex-1 rounded-md border object-contain"
          />
        )}
        {results.length > 0 && (
          <div className="min-h-0 w-80 shrink-0 overflow-y-auto rounded-md border p-3">
            <p className="mb-2 text-sm font-medium">
              识别结果（{results.length} 条）
            </p>
            {results.map((r, i) => (
              <div key={i} className="border-b py-2 last:border-b-0">
                <p className="text-xs text-muted-foreground">{r.original}</p>
                <p className="text-sm">{r.translation}</p>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

export default ImageTranslate
