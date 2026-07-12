package destiny.tools.ai

import kotlinx.serialization.Serializable

/**
 * 模型能力維度——**只登記「會變動、需要判別」的能力**。
 *
 * 刻意**不含** TEXT / STRUCTURED_OUTPUT：
 * - TEXT 是所有 chat model 的必備地板，登記它零資訊量。
 * - STRUCTURED_OUTPUT（輸出 JSON）在本專案所有 provider 的 model 上實測皆可用
 *   （框架走 prompt + parse，非 native strict-schema mode），從不需據此篩選 → 省略。
 * → 空集合（[emptySet]）即代表「純文字、無特殊能力」的一般 model。
 *
 * 區分兩種「圖片」能力：
 * - [VISION]    ：能**判讀**圖片輸入（models.dev `modalities.input` 含 "image"，且我方 impl 真能送圖）
 * - [IMAGE_GEN] ：能**生成**圖片輸出（models.dev `modalities.output` 含 "image"）
 *
 * voice / audio 目前用不到，先略。
 */
@Serializable
enum class Capability {
  VISION,
  IMAGE_GEN,
  TOOLS,
  REASONING,
}
