import { EventEmitter } from "events"
import { exec } from "child_process"
import Hooker from "../hooker"
import { registerProcessExitCallback } from "../win32"
import RetryTimer from "@/utils/retryTimer"

class Runtime extends EventEmitter {
  private gameInfo: echo.Game
  private hooker: Hooker
  private pids: number[]
  private retryTimer: RetryTimer | null

  constructor(game: echo.Game) {
    super()
    this.gameInfo = game
    this.hooker = new Hooker()
    this.pids = []
    this.retryTimer = null
  }

  public getRuntimeInfo() {
    return this.gameInfo
  }

  public getPids() {
    return this.pids
  }

  public async run() {
    // 分为三步操作 1. 执行游戏进程 2. 找到当前游戏进程 3. 注入文本抓取钩子
    this.processRuntime()
    await this.findRuntimePids()
    this.registerHooker()
  }

  private processRuntime() {
    exec(`"${this.gameInfo.path}"`)
  }

  private getExeName() {
    return this.gameInfo.path.substring(
      this.gameInfo.path.lastIndexOf("\\") + 1
    )
  }

  private getPidsIn(value: string) {
    return value.startsWith('"')
  }

  private parsePidsFrom(value: string) {
    const pids: number[] = []

    const regexResult = value.match(/"[^"]+"/g)
    if (!regexResult) return []

    for (let i = 0; i < regexResult.length; i++) {
      if (i % 5 !== 1) continue

      pids.push(parseInt(regexResult[i].replace('"', ""), 10))
    }
    return pids
  }

  private async findRuntimePids() {
    if (this.retryTimer) {
      return
    }
    this.retryTimer = new RetryTimer({
      callBack: () =>
        new Promise((resolve, reject) => {
          exec(
            `tasklist /nh /fo csv /fi "imagename eq ${this.getExeName()}"`,
            (error, stdout) => {
              if (error) {
                return reject()
              }
              if (this.getPidsIn(stdout)) {
                this.pids = this.parsePidsFrom(stdout)
                return resolve()
              }
              return reject()
            }
          )
        }),
    })
    await this.retryTimer.run()
  }

  private registerHooker() {
    this.pids.forEach(pid => this.hooker.inject(pid))
    registerProcessExitCallback(this.pids, () => {
      this.emit("runtime exit", this)
    })
    this.emit("runtime start", this)
  }
}

export default Runtime
