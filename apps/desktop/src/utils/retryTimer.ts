const DEFAULT_TIMES = 3 // 默认重试3次
const DEFAULT_TIMEOUT = 1000 // 1s尝试一次

class RetryTimer {
  private times: number
  private timeout: number
  private nowTimes = 0
  private timer: NodeJS.Timeout | undefined
  private callBack: echo.Retry["callBack"]

  constructor(props: echo.Retry) {
    this.times = props.times ?? DEFAULT_TIMES
    this.timeout = props.timeout ?? DEFAULT_TIMEOUT
    this.callBack = props.callBack
    this.timer = undefined
  }

  public async run() {
    this.timer = setTimeout(async () => {
      try {
        await this.callBack(this.nowTimes)
        this.finish()
        return Promise.resolve()
      } catch (err) {
        this.finish()
        if (this.nowTimes >= this.times) {
          return Promise.reject()
        }
        this.nowTimes++
        this.run()
      }
    }, this.timeout)
  }

  public finish() {
    clearTimeout(this.timer)
    this.timer = undefined
    this.nowTimes = 0
  }
}

export default RetryTimer
