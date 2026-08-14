"use client"
import { useState } from "react"
import { Button, Layout, Menu, theme } from "antd"
import {
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  UserOutlined,
} from "@ant-design/icons"
import Main from "./main"

const { Header, Content, Sider } = Layout

function Home() {
  const [collapsed, setCollapsed] = useState(false)
  const {
    token: { colorBgContainer },
  } = theme.useToken()

  return (
    <Layout>
      <Sider trigger={null} collapsible collapsed={collapsed}>
        <Menu
          theme="dark"
          mode="inline"
          defaultSelectedKeys={["1"]}
          items={[
            { key: "1", icon: <UserOutlined />, label: "控制" },
            { key: "2", icon: <UserOutlined />, label: "设置" },
          ]}
        ></Menu>
      </Sider>
      <Layout>
        <Header style={{ padding: 0, background: colorBgContainer }}>
          <Button
            type="text"
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed(!collapsed)}
            className="text-[16px] w-[64px] h-[64px]"
          ></Button>
        </Header>
        <Content
          className="mx-[24px] my-[16px] p-[24px] min-h-[calc(100vh-96px)]"
          style={{ background: colorBgContainer }}
        >
          <Main></Main>
        </Content>
      </Layout>
    </Layout>
  )
}

export default Home
