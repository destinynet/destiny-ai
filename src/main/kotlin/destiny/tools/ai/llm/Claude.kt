/**
 * Created by smallufo on 2024-08-24.
 */
package destiny.tools.ai.llm

import destiny.tools.ai.ChatOptions
import destiny.tools.ai.IFunctionDeclaration
import destiny.tools.ai.InputSchema
import destiny.tools.ai.ThinkingMode
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*


class Claude {

  /**
   * Anthropic prompt caching 控制 —— 放在 `text` content block 或 top-level `system` block 上,
   * 標記該 block 為可快取。
   *
   * - `type = "ephemeral"` 是目前 Anthropic 唯一支援的 cache type（5 分鐘 TTL 預設；
   *   可透過 `ttl = "1h"` + beta header 使用 1 小時 TTL）。
   * - `ttl` 可選 `"5m"`（預設）或 `"1h"`（beta，需 header `anthropic-beta:
   *   extended-cache-ttl-2025-04-11`）。
   *
   * cached block 在同一 user 後續 request 的 input tokens 計費打 1 折。
   *
   * API doc: https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching
   */
  @Serializable
  data class CacheControl(
    val type: String = "ephemeral",
    @SerialName("ttl") val ttl: String? = null,
  )

  /**
   * 頂層 `system` 欄位用的 text block（支援 cache_control）。
   * 與 messages[] 裡的 `Content.Text` 型別雷同，但 system 是獨立頂層欄位、不帶 role。
   */
  @Serializable
  data class SystemTextBlock(
    val type: String = "text",
    val text: String,
    @SerialName("cache_control")
    val cacheControl: CacheControl? = null,
  )

  @OptIn(ExperimentalSerializationApi::class)
  @Serializable
  @JsonClassDiscriminator("type")
  sealed class Content {
    abstract val contentType: String

    @Serializable
    @SerialName("text")
    data class Text(
      override val contentType: String = "text",
      val text: String,
      @SerialName("cache_control")
      val cacheControl: CacheControl? = null,
    ) : Content()

    /**
     * 模型的思考歷程。
     *
     * ## 為什麼會突然冒出來
     *
     * Claude 4.6 世代（`claude-sonnet-5`、`claude-opus-5` 等）**省略 `thinking` 參數等於
     * adaptive thinking 開啟**——與舊模型相反（舊模型省略 = 不思考）。所以一份完全沒改過的
     * request，只要把 model 從 `claude-haiku-4-5` 換成 `claude-sonnet-5`，回應的 `content[0]`
     * 就會多出這個 block。少了這個 subclass，kotlinx 會丟
     * `Serializer for subclass 'thinking' is not found in the polymorphic scope of 'Content'`
     * ——整個回應解不開，而不只是少讀一個欄位（`ignoreUnknownKeys` 管不到多型子類）。
     *
     * ## 兩個欄位都要留著
     *
     * [thinking] 在 `display = "omitted"`（4.6 世代的預設）時是空字串——block 仍然存在。
     * [signature] 是 Anthropic 的驗證用簽章：在 tool-use 往返裡把 assistant 的 content
     * 原樣回送時，**必須連簽章一起送回**，否則 API 會拒絕。
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @SerialName("thinking")
    data class Thinking(
      /**
       * `@EncodeDefault` 是必要的，不是裝飾。
       *
       * [ClaudeMessageSerializer] 送出 `ArrayContent` 時用的是 **`Json` companion 的預設實例**
       * （`Json.encodeToJsonElement`），不是 `ClaudeImpl` 那個 `encodeDefaults = true` 的設定 ——
       * 所以任何「等於預設值」的欄位都會被省略。`display = omitted`（4.6 世代預設）時
       * thinking 剛好就是空字串，於是回送的 block 少了這個欄位，Anthropic 回
       * `messages.N.content.0.thinking.thinking: Field required` 而整個 tool-use 往返失敗。
       *
       * 2026-08-26 由 `ClaudeImpl_Sonnet5_Test` 的 function call 測試實跑抓到。
       */
      @EncodeDefault
      val thinking: String = "",
      val signature: String? = null,
    ) : Content() {
      // @Transient：不要把這個冗餘欄位送上線。`type` 已經是 discriminator，而 thinking block
      // 回送時 Anthropic 會拿 signature 驗證 block 內容，多送欄位是不必要的風險。
      // （既有的 Text / ToolUse / ToolResult / Image 仍會送出 contentType —— 那是既有行為，未動。）
      @Transient
      override val contentType: String = "thinking"
    }

    /** 被遮蔽的思考歷程（內容加密，原樣回送即可） */
    @Serializable
    @SerialName("redacted_thinking")
    data class RedactedThinking(val data: String) : Content() {
      @Transient
      override val contentType: String = "redacted_thinking"
    }

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(override val contentType: String = "tool_use", val id: String, val name: String, val input: JsonElement) : Content()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(@SerialName("tool_use_id") val toolUseId: String, val content: String) : Content() {
      override val contentType: String = "tool_result"
    }

    @Serializable
    @SerialName("image")
    data class Image(
      override val contentType: String = "image",
      val source: ImageSource
    ) : Content() {
      @Serializable
      data class ImageSource(
        val type: String,
        @SerialName("media_type") val mediaType: String,
        val data: String
      )
    }
  }

  @Serializable(with = ClaudeMessageSerializer::class)
  sealed class ClaudeMessage {
    abstract val role: String

    @Serializable
    data class TextContent(override val role: String, val content: String) : ClaudeMessage()

    @Serializable
    data class ArrayContent(override val role: String, val content: List<Content>) : ClaudeMessage()
  }


  object ClaudeMessageSerializer : KSerializer<ClaudeMessage> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ClaudeMessage") {
      element<String>("role")
      element<JsonElement>("content")
    }

    override fun serialize(encoder: Encoder, value: ClaudeMessage) {
      val compositeOutput = encoder.beginStructure(descriptor)
      compositeOutput.encodeStringElement(descriptor, 0, value.role)

      when (value) {
        is ClaudeMessage.TextContent  -> compositeOutput.encodeSerializableElement(descriptor, 1, serializer(), JsonPrimitive(value.content))
        is ClaudeMessage.ArrayContent -> compositeOutput.encodeSerializableElement(descriptor, 1, serializer(), Json.encodeToJsonElement(value.content))
      }

      compositeOutput.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ClaudeMessage {
      throw UnsupportedOperationException("Deserialization is not supported")
    }
  }

  /**
   * request 的 `thinking` 參數。
   *
   * **省略（null）不等於關閉** —— 各世代的預設不同：4.6 世代（`claude-sonnet-5`、
   * `claude-opus-5` …）省略等於 [Adaptive]，更早的模型省略等於不思考。
   * 想要確定的行為就明講，別靠預設。
   *
   * ⚠️ 送錯值會 400：[Adaptive] 只有 4.6 世代以上支援（送給 `claude-haiku-4-5` 會被拒）；
   * 部分最新模型不接受 [Disabled]。
   */
  @OptIn(ExperimentalSerializationApi::class)
  @Serializable
  @JsonClassDiscriminator("type")
  sealed class ThinkingConfig {

    /**
     * 由模型自行決定要不要想、想多久。
     *
     * @param display `omitted`（預設）思考內容為空字串但 block 仍在；`summarized` 回傳摘要。
     *   兩者的**計費與思考量相同**，差別只在看不看得到。
     */
    @Serializable
    @SerialName("adaptive")
    data class Adaptive(val display: Display? = null) : ThinkingConfig()

    @Serializable
    @SerialName("disabled")
    data object Disabled : ThinkingConfig()

    @Serializable
    enum class Display {
      @SerialName("summarized")
      SUMMARIZED,

      @SerialName("omitted")
      OMITTED,
    }
  }

  @Serializable
  data class MetaData(@SerialName("user_id") val userId: String)


  data class ClaudeOptions(
    val temperature: Double? = null,  // 0 < x < 1
    val topK: Int? = null,            // > 0
    val topP: Double? = null,         // 0 < x < 1
    val thinking: ThinkingConfig? = null,
  ) {
    companion object {
      fun ChatOptions.toClaude() : ClaudeOptions {
        return ClaudeOptions(
          this.temperature?.value,
          this.topK?.value,
          this.topP?.value,
          this.thinking?.toClaude(),
        )
      }

      /** 跨 provider 的 [ThinkingMode] → Anthropic 的 wire 形狀 */
      private fun ThinkingMode.toClaude(): ThinkingConfig = when (this) {
        ThinkingMode.DISABLED            -> ThinkingConfig.Disabled
        ThinkingMode.ADAPTIVE            -> ThinkingConfig.Adaptive()
        ThinkingMode.ADAPTIVE_SUMMARIZED -> ThinkingConfig.Adaptive(ThinkingConfig.Display.SUMMARIZED)
      }
    }
  }

  @Serializable
  data class ChatModel(
    val messages: List<ClaudeMessage>,
    // "claude-2.1" , "claude-3-opus-20240229" , "claude-3-5-sonnet-20240620"
    val model: String,

    @SerialName("max_tokens")
    val maxTokens: Int = 8192,

    @SerialName("metadata")
    val metadata: MetaData? = null,

    @Transient
    val options: ClaudeOptions? = null,

    val tools: List<Function>? = null,

    /**
     * Top-level system prompt — Anthropic-native。
     * 通常放不變的背景（user profile / natal data / 指令），搭配 `cache_control = ephemeral`
     * 讓後續 turn 打 1 折。null 或空 list 就不送 `system` 欄位。
     */
    val system: List<SystemTextBlock>? = null,
  ) {

    val temperature: Double? = options?.temperature

    @SerialName("top_k")
    val topK: Int? = options?.topK

    @SerialName("top_p")
    val topP: Double? = options?.topP

    /** null → 整個欄位不出現在 payload（`explicitNulls = false`），沿用該 model 的預設 */
    val thinking: ThinkingConfig? = options?.thinking
  }

  @Serializable
  data class Response(val id : String?, val type : String, val role : String?, val model : String?,
                      @SerialName("content")
                      val contents : List<Content>?,
                      val error : Error?,
                      @SerialName("stop_reason")
                      val stopReason : String?,
                      @SerialName("stop_sequence")
                      val stopSequence: String?, val usage : Usage?) {

    @Serializable
    data class Usage(
      @SerialName("input_tokens")
      val inputTokens : Int ,
      @SerialName("output_tokens")
      val outputTokens : Int,
      @SerialName("cache_creation_input_tokens")
      val cacheCreationInputTokens : Int,
      @SerialName("cache_read_input_tokens")
      val cacheReadInputTokens : Int,
    )

    @Serializable
    data class Error(val type: String = "error", val message: String)

  }


  @Serializable
  data class Function(val name: String, val description: String, @SerialName("input_schema") val inputSchema: InputSchema)
}

fun IFunctionDeclaration.toClaude(): Claude.Function {
  return Claude.Function(
    this.name,
    this.description,
    InputSchema(
      "object",
      this.parameters.associate { p ->
        p.name to InputSchema.Property(
          p.type,
          p.description
        )
      },
      this.parameters.filter { it.required }.map { it.name }
    )
  )
}
