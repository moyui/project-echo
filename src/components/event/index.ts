import { dialog, event } from "@tauri-apps/api"
import { WebviewWindow } from "@tauri-apps/api/window"
import EventTypes from "./types"
import Runtime from "../runtime"
import Window from "../window"

let nowRuntime: Runtime | null = null
let translatorWindow: Window | null = null

function RegisterEvent() {
  const mainWindow = WebviewWindow.getByLabel("main")
  event.listen(EventTypes.VIEW_GET_GAME_PATH, async () => {
    const files = await dialog.open({
      title: "选择游戏",
      filters: [{ name: "可执行文件", extensions: ["exe"] }],
    })
    if (files) {
      event.emit(EventTypes.BACK_GET_GAME_PATH, {
        path: files[0],
      })
    }
  })
  event.listen<echo.Game>(EventTypes.VIEW_APP_RUN, ({ payload: runtime }) => {
    if (runtime) {
      nowRuntime = new Runtime(runtime)
    }
    nowRuntime?.on("runtime start", () => {
      mainWindow?.hide()
      if (translatorWindow) {
        translatorWindow.close()
      }
      translatorWindow = new Window({
        url: "http://localhost:9081/translator",
        label: "translator",
      })
    })
    nowRuntime?.on("runtime exit", () => {
      if (translatorWindow) {
        translatorWindow.close()
      }
      translatorWindow = null
      mainWindow?.show()
    })
    nowRuntime?.run()
  })
}

export default RegisterEvent
