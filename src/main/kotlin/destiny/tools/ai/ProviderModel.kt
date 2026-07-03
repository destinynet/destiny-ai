/**
 * Created by smallufo on 2024-08-19.
 */
package destiny.tools.ai

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

interface IProviderModel {
  val provider: Provider?
  val model: String?

  fun withProviderModel(provider: Provider, model: String): IProviderModel
}

@Serializable
data class ProviderModel(
  val provider: Provider,
  val model: String,
  @Contextual val temperature: Temperature? = null,
  /** 此 (domain, model) 專屬的 output 上限;null → 沿用各 impl 的 providerDefault。config 內以 `maxTokens=N` 指定。 */
  @Contextual val maxTokens: MaxTokens? = null,
)
