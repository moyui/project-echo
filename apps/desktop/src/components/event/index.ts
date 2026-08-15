import { listen, emit } from "@tauri-apps/api/event"
import { invoke } from "@tauri-apps/api/core"
import { open as openFileDialog } from "@tauri-apps/plugin-dialog"
import { WebviewWindow } from "@tauri-apps/api/webviewWindow"
import EventTypes from "./types"
import Window from "../window"

let translatorWindow: Window | null = null

/** 从游戏完整路径提取镜像名（game.exe） */
function exeNameOf(path: string): string {
  const normalized = path.replaceAll("/", "\\")
  return normalized.substring(normalized.lastIndexOf("\\") + 1)
}

async function sleep(ms: number) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

/**
 * 启动流程（Rust 侧实现）：
 * 1. 启动 TextractorCLI（stdout 线程解析并广播 echo://text）
 * 2. 轮询 tasklist 等待游戏进程出现（游戏由用户自行启动）
 * 3. attach 全部进程 + 可选特殊码 hook
 * 4. 隐藏主窗口，打开翻译窗口
 */
async function startHooking(game: echo.Game) {
  await invoke("textractor_start").catch(e =>
    console.error("textractor_start:", e)
  )

  const exeName = exeNameOf(game.path)
  let pids: number[] = []
  for (let i = 0; i < 60 && pids.length === 0; i++) {
    pids = await invoke<number[]>("find_pids", { exeName }).catch(() => [])
    if (pids.length === 0) {
      await sleep(1000)
    }
  }
  if (pids.length === 0) {
    console.error("未检测到游戏进程:", exeName)
    return
  }

  for (const pid of pids) {
    await invoke("textractor_attach", { pid }).catch(e =>
      console.error("attach:", e)
    )
  }
  if (game.code) {
    await invoke("textractor_hook", { pid: pids[0], code: game.code }).catch(
      e => console.error("hook:", e)
    )
  }

  WebviewWindow.getByLabel("main")
    ?.then(win => win?.hide())
    .catch(() => {})
  translatorWindow?.close()
  translatorWindow = new Window({ url: "/translator", label: "translator" })
  translatorWindow.run()
}

function RegisterEvent() {
  listen(EventTypes.VIEW_GET_GAME_PATH, async () => {
    const files = await openFileDialog({
      title: "选择游戏",
      filters: [{ name: "可执行文件", extensions: ["exe"] }],
    })
    if (files) {
      emit(EventTypes.BACK_GET_GAME_PATH, {
        path: files,
      })
    }
  })
  listen<echo.Game>(EventTypes.VIEW_APP_RUN, ({ payload: game }) => {
    if (game) {
      startHooking(game).catch(e => console.error("startHooking:", e))
    }
  })
}

export default RegisterEvent
