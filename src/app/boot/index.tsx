import { B } from "@tauri-apps/api/fs-6ad2a328"
import { Row, Col, Typography, Button } from "antd"

const { Title } = Typography

interface BootProps {
  onStartClick: () => void
}

function Boot(props: BootProps) {
  const { onStartClick } = props

  return (
    <>
      <Row>
        <Col>
          <Title level={3}>回声计划</Title>
        </Col>
      </Row>
      <Row gutter={16}>
        <Col>
          <Button onClick={onStartClick}>回声，启动！</Button>
        </Col>
        <Col>
          <Button>设置</Button>
        </Col>
        <Col>
          <Button>待开发功能</Button>
        </Col>
      </Row>
    </>
  )
}

export default Boot
