import React from "react"
import { Row, Col, Button } from "antd"

interface GameSelectProps {
  name: string
  path: string
  onGameSelect: () => void
}

function GameSelect(props: GameSelectProps) {
  const { name, path, onGameSelect } = props
  return (
    <>
      <Row>
        <Col>
          <Button onClick={onGameSelect}>选择游戏</Button>
        </Col>
      </Row>
      <Row>
        <Col>您当前选择的游戏名是：</Col>
        <Col>{name}</Col>
      </Row>
      <Row>
        <Col>您当前选择的游戏路径是：</Col>
        <Col>{path}</Col>
      </Row>
    </>
  )
}

export default React.memo(GameSelect)
