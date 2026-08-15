import { WebviewWindow as TauriWebviewWindow } from "@tauri-apps/api/webviewWindow"

class Window {
  private url: string
  private window: TauriWebviewWindow | null
  private label: string

  constructor({ url = "", label = "" }) {
    this.url = url
    this.label = label
    this.window = null
  }

  public run() {
    this.window = new TauriWebviewWindow(this.label, {
      url: this.url,
      width: 480,
      height: 720,
      title: "Echo 翻译",
    })
  }

  public close() {
    this.window?.close()
    this.window = null
  }
}

export default Window
