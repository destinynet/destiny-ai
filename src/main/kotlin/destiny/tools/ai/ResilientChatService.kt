/**
 * Created by smallufo on 2025-05-13.
 */
package destiny.tools.ai

import destiny.tools.ai.model.FormatSpec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


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

  @OptIn(ExperimentalSerializationApi::class)
  val json: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    allowTrailingComma = true
    isLenient = true
  }

  // 可以定義一個配置類
  data class ResilientConfig(
    val providerModels: Set<ProviderModel>,
    override val modelTimeout: Duration, // 在完整輪詢所有模型都失敗後，再次開始新一輪輪詢前的延遲
    val delayBetweenModelLoops: Duration = 2.seconds, // 最多進行多少輪完整的模型輪詢
    val maxTotalAttempts: Int = 3,
    override val user: String? = null,
  ) : IChatConfig

  private val core = ResilientOrchestrator(config.providerModels, config.delayBetweenModelLoops, config.maxTotalAttempts)

  @Suppress("UNCHECKED_CAST")
  override suspend fun <T : Any> chatComplete(
    formatSpec: FormatSpec<out T>,
    messages: List<Msg>,
    postProcessors: List<IPostProcessor>,
    locale: Locale,
    funCalls: Set<IFunctionDeclaration>,
    chatOptionsTemplate: ChatOptions,
    providerImpl: (Provider) -> IChatCompletion
  ): Reply.Normal<T>? {
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
