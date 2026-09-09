/**
 * Created by smallufo on 2025-05-11.
 */
package destiny.tools.ai

import destiny.tools.ai.model.FormatSpec
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

  /** 解碼用的 Json 全專案只有一份，見 [LlmJson] */
  val json: Json = LlmJson.lenient

  private val core = HedgeOrchestrator(config.preferred, config.fallbacks, config.preferredWait)

  override suspend fun <T : Any> chatComplete(
    formatSpec: FormatSpec<out T>,
    messages: List<Msg>,
    postProcessors: List<IPostProcessor>,
    locale: Locale,
    funCalls: Set<IFunctionDeclaration>,
    chatOptionsTemplate: ChatOptions,
    providerImpl: (Provider) -> IChatCompletion
  ): Reply.Normal<out T>? =
    chatCompleteOrExplain(formatSpec, messages, postProcessors, locale, funCalls, chatOptionsTemplate, providerImpl).successOrNull()

  @Suppress("UNCHECKED_CAST")
  override suspend fun <T : Any> chatCompleteOrExplain(
    formatSpec: FormatSpec<out T>,
    messages: List<Msg>,
    postProcessors: List<IPostProcessor>,
    locale: Locale,
    funCalls: Set<IFunctionDeclaration>,
    chatOptionsTemplate: ChatOptions,
    providerImpl: (Provider) -> IChatCompletion
  ): Orchestration<out T> {
    return core.executeExplained { providerModel ->
      val impl = providerImpl.invoke(providerModel.provider)
      val currentChatOptions = chatOptionsTemplate.copy(
        temperature = providerModel.temperature ?: chatOptionsTemplate.temperature,
        maxTokens = providerModel.maxTokens ?: chatOptionsTemplate.maxTokens,
        thinking = providerModel.thinking ?: chatOptionsTemplate.thinking
      )
      impl.typedChatComplete(
        providerModel.model, messages, formatSpec as FormatSpec<T>, json, locale,
        currentChatOptions, postProcessors, config.user, funCalls, config.modelTimeout
      )
    }
  }
}
