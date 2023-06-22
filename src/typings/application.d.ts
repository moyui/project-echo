declare namespace echo {
  export interface Game {
    name: string
    path: string
    code: string
  }
  export interface Retry {
    callBack: (now: number) => Promise<void>
    timeout?: number
    times?: number
  }
}
