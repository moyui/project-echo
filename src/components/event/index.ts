import { dialog, event } from "@tauri-apps/api"
import EventTypes from "./types"

let runtime = null

function RegisterEvent() {
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
  event.listen<echo.Game>(EventTypes.VIEW_APP_RUN, ({ payload: game }) => {
    if (game) {
    }
  })
}

export default RegisterEvent
