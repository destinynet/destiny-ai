package destiny.tools.ai

import destiny.tools.ai.serializers.YearMonthSerializer
import kotlinx.serialization.Serializable
import java.time.YearMonth

/**
 * 單一 model 的 metadata。自我描述（含 [model] 名稱），可獨立傳遞。
 *
 * @param maxOutputTokens 該 model 的 output 上限（token）；[destiny.tools.ai.IChatCompletion] 用於 clamp。null = 未登記。
 * @param contextWindow   最大 context window（input+output 總長，token）；null = 未登記。
 * @param knowledgeCutoff 訓練知識截止（截到月）；null = 未知。
 * @param capabilities    模型特殊能力集合（見 [Capability]）；預設 [emptySet] = 純文字、無特殊能力。
 */
@Serializable
data class ModelInfo(
  val model: String,
  val pricing: ModelPricing,
  val maxOutputTokens: Int? = null,
  val contextWindow: Int? = null,
  @Serializable(with = YearMonthSerializer::class)
  val knowledgeCutoff: YearMonth? = null,
  val capabilities: Set<Capability> = emptySet(),
  val deprecated: Boolean = false,
)

/** [IChatCompletion.requireModelInfo] 查無此 model 時拋出。 */
class NoSuchModelException(
  val provider: Provider,
  val modelKey: String,
) : RuntimeException("No ModelInfo registered for provider=$provider, model='$modelKey'")
