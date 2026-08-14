import { WebviewWindow } from "@tauri-apps/api/window"

class Window {
  private url: string
  private window: WebviewWindow | null
  private label: string
  private status: "open" | "close" | "initial"

  constructor({ url = "", label = "" }) {
    this.url = url
    this.label = label
    this.window = null
    this.status = "initial"
  }

  public run() {
    this.window = new WebviewWindow(this.label, {
      url: this.url,
    })
    this.status = "open"
  }

  public close() {
    this.window?.close()
    this.status = "close"
  }
}

export default Window
