import React from "react"
import { Button } from "@/components/ui/button"

interface GameSelectProps {
  name: string
  path: string
  onGameSelect: () => void
}

function GameSelect(props: GameSelectProps) {
  const { name, path, onGameSelect } = props
  return (
    <div className="flex flex-col gap-3 py-2">
      <Button onClick={onGameSelect} className="w-fit">
        选择游戏
      </Button>
      <div className="text-sm">
        <span className="text-muted-foreground">游戏名：</span>
        {name || "未选择"}
      </div>
      <div className="break-all text-sm">
        <span className="text-muted-foreground">路径：</span>
        {path || "未选择"}
      </div>
    </div>
  )
}

export default React.memo(GameSelect)
