import { spawn, ChildProcess } from "child_process"
import { EventEmitter } from "events"
import { EOL } from "os"
import { join } from "path"
import { Buffer } from "buffer"
import split from "split2"
import { textractor as textractorPath } from "../../config"

export interface Output {
  handle: number // 句柄id
  pid: number // 进程id
  addr: number // 地址
  ctx: number // 上下文1
  ctx2: number // 上下文2
  name: string // 名称
  code: string // 状态码
  text: string // 文案
}

export type codeMode = "/H" | "/R"

class Textractor extends EventEmitter {
  static readonly textReg =
    /^[(.*?):(.*?):(.*?):(.*?):(.*?):(.*?):(.*?)] ([\S\s]*)$/
  private path = ""
  private process: ChildProcess | null
  private stream: NodeJS.ReadWriteStream
  private attachPids: number[] = []
  outputVO: Output = {
    handle: 0,
    pid: 0,
    addr: 0,
    ctx: 0,
    ctx2: 0,
    name: "",
    code: "",
    text: "",
  }

  constructor() {
    super()
    this.process = null
    this.stream = split()
    this.path = join(__dirname, "../../", textractorPath)
  }

  run() {
    this.process = spawn(this.path)
    this.process.stdout?.setEncoding("utf16le")
    this.process.stdout?.pipe(this.stream)
    this.stream.on("data", this.handleData)
  }

  stop() {
    this.process?.kill()
    this.process = null
  }

  attach(pid: number) {
    if (this.attachPids.includes(pid)) {
      return
    }
    // 执行注入
    this.exec(`attach -P${pid}`)
    this.attachPids.push(pid)
  }

  detach(pid: number) {
    if (!this.attachPids.includes(pid)) {
      return
    }
    // 执行卸载
    this.exec(`detach -P${pid}`)
    this.attachPids.splice(this.attachPids.indexOf(pid), 1)
  }

  hook(pid: number, code: codeMode) {
    this.exec(`${code} -P${pid}`)
  }

  exec(command: string) {
    this.process?.stdin?.write(Buffer.from(`${command}${EOL}`, "utf16le"))
  }

  private handleData(lineText: string) {
    // 如果当前行开头是Usage，丢弃当前行
    if (lineText.indexOf("Usage") === 0) {
      return
    }
    // 如果当前行开头是[]，使用正则解析该行
    // e.g [1:0:0:FFFFFFFFFFFFFFFF:FFFFFFFFFFFFFFFF:剪贴板:HB0@0] text
    if (lineText.indexOf("[") === 0) {
      const [handle, pid, addr, ctx, ctx2, name, code, text] =
        Textractor.textReg.exec(lineText) ?? []
      this.outputVO.handle = +(handle ?? 0)
      this.outputVO.pid = +(pid ?? 0)
      this.outputVO.addr = +(addr ?? 0)
      this.outputVO.ctx = +(ctx ?? 0)
      this.outputVO.ctx2 = +(ctx2 ?? 0)
      this.outputVO.name = name ?? ""
      this.outputVO.code = code ?? ""
      this.outputVO.text = text ?? ""
    } else {
      this.outputVO.text += lineText
    }
    this.emit("output", this.outputVO)
  }
}

export default Textractor
