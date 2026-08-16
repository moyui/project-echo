import Boot from "../boot"
import RegisterEvent from "@/components/event"

if (typeof window !== "undefined") {
  RegisterEvent()
}

function Main() {
  return (
    <>
      <Boot />
    </>
  )
}

export default Main
