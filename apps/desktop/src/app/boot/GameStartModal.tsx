import React, { useState } from "react"
import { once, emit } from "@tauri-apps/api/event"
import EventTypes from "../../components/event/types"
import GameSelect from "./GameSelect"
import SpecialCodeInput from "./SpecialCodeInput"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"

type RenderStartButtonProps = {
  onStartClick: () => void
}

interface GameStartModalProps {
  children: (props: RenderStartButtonProps) => React.ReactNode
}

const STEPS = ["选择游戏可执行文件", "输入特殊码"]

function GameStartModal(props: GameStartModalProps) {
  const { children } = props
  const [isOpen, setIsOpen] = useState(false)
  const [current, setCurrent] = useState(0)
  const [path, setPath] = useState("")
  const [name, setName] = useState("")
  const [specialCode, setSpecialCode] = useState("")

  const handleStartClick = () => {
    setCurrent(0)
    setIsOpen(true)
  }

  const handleStartFinish = () => {
    setIsOpen(false)
    const game: echo.Game = {
      path: path,
      name: name,
      code: specialCode,
    }
    emit(EventTypes.VIEW_APP_RUN, game)
  }

  const handleGameSelect = () => {
    once<{ path: echo.Game["path"] }>(EventTypes.BACK_GET_GAME_PATH, event => {
      const path = event.payload.path
      const name = path.substring(
        path.lastIndexOf("\\") + 1,
        path.lastIndexOf(".exe")
      )
      setPath(path)
      setName(name)
    })
    emit(EventTypes.VIEW_GET_GAME_PATH)
  }

  const handleSpecialCodeChange = (code: string) => {
    setSpecialCode(code)
  }

  return (
    <>
      {children({ onStartClick: handleStartClick })}
      <Dialog open={isOpen} onOpenChange={setIsOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>启动游戏</DialogTitle>
          </DialogHeader>

          <div className="flex items-center gap-2 text-sm">
            {STEPS.map((title, index) => (
              <React.Fragment key={title}>
                <span
                  className={
                    index === current
                      ? "font-medium text-foreground"
                      : "text-muted-foreground"
                  }
                >
                  {index + 1}. {title}
                </span>
                {index < STEPS.length - 1 && (
                  <span className="text-muted-foreground">→</span>
                )}
              </React.Fragment>
            ))}
          </div>

          <div className="min-h-[160px] rounded-md border border-dashed p-4">
            {current === 0 && (
              <GameSelect
                name={name}
                path={path}
                onGameSelect={handleGameSelect}
              />
            )}
            {current === 1 && (
              <SpecialCodeInput
                value={specialCode}
                onValueChange={handleSpecialCodeChange}
              />
            )}
          </div>

          <DialogFooter>
            {current > 0 && (
              <Button variant="outline" onClick={() => setCurrent(current - 1)}>
                上一步
              </Button>
            )}
            {current < STEPS.length - 1 ? (
              <Button onClick={() => setCurrent(current + 1)} disabled={!path}>
                下一步
              </Button>
            ) : (
              <Button onClick={handleStartFinish} disabled={!path}>
                完成
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  )
}

export default GameStartModal
