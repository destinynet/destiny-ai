/**
 * Created by smallufo on 2024-08-19.
 */
package destiny.tools.ai

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class ProviderModel(
  val provider: Provider,
  val model: String,
  @Contextual val temperature: Temperature? = null,
  /** 此 (domain, model) 專屬的 output 上限;null → 沿用各 impl 的 providerDefault。config 內以 `maxTokens=N` 指定。 */
  @Contextual val maxTokens: MaxTokens? = null,
  /**
   * 此 (domain, model) 的思考模式;null → 不送、沿用該 model 的預設。
   * config 內以 `thinking=adaptive` / `thinking=adaptive_summarized` / `thinking=disabled` 指定。
   */
  val thinking: ThinkingMode? = null,
)
