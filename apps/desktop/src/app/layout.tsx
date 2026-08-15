import "./globals.css"

export const metadata = {
  title: "Echo",
  description: "游戏/屏幕翻译工具",
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="zh-CN">
      {/* Tauri 本地窗口用系统字体栈；不用 next/font/google（build 需连 Google 下载字体会卡死） */}
      <body className="font-sans">{children}</body>
    </html>
  )
}
