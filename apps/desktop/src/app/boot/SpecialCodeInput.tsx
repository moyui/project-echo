import React from "react"
import { Row, Col, Input } from "antd"

interface SpecialCodeInputProps {
  value: string
  onValueChange: (value: string) => void
}

function SpecialCodeInput(props: SpecialCodeInputProps) {
  const { value, onValueChange } = props
  return (
    <Row>
      <Col>
        <Input
          value={value}
          onChange={e => onValueChange(e.target.value?.trim())}
          addonBefore="特殊码："
          placeholder="请输入特殊码（如果无需则为空）"
        ></Input>
      </Col>
    </Row>
  )
}

export default React.memo(SpecialCodeInput)
