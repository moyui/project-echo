import { useState } from "react"
import Boot from "../boot"
import Translator from "../translator"
import RegisterEvent from "@/components/event"

RegisterEvent()

function Main() {
  // const [isStart, setIsStart] = useState(false)

  // const handleStartClick = () => {
  //   setIsStart(true)
  // }

  return (
    <>
      <Boot onStartClick={() => {}}></Boot>
      {/* {isStart ? <Translator></Translator> : null} */}
    </>
  )
}

export default Main
