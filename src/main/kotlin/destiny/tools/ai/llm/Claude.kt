/**
 * Created by smallufo on 2024-08-24.
 */
package destiny.tools.ai.llm

import destiny.tools.ai.*
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

    /**
     * 工具回傳。Anthropic 規定 assistant 的**每一個** `tool_use` 都要有對應的 `tool_result`，
     * 而且 user 訊息不得為空 —— 少一個就是 400 `messages.N: user messages must have non-empty content`。
     *
     * @param isError 工具端的失敗（未知工具名、invoke 拋例外）用 `is_error: true` 回給模型，
     *   讓它自己修正，而不是把整段對話弄壞。`null` 時不序列化（預設 Json 省略等於預設值的欄位）。
     */
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
      @SerialName("tool_use_id") val toolUseId: String,
      val content: String,
      @SerialName("is_error") val isError: Boolean? = null,
    ) : Content() {
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


  /**
   * request 的 `output_config`（GA，無 beta header）。兩個用途各自獨立，可單獨出現：
   *
   * - [effort]：控制思考深度與整體 token 支出，對 4.6 世代以上有效；配 adaptive thinking
   *   是官方建議的成本／品質槓桿。
   * - [format]：原生 structured output。
   */
  @Serializable
  data class OutputConfig(val effort: Effort? = null, val format: Format? = null) {
    @Serializable
    enum class Effort {
      @SerialName("low") LOW,
      @SerialName("medium") MEDIUM,
      @SerialName("high") HIGH,
      @SerialName("xhigh") XHIGH,
      @SerialName("max") MAX,
    }

    /**
     * `output_config.format` —— schema 從「提示詞裡的請求」變成「API 層的約束」。
     *
     * schema 送進來之前必須經過 [destiny.tools.ai.SchemaDialect.CLAUDE] 降級：
     * 每個 object 都要 `additionalProperties:false`，而數值／長度類約束與 `minItems > 1`
     * 這一家收不下（見該 enum 的 KDoc）。
     */
    @Serializable
    sealed class Format {
      @Serializable
      @SerialName("json_schema")
      data class JsonSchema(val schema: JsonObject) : Format()
    }
  }

  data class ClaudeOptions(
    val temperature: Double? = null,  // 0 < x < 1
    val topK: Int? = null,            // > 0
    val topP: Double? = null,         // 0 < x < 1
    val thinking: ThinkingConfig? = null,
    val effort: OutputConfig.Effort? = null,
  ) {
    companion object {
      fun ChatOptions.toClaude() : ClaudeOptions {
        return ClaudeOptions(
          this.temperature?.value,
          this.topK?.value,
          this.topP?.value,
          this.thinking?.toClaude(),
          this.effort?.toClaude(),
        )
      }
      private fun destiny.tools.ai.Effort.toClaude(): OutputConfig.Effort = when (this) {
        destiny.tools.ai.Effort.LOW    -> OutputConfig.Effort.LOW
        destiny.tools.ai.Effort.MEDIUM -> OutputConfig.Effort.MEDIUM
        destiny.tools.ai.Effort.HIGH   -> OutputConfig.Effort.HIGH
        destiny.tools.ai.Effort.XHIGH  -> OutputConfig.Effort.XHIGH
        destiny.tools.ai.Effort.MAX    -> OutputConfig.Effort.MAX
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

    /**
     * 原生 structured output 的 format（已降級成 Claude 方言）。由 [ClaudeImpl] 決定要不要給 ——
     * 判斷需要知道本輪有沒有 function declarations，那是 impl 才有的資訊。
     */
    @Transient
    val outputFormat: OutputConfig.Format? = null,

    /**
     * `true` 時 Anthropic 改以 SSE 逐塊回傳（見 [Stream]）。
     *
     * ⚠️ 型別是 `Boolean?` 而非 `Boolean = false`，這是刻意的。`ClaudeImpl` 的 `Json` 設定是
     * `encodeDefaults = true` + `explicitNulls = false` —— 寫成 `Boolean = false` 會讓
     * **既有的每一個非串流 request** 都多帶一個 `"stream": false`；用 nullable 並預設 null
     * 才會整個欄位省略，非串流路徑的 request JSON 一個位元組都不變。
     */
    val stream: Boolean? = null,
  ) {

    val temperature: Double? = options?.temperature

    @SerialName("top_k")
    val topK: Int? = options?.topK

    @SerialName("top_p")
    val topP: Double? = options?.topP

    /** null → 整個欄位不出現在 payload（`explicitNulls = false`），沿用該 model 的預設 */
    val thinking: ThinkingConfig? = options?.thinking
    /** 兩者皆 null → 整個 `output_config` 不出現（effort 沿用 model 預設 `high`，輸出不受 schema 約束）。 */
    @SerialName("output_config")
    val outputConfig: OutputConfig? = OutputConfig(options?.effort, outputFormat)
      .takeIf { it.effort != null || it.format != null }
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


  /**
   * `stream: true` 時 Anthropic 以 SSE 回傳的事件。官方文件：
   * https://platform.claude.com/docs/en/api/messages-streaming
   *
   * ## 為什麼不做成一個 sealed class
   *
   * 因為**未知的事件型別必須能安全忽略**。Anthropic 明講 client 要容忍新增的 event type
   * （現有的 `ping` 就是一例，未來還會有）。kotlinx 的多型反序列化遇到不認識的 discriminator
   * 會整個拋例外 —— 那在一次性回應只是解不開一份 JSON，在串流卻是**講到一半整條斷掉**，
   * 而且已經 emit 出去的內容收不回來。
   *
   * 所以解析端（`ClaudeImpl`）的做法是：先把每個 `data:` 解成 [kotlinx.serialization.json.JsonObject]、
   * 讀出 `type` 自行分派，認得的才用下面的 DTO 解，不認得的丟掉。本物件只提供「認得的那幾種」
   * 的形狀，不負責窮舉。
   *
   * 同理，[ContentBlockStart.contentBlock] 與 [ContentBlockDelta.delta] 都留成 JsonObject：
   * content block 的種類（text / thinking / tool_use / …）與 delta 的種類（text_delta /
   * thinking_delta / input_json_delta / signature_delta / citations_delta / …）都還在長。
   */
  object Stream {

    /** 事件信封的 `type` 值。 */
    object EventType {
      const val MESSAGE_START = "message_start"
      const val CONTENT_BLOCK_START = "content_block_start"
      const val CONTENT_BLOCK_DELTA = "content_block_delta"
      const val CONTENT_BLOCK_STOP = "content_block_stop"
      const val MESSAGE_DELTA = "message_delta"
      const val MESSAGE_STOP = "message_stop"
      const val ERROR = "error"
    }

    /** `content_block.type` / `delta.type` 的值。 */
    object BlockType {
      const val TEXT = "text"
      const val THINKING = "thinking"
      const val TOOL_USE = "tool_use"

      const val TEXT_DELTA = "text_delta"
      const val THINKING_DELTA = "thinking_delta"
      const val INPUT_JSON_DELTA = "input_json_delta"
    }

    /**
     * 串流的用量。**每個欄位都是 nullable** —— 與一次性回應的 [Response.Usage] 不同：
     * `message_start` 帶 input 與 cache 數字（output 此時尚為預估值），真正的 output_tokens
     * 要等 `message_delta`。硬要非 null 會在解析時炸掉。
     */
    @Serializable
    data class Usage(
      @SerialName("input_tokens") val inputTokens: Int? = null,
      @SerialName("output_tokens") val outputTokens: Int? = null,
      @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int? = null,
      @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int? = null,
    )

    @Serializable
    data class MessageStart(val message: StartedMessage) {
      @Serializable
      data class StartedMessage(
        val id: String? = null,
        val model: String? = null,
        val usage: Usage? = null,
      )
    }

    @Serializable
    data class ContentBlockStart(
      val index: Int,
      @SerialName("content_block") val contentBlock: JsonObject,
    )

    @Serializable
    data class ContentBlockDelta(
      val index: Int,
      val delta: JsonObject,
    )

    @Serializable
    data class ContentBlockStop(val index: Int)

    /** 收尾事件：帶 `stop_reason` 與**真正的** output_tokens。 */
    @Serializable
    data class MessageDelta(
      val delta: Delta,
      val usage: Usage? = null,
    ) {
      @Serializable
      data class Delta(@SerialName("stop_reason") val stopReason: String? = null)
    }

    @Serializable
    data class ErrorEvent(val error: Response.Error)
  }

  @Serializable
  data class Function(val name: String, val description: String, @SerialName("input_schema") val inputSchema: InputSchema)
}

fun IFunctionDeclaration.toClaude(): Claude.Function {
  return Claude.Function(
    this.name,
    this.description,
    this.parameters.toInputSchema()
  )
}
