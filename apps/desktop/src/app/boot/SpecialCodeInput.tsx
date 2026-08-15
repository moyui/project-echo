import React from "react"
import { Input } from "@/components/ui/input"

interface SpecialCodeInputProps {
  value: string
  onValueChange: (value: string) => void
}

function SpecialCodeInput(props: SpecialCodeInputProps) {
  const { value, onValueChange } = props
  return (
    <div className="flex flex-col gap-2 py-2">
      <label className="text-sm text-muted-foreground">
        特殊码（无则留空）
      </label>
      <Input
        value={value}
        onChange={e => onValueChange(e.target.value?.trim())}
        placeholder="如 /HWNWC@... 或 /R"
      />
    </div>
  )
}

export default React.memo(SpecialCodeInput)
