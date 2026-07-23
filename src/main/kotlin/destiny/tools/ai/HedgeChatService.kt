/**
 * Created by smallufo on 2025-05-11.
 */
package destiny.tools.ai

import destiny.tools.ai.model.FormatSpec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.util.*


/**
 * Hedged chat completion：preferred model 若在 [HedgeConfig.preferredWait] 內成功即優先回傳，
 * 否則改用 fallbacks 中第一個成功者。
 *
 * 並行請求 / select / 取消等**政策全在 [HedgeOrchestrator]**（generic core）；
 * 本 class 只是把 [IChatCompletion.typedChatComplete] 綁進 core 的 chat adapter。
 */
class HedgeChatService(
  private val config: HedgeConfig,
) : IChatOrchestrator {

  @OptIn(ExperimentalSerializationApi::class)
  val json: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    allowTrailingComma = true
    isLenient = true
  }

  private val core = HedgeOrchestrator(config.preferred, config.fallbacks, config.preferredWait)

  @Suppress("UNCHECKED_CAST")
  override suspend fun <T : Any> chatComplete(
    formatSpec: FormatSpec<out T>,
    messages: List<Msg>,
    postProcessors: List<IPostProcessor>,
    locale: Locale,
    funCalls: Set<IFunctionDeclaration>,
    chatOptionsTemplate: ChatOptions,
    providerImpl: (Provider) -> IChatCompletion
  ): Reply.Normal<out T>? {
    return core.execute { providerModel ->
      val impl = providerImpl.invoke(providerModel.provider)
      val currentChatOptions = chatOptionsTemplate.copy(
        temperature = providerModel.temperature ?: chatOptionsTemplate.temperature,
        maxTokens = providerModel.maxTokens ?: chatOptionsTemplate.maxTokens
      )
      impl.typedChatComplete(
        providerModel.model, messages, formatSpec as FormatSpec<T>, json, locale,
        currentChatOptions, postProcessors, config.user, funCalls, config.modelTimeout
      )
    }
  }
}
