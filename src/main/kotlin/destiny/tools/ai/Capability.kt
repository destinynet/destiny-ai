package destiny.tools.ai

import kotlinx.serialization.Serializable

/**
 * 模型能力維度。
 *
 * 刻意區分兩種「圖片」能力：
 * - [VISION]    ：能**判讀**圖片輸入（models.dev `modalities.input` 含 "image"）
 * - [IMAGE_GEN] ：能**生成**圖片輸出（models.dev `modalities.output` 含 "image"）
 *
 * voice / audio 目前用不到，先略。
 */
@Serializable
enum class Capability {
  TEXT,
  VISION,
  IMAGE_GEN,
  TOOLS,
  REASONING,
  STRUCTURED_OUTPUT,
}
