import { useState } from "react"
import Boot from "../boot"
import Translator from "../translator"

function Main() {
  const [isStart, setIsStart] = useState(false)

  const handleStartClick = () => {
    setIsStart(true)
  }

  return (
    <>
      {!isStart ? <Boot onStartClick={handleStartClick}></Boot> : null}
      {isStart ? <Translator></Translator> : null}
    </>
  )
}

export default Main
