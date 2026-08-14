import { EventEmitter } from "events"
import Textractor, { codeMode, Output } from "../../utils/textractorConnect"

// 发布订阅模式，消费文本提取的信息
class Hooker extends EventEmitter {
  private instance: Hooker | undefined
  private tractor: Textractor

  constructor() {
    super()
    this.instance = new Hooker()
    this.tractor = new Textractor()
    this.tractor.run()
  }

  inject(pid: number) {
    this.tractor.attach(pid)
  }

  insert(pid: number, code: codeMode) {
    this.tractor.hook(pid, code)
  }

  remove(pid: number) {
    this.tractor.detach(pid)
  }

  callBack() {
    this.tractor.on("output", (output: Output) => {
      console.log(output)
    })
  }
}

export default Hooker
