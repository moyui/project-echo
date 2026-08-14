import { Row, Col, Typography, Tag } from "antd"

const { Text, Title } = Typography

function Translator() {
  return (
    <>
      <Row>
        <Col>
          <Title level={5}>这里是主要文本！！！</Title>
        </Col>
      </Row>
      <Row>
        <Col>
          <Tag>翻译器1号</Tag>
        </Col>
        <Col>
          <Text>这里是翻译器1号的文本</Text>
        </Col>
      </Row>
      <Row>
        <Col>
          <Tag>翻译器二哈</Tag>
        </Col>
        <Col>
          <Text>这里是翻译器二哈的文本</Text>
        </Col>
      </Row>
    </>
  )
}

export default Translator
