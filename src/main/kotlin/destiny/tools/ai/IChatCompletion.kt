package destiny.tools.ai

import mu.KotlinLogging
import destiny.tools.ai.model.FormatSpec
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed class Reply<out T> {

  abstract val provider: Provider

  data class Normal<T>(val content: T,
                       val think: String?,
                       override val provider: Provider,
                       val model: String,
                       val invokedFunCalls: List<FunCall> = emptyList(),
                       val inputTokens: Int? = null,
                       val outputTokens: Int? = null,
                       val duration: Duration? = null,
                       /** Prompt caching metrics（Anthropic 等支援的 provider） */
                       val cacheCreationTokens: Int? = null,
                       /** Prompt caching 命中的 input token 數（計費 10x 便宜） */
                       val cacheReadTokens: Int? = null) : Reply<T>()

  sealed class Error : Reply<Nothing>() {

    /**
     * orchestrator 可安全地對同一個 (provider, model) 重試。
     * 通常是 transient 狀態（server overload, rate limit）。
     *
     * 用 sealed class 而非 sealed interface，讓 Kotlin 單一繼承強制 [Retryable] / [Terminal] 互斥。
     */
    sealed class Retryable : Error()

    /**
     * orchestrator 不應對同一輸入再 retry 同一 model。
     * 改 input、換 model 或換 provider 才有救。
     */
    sealed class Terminal : Error()

    /** Input/context 太長 —— 改 input 才能解。 */
    data class TooLong(val message: String, override val provider: Provider) : Terminal()

    data class DeserializationFailure(val errorMessage: String,
                                      val originalContent: String,
                                      override val provider: Provider,
                                      val model: String) : Terminal()

    data class InvalidApiKey(override val provider: Provider) : Terminal()

    /** 籠統的 transient 錯誤（server overloaded / 未分類的 5xx 等）—— 可 retry。 */
    data class Busy(override val provider: Provider) : Retryable()

    /** 預設視為 Terminal —— 未知狀況保守處理，不重試。 */
    data class Unknown(val message: String, override val provider: Provider) : Terminal()

    /**
     * 明確的 rate limit（429 / quota per minute）。
     * @param retryAfter 若 provider 在 response header 給了 Retry-After 即帶入，否則 null。
     */
    data class RateLimited(
      override val provider: Provider,
      val retryAfter: Duration? = null,
      val message: String = ""
    ) : Retryable()

    /**
     * 輸出觸頂 max_tokens（finishReason = "length" / "max_tokens" / "MAX_TOKENS"）；
     * 內容部分產生但被截斷。同樣 input 重 retry 不會有救，要換 model 或加大 max_tokens。
     */
    data class MaxTokensReached(
      override val provider: Provider,
      val model: String,
      val partialContent: String? = null
    ) : Terminal()

    /**
     * Function-call loop 沒在 [maxDepth] 內收斂。
     * 通常是 model 卡在重複呼叫同一個 function、或 function 回的結果讓 model 無法決斷。
     */
    data class FunctionCallLoopExceeded(
      override val provider: Provider,
      val model: String,
      val maxDepth: Int
    ) : Terminal()
  }
}


interface IChatCompletion : IModelInfoSource {

  /**
   * 此 impl 是否**操作上**能送出圖片輸入（把 image 轉成 provider 認得的 request）。
   *
   * 這是 model 的 [Capability.VISION] 能否實際生效的**結構性前提**：即使某 model 理論支援判圖，
   * 若承載它的 impl 送不出圖（例如 [Xai] 對 chunk content 直接 error），該 model 在我方 stack 仍
   * 無法判圖。[destiny.tools.ai.IModelCatalog] 據此把「送不出圖」impl 的 model 之 VISION 遮蔽掉。
   *
   * 預設 **false（fail-closed）**：新 impl 未明確宣告即視為不支援，避免誤標。
   */
  val supportsVisionInput: Boolean get() = false

  suspend fun chatComplete(model: String, messages: List<Msg>, user: String? = null, funCalls: Set<IFunctionDeclaration> = emptySet(), timeout: Duration = 90.seconds, chatOptions: ChatOptions, jsonSchema: JsonSchemaSpec? = null, maxFunctionCallDepth: Int = DEFAULT_MAX_FUNCTION_CALL_DEPTH) : Reply<String>

  suspend fun chatComplete(model: String, messages: List<Msg>, user: String? = null, funCall: IFunctionDeclaration, timeout: Duration = 90.seconds, chatOptions: ChatOptions, maxFunctionCallDepth: Int = DEFAULT_MAX_FUNCTION_CALL_DEPTH) : Reply<String> {
    return chatComplete(model, messages, user, setOf(funCall), timeout, chatOptions, maxFunctionCallDepth = maxFunctionCallDepth)
  }

  suspend fun <T : Any> typedChatComplete(
    model: String,
    messages: List<Msg>,
    formatSpec: FormatSpec<T>,
    json: Json,
    locale: Locale = Locale.getDefault(),
    chatOptions: ChatOptions = ChatOptions(),
    postProcessors: List<IPostProcessor> = emptyList(),
    user: String? = null,
    funCalls: Set<IFunctionDeclaration> = emptySet(),
    timeout: Duration = 90.seconds,
    maxFunctionCallDepth: Int = DEFAULT_MAX_FUNCTION_CALL_DEPTH
  ) : Reply<T>?

  suspend fun <T : Any> typedChatComplete(
    model: String,
    message: String,
    formatSpec: FormatSpec<T>,
    json: Json,
    locale: Locale = Locale.getDefault(),
    chatOptions: ChatOptions = ChatOptions(),
    postProcessors: List<IPostProcessor> = emptyList(),
    user: String? = null,
    funCalls: Set<IFunctionDeclaration> = emptySet(),
    timeout: Duration = 90.seconds,
    maxFunctionCallDepth: Int = DEFAULT_MAX_FUNCTION_CALL_DEPTH
  ): Reply<T>? {
    return typedChatComplete(model, listOf(Msg(Role.USER, message)), formatSpec, json, locale, chatOptions, postProcessors, user, funCalls, timeout, maxFunctionCallDepth)
  }

  companion object {
    /**
     * Function-call loop 預設最大遞迴深度。一輪 = 一次 model→function→model 的交換。
     * 超過會回傳 [Reply.Error.FunctionCallLoopExceeded]，避免 model 卡在重複呼叫 / stack overflow / 燒 token。
     */
    const val DEFAULT_MAX_FUNCTION_CALL_DEPTH = 10
  }
}

abstract class AbstractChatCompletion : IChatCompletion {

  abstract suspend fun doChatComplete(model: String, messages: List<Msg>, user: String?, funCalls: Set<IFunctionDeclaration>, timeout: Duration, chatOptions: ChatOptions, jsonSchema: JsonSchemaSpec? = null, maxFunctionCallDepth: Int = IChatCompletion.DEFAULT_MAX_FUNCTION_CALL_DEPTH): Reply<String>

  override suspend fun chatComplete(model: String, messages: List<Msg>, user: String?, funCalls: Set<IFunctionDeclaration>, timeout: Duration, chatOptions: ChatOptions, jsonSchema: JsonSchemaSpec?, maxFunctionCallDepth: Int): Reply<String> {
    val filteredFunCalls = funCalls.filter { it.applied(messages) }.toSet()

    val finalMsgs = messages.fold(mutableListOf<Msg>()) { acc, msg ->
      if (acc.isNotEmpty()) {
        val lastMsg = acc.last()
        // 不合併 SYSTEM 訊息 —— 因為每則可能有不同的 [Msg.cacheable] 策略
        // （Anthropic prompt caching 要穩定的 block 單獨 cache、不穩定的另一 block 不 cache），
        // 合併會讓 cache_control boundary 錯位、cache key 失配。
        val sameRoleNonSystem = lastMsg.role == msg.role && msg.role != Role.SYSTEM
        val sameCacheable = lastMsg.cacheable == msg.cacheable
        if (sameRoleNonSystem && sameCacheable) {
          if (lastMsg.stringContents == msg.stringContents) {
            // Drop the duplicate message
            logger.warn { "DROP_DUPLICATED  : ${msg.stringContents}" }
          } else {
            // Append the content
            acc[acc.size - 1] = lastMsg.copy(contents = buildList {
              addAll(lastMsg.contents)
              addAll(msg.contents)
            })
          }
        } else {
          acc.add(msg)
        }
      } else {
        acc.add(msg)
      }
      acc
    }

    val funCallPrompts = (filteredFunCalls.joinToString(",") { it.name }).let {
      if (it.isEmpty()) it
      else buildString {
        // TODO : add meta data , to enhance LLM memory
        append("With function calls if applicable : ")
        append(it)
      }
    }

    if (filteredFunCalls.isNotEmpty() && finalMsgs.isNotEmpty()) {
      val lastMsg = finalMsgs.last()
      finalMsgs[finalMsgs.lastIndex] = lastMsg.copy(
        contents = buildList {
          addAll(lastMsg.contents)
          add(Content.StringContent("\n$funCallPrompts"))
        }
      )
    }

    return doChatComplete(model, finalMsgs, user, filteredFunCalls, timeout, chatOptions, jsonSchema, maxFunctionCallDepth)
  }

  @Suppress("UNCHECKED_CAST")
  override suspend fun <T : Any> typedChatComplete(
    model: String,
    messages: List<Msg>,
    formatSpec: FormatSpec<T>,
    json: Json,
    locale: Locale,
    chatOptions: ChatOptions,
    postProcessors: List<IPostProcessor>,
    user: String?,
    funCalls: Set<IFunctionDeclaration>,
    timeout: Duration,
    maxFunctionCallDepth: Int
  ): Reply<T>? {

    return when (val rawReply: Reply<String> = chatComplete(model, messages, user, funCalls, timeout, chatOptions, formatSpec.jsonSchema, maxFunctionCallDepth)) {
      is Reply.Normal<String> -> {
        val processedString = postProcessors.fold(rawReply.content) { currentContent, postProcessor ->
          val (nextContent, _) = postProcessor.process(currentContent, locale)
          nextContent
        }
        val serializer = formatSpec.serializer

        fun failure(message: String, content: String) = Reply.Error.DeserializationFailure(
          errorMessage = message,
          originalContent = content,
          provider = rawReply.provider,
          model = rawReply.model,
        )

        val typedResult: T = if (formatSpec.kClass == String::class) {
          @Suppress("UNCHECKED_CAST")
          processedString as T
        } else {
          // 空回覆（Claude 沒有 text block、模型只回了 tool_use…）直接歸類，別讓 decoder 吐一句看不懂的錯
          if (processedString.isBlank()) {
            logger.warn { "$model returned empty content for ${serializer.descriptor.serialName}" }
            return failure("empty content", processedString)
          }
          // 輸出型別不是 String 就切 JSON 本體 —— 這是 typed 路徑的事實，不是 domain 的選項（見 JsonExtract）
          val jsonText = JsonExtract.extract(processedString)
          val parsed: T = try {
            json.decodeFromString(serializer, jsonText)
          } catch (e: IllegalArgumentException) {
            // SerializationException 是 IllegalArgumentException 的子類；DTO 的 init { require } 丟的也是它
            logger.warn(e) { "Failed to deserialize content from $model (serializer: ${serializer.descriptor.serialName}). Content: $jsonText" }
            return failure(e.localizedMessage ?: "Serialization failed", jsonText)
          }
          // 內容層驗證：decode 成功不代表完整（見 FormatSpec.validator）
          formatSpec.validator(parsed)?.let { reason ->
            logger.warn { "$model reply for ${serializer.descriptor.serialName} failed validation: $reason" }
            return failure("validation failed: $reason", jsonText)
          }
          parsed
        }

        Reply.Normal(
          content = typedResult,
          think = rawReply.think,
          provider = rawReply.provider,
          model = rawReply.model,
          invokedFunCalls = rawReply.invokedFunCalls,
          inputTokens = rawReply.inputTokens,
          outputTokens = rawReply.outputTokens,
          duration = rawReply.duration,
          cacheCreationTokens = rawReply.cacheCreationTokens,
          cacheReadTokens = rawReply.cacheReadTokens,
        )
      }

      is Reply.Error -> rawReply
    }
  }

  /**
   * 解析本次 request 要送出的 max output tokens；回傳 null 代表「不指定，交給 provider server 端預設」。
   *
   * 取值優先序：caller 於 [ChatOptions.maxTokens] 指定 → [providerDefault] → 該 model 的
   * [ModelInfo.maxOutputTokens]；三者皆無 → null。取到的值最終一律 clamp 到
   * [ModelInfo.maxOutputTokens] 上限（若有登記）—— 超過即自動調降並 warn
   * （例如對 mistral-small 期望 300k → 降為 256k）。
   *
   * @param providerDefault caller 未指定 maxTokens 時的預設。
   *        傳 null（預設）表示「未指定時就退到該 model 的 [ModelInfo.maxOutputTokens]」，
   *        適合 output 上限本身即合理預設、且未登記上限的 model 想沿用 server 預設的 provider
   *        （OpenAI / XiaoMi / Groq / Reka）；Claude / Mistral 這類上限遠高於一般所需的，
   *        則傳一個較低的常數當預設。
   */
  /**
   * 依 [ModelInfo.samplingEnabled] 決定要不要把 sampling 參數拿掉。
   *
   * 不吃這些參數的 model（Claude 4.6 世代、OpenAI reasoning 系列）收到就回 400，
   * 而錯誤訊息不會告訴呼叫端「是哪一份設定帶進來的」。與其讓它撞牆，不如在這裡拿掉並 warn ——
   * 與 [resolveMaxTokens] 的 clamp + warn 同一個慣例。
   *
   * 這麼做的好處是**沒有任何 caller 需要知道這件事**：`domain-model-config.json` 裡那些
   * `"CLAUDE : xxx, 0.2"` 的設定，換到新世代 model 時不會突然全面失效。
   */
  protected fun resolveSampling(model: String, chatOptions: ChatOptions): ChatOptions {
    if (findModelInfo(model)?.samplingEnabled != false) return chatOptions
    val dropped = buildList {
      chatOptions.temperature?.let { add("temperature=${it.value}") }
      chatOptions.topP?.let { add("topP=${it.value}") }
      chatOptions.topK?.let { add("topK=${it.value}") }
    }
    if (dropped.isEmpty()) return chatOptions
    logger.warn { "$provider: $model 不接受 sampling 參數，已忽略 ${dropped.joinToString(", ")}" }
    return chatOptions.copy(temperature = null, topP = null, topK = null)
  }

  protected fun resolveMaxTokens(model: String, chatOptions: ChatOptions, providerDefault: Int? = null): Int? {
    val ceiling = findModelInfo(model)?.maxOutputTokens
    val requested = chatOptions.maxTokens?.value ?: providerDefault ?: ceiling ?: return null
    return if (ceiling != null && requested > ceiling) {
      logger.warn { "$provider: requested maxTokens=$requested exceeds $model ceiling=$ceiling → clamped to $ceiling" }
      ceiling
    } else {
      requested
    }
  }

  companion object {
    private val logger = KotlinLogging.logger { }
  }
}
