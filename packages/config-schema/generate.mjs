// 从 Rust 侧生成 TS 类型：
//   echo-translator 的 TranslatorConfig --(schemars)--> JSON Schema --(json-schema-to-typescript)--> index.d.ts
// 需要本机 cargo 在 PATH 上（CI 里同样要求）。
import { execFileSync } from "node:child_process"
import { mkdirSync, writeFileSync } from "node:fs"
import { fileURLToPath } from "node:url"
import { dirname, join } from "node:path"
import { compile } from "json-schema-to-typescript"

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = join(here, "..", "..")

const schemaJson = execFileSync(
  "cargo",
  ["run", "-q", "-p", "echo-translator", "--", "schema"],
  {
    cwd: repoRoot,
    encoding: "utf8",
    maxBuffer: 32 * 1024 * 1024,
    shell: process.platform === "win32",
  }
)

const schema = JSON.parse(schemaJson)
schema.$id = "echo/translator-config.schema.json"
schema.title = "TranslatorConfig"

mkdirSync(join(here, "schema"), { recursive: true })
writeFileSync(
  join(here, "schema", "translator-config.schema.json"),
  JSON.stringify(schema, null, 2) + "\n"
)

const dts = await compile(schema, "TranslatorConfig", {
  additionalProperties: false,
  bannerComment:
    "// 由 generate.mjs 从 crates/echo-translator 的 Rust 结构生成，勿手改；运行 pnpm schema 重新生成。",
})
writeFileSync(join(here, "index.d.ts"), dts)

console.log("已生成 packages/config-schema/index.d.ts")
