import GameStartModal from "./GameStartModal"
import { Button } from "@/components/ui/button"
import Link from "next/link"

interface BootProps {
  onStartClick: () => void
}

function Boot(_props: BootProps) {
  return (
    <div className="flex h-screen flex-col items-center justify-center gap-8">
      <h1 className="text-3xl font-bold tracking-wide">回声计划</h1>
      <div className="flex gap-3">
        <GameStartModal>
          {({ onStartClick }) => (
            <Button onClick={onStartClick} size="lg">
              回声，启动！
            </Button>
          )}
        </GameStartModal>
        <Link href="/setting">
          <Button variant="outline" size="lg">
            设置
          </Button>
        </Link>
        <Link href="/image-translate">
          <Button variant="outline" size="lg">
            图片翻译
          </Button>
        </Link>
      </div>
      <p className="text-sm text-muted-foreground">
        启动后请自行打开游戏，Echo 会自动检测进程并钩取文本
      </p>
    </div>
  )
}

export default Boot
