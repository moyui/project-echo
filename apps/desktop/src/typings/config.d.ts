import type { TranslatorConfig } from "@echo/config-schema"

declare global {
  namespace echo {
    namespace Config {
      export type Translator = TranslatorConfig
    }
  }
}
