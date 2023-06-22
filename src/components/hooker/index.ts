import { EventEmitter } from "events"
import Textractor, { codeMode } from "../../utils/textractorConnect"

// 发布订阅模式，消费文本提取的信息
class Hooker extends EventEmitter {
  private instance: Hooker | undefined
  private tractor: Textractor
  // private subscriber

  public getInstance() {
    if (!this.instance) {
      this.instance = new Hooker()
    }
    return this.instance
  }

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
}

export default Hooker
