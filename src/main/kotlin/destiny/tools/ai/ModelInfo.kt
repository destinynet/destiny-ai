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
  /**
   * 是否接受 sampling 參數（`temperature` / `top_p` / `top_k`）。
   *
   * 預設 `true` —— 只有已知會拒收的 model 才標 `false`，這樣既有登記不必動。
   *
   * 為什麼是「sampling」而不是只有 temperature：Claude 4.6 世代把**三個一起**移除了，
   * 只擋 temperature 的話，帶 `top_p` 照樣 400
   * （`` `temperature` is deprecated for this model. ``，2026-08-26 對 claude-sonnet-5 實測）。
   * OpenAI 的 reasoning 系列也是同一種情況，所以這個欄位放在跨 provider 的 [ModelInfo] 上。
   *
   * 由 [destiny.tools.ai.IChatCompletion.resolveSampling] 統一套用：不吃就拿掉並 warn，
   * 而不是讓請求送出去撞 400 —— 與 `resolveMaxTokens` 的 clamp + warn 同一個慣例。
   */
  val samplingEnabled: Boolean = true,
  val deprecated: Boolean = false,
)

/** [IModelInfoSource.requireModelInfo] 查無此 model 時拋出。 */
class NoSuchModelException(
  val provider: Provider,
  val modelKey: String,
) : RuntimeException("No ModelInfo registered for provider=$provider, model='$modelKey'")
