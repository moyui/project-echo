import React, { useState } from "react"
import { Modal, Steps, Layout, theme, Row, Col, Button } from "antd"
import { event } from "@tauri-apps/api"
import EventTypes from "../../components/event/types"
import GameSelect from "./GameSelect"
import SpecialCodeInput from "./SpecialCodeInput"

const { Content } = Layout

type RenderStartButtonProps = {
  onStartClick: () => void
}

interface GameStartModalProps {
  children: (props: RenderStartButtonProps) => React.ReactNode
}

function GameStartModal(props: GameStartModalProps) {
  const { children } = props
  const { token } = theme.useToken()
  const [isOpen, setIsOpen] = useState(false)
  const [current, setCurrent] = useState(0)
  const [path, setPath] = useState("")
  const [name, setName] = useState("")
  const [specialCode, setSpecialCode] = useState("")

  const next = () => {
    setCurrent(current + 1)
  }

  const prev = () => {
    setCurrent(current - 1)
  }

  const handleStartClick = () => {
    setIsOpen(true)
  }

  const handleStartFinish = () => {
    setIsOpen(false)
    const game: echo.Game = {
      path: path,
      name: name,
      code: specialCode,
    }
    event.emit(EventTypes.VIEW_APP_RUN, game)
  }

  const handleGameSelect = () => {
    event.once<{ path: echo.Game["path"] }>(
      EventTypes.BACK_GET_GAME_PATH,
      event => {
        const path = event.payload.path
        const name = path.substring(
          path.lastIndexOf("\\") + 1,
          path.lastIndexOf(".exe")
        )
        setPath(path)
        setName(name)
      }
    )
    event.emit(EventTypes.VIEW_GET_GAME_PATH)
  }

  const handleSpecialCodeChange = (code: string) => {
    setSpecialCode(code)
  }

  const steps = [
    {
      title: "选择游戏可执行文件",
      content: (
        <GameSelect
          name={name}
          path={path}
          onGameSelect={handleGameSelect}
        ></GameSelect>
      ),
    },
    {
      title: "输入特殊码",
      content: (
        <SpecialCodeInput
          value={specialCode}
          onValueChange={handleSpecialCodeChange}
        ></SpecialCodeInput>
      ),
    },
  ]

  const items = steps.map(item => ({ key: item.title, title: item.title }))

  const contentStyle: React.CSSProperties = {
    lineHeight: "260px",
    textAlign: "center",
    color: token.colorTextTertiary,
    backgroundColor: token.colorFillAlter,
    borderRadius: token.borderRadiusLG,
    border: `1px dashed ${token.colorBorder}`,
    marginTop: 16,
  }

  return (
    <>
      {children({
        onStartClick: handleStartClick,
      })}
      <Modal title="启动游戏" closable open={isOpen}>
        <Steps current={current} items={items} />
        <Content style={contentStyle}>
          <Row>
            <Col>{steps[current].content}</Col>
          </Row>
          <Row>
            {current < steps.length - 1 ? (
              <Col>
                <Button onClick={next}>下一步</Button>
              </Col>
            ) : null}
            {current === steps.length - 1 ? (
              <Col>
                <Button onClick={handleStartFinish}>完成</Button>
              </Col>
            ) : null}
            {current > 0 ? (
              <Col>
                <Button onClick={prev}>上一步</Button>
              </Col>
            ) : null}
          </Row>
        </Content>
      </Modal>
    </>
  )
}

export default GameStartModal
