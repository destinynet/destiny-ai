/**
 * Created by smallufo on 2025-05-13.
 */
package destiny.tools.ai

import destiny.tools.ai.model.FormatSpec
import kotlinx.serialization.json.Json
import java.util.*


/**
 * 提供具有彈性的聊天完成功能。
 *
 * 該服務通過順序嘗試一組指定的語言模型 ([ProviderModel]) 來工作。
 * 在每次嘗試循環中，它會隨機打亂模型列表，然後依次調用每個模型，
 * 直到其中一個模型成功返回響應、完成所有後處理步驟，並且（如果需要）
 * 成功將結果反序列化為目標類型 [T]。
 *
 * retry / backoff / provider 停用等**政策全在 [ResilientOrchestrator]**（generic core）；
 * 本 class 只是把 [IChatCompletion.typedChatComplete] 綁進 core 的 chat adapter。
 * 錯誤分類語意（InvalidApiKey 永久排除、RateLimited retryAfter backoff …）詳見 core 的 KDoc。
 *
 * 與 [HedgeChatService] 不同（後者通過並行請求優先考慮低延遲），
 * 此服務優先考慮**最終的成功率**，即使可能需要更長的處理時間。
 *
 * @property config 控制重試行為（例如，總嘗試次數、循環間延遲）的配置。
 */
class ResilientChatService(
  private val config: ResilientConfig
) : IChatOrchestrator {

  /** 解碼用的 Json 全專案只有一份，見 [LlmJson] */
  val json: Json = LlmJson.lenient

  private val core = ResilientOrchestrator(config.providerModels, config.delayBetweenModelLoops, config.maxTotalAttempts)

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
    // repairInvalidOutput：驗證不過的回覆與錯誤接在後面，下一次嘗試帶著它送（跨 provider 也成立：訊息是純文字）
    val conversation = messages.toMutableList()
    return core.executeExplained { providerModel ->
      val impl = providerImpl.invoke(providerModel.provider)
      val currentChatOptions = chatOptionsTemplate.copy(
        temperature = providerModel.temperature ?: chatOptionsTemplate.temperature,
        maxTokens = providerModel.maxTokens ?: chatOptionsTemplate.maxTokens,
        thinking = providerModel.thinking ?: chatOptionsTemplate.thinking
      )
      // 傳副本：impl 若保留參照，之後接上的修正訊息不會回頭改到這一次送出的內容
      val reply = impl.typedChatComplete(
        providerModel.model, conversation.toList(), formatSpec as FormatSpec<T>, json, locale,
        currentChatOptions, postProcessors, config.user, funCalls, config.modelTimeout
      )
      if (config.repairInvalidOutput && reply is Reply.Error.DeserializationFailure && reply.originalContent.isNotBlank()) {
        // 原回覆空白就不接（Anthropic 不收空的 assistant 訊息），下一次照原訊息重送
        conversation += Msg(Role.ASSISTANT, reply.originalContent)
        conversation += Msg(Role.USER, repairPrompt(reply.errorMessage))
      }
      reply
    }
  }

  companion object {
    /** 驗證不過時請 model 照著改的訊息（全專案唯一一份） */
    fun repairPrompt(errorMessage: String): String =
      "The response above has errors. Rewrite the whole thing and fix these:\n${errorMessage.removePrefix("validation failed: ")}"
  }
}
