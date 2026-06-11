package destiny.tools.ai

import kotlinx.serialization.Serializable

/**
 * 單一 model 的 metadata。自我描述（含 [model] 名稱），可獨立傳遞。
 *
 * @param maxOutputTokens 預留欄位，v1 一律為 null（pricing-only 範圍）。日後再把散落的
 *        maxCompletionTokens / maxOutputTokens map 折進來。
 */
@Serializable
data class ModelInfo(
  val model: String,
  val pricing: ModelPricing,
  val maxOutputTokens: Int? = null,
  val deprecated: Boolean = false,
)

/** [IChatCompletion.requireModelInfo] 查無此 model 時拋出。 */
class NoSuchModelException(
  val provider: Provider,
  val modelKey: String,
) : RuntimeException("No ModelInfo registered for provider=$provider, model='$modelKey'")
