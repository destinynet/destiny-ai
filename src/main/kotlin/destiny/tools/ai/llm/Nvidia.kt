/**
 * Created by smallufo on 2026-10-17.
 */
package destiny.tools.ai.llm

import destiny.tools.ai.ChatOptions
import destiny.tools.ai.JsonSchemaSpec
import destiny.tools.ai.ThinkingMode
import destiny.tools.ai.model.ResponseFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject


/**
 * NVIDIA build.nvidia.com（`integrate.api.nvidia.com`）chat schema。
 *
 * wire shape 與 OpenAI 相同（`reasoning_content`、`tool_calls`、`response_format: json_schema` 皆同），
 * 故 message 直接複用 [OpenAi.Message]。差異只有兩處：
 *  - 思考開關走 vLLM 的 `chat_template_kwargs.enable_thinking`，不是 `reasoning_effort`
 *  - 錯誤有兩種外殼（見 [Response]）
 */
class Nvidia {

  /**
   * 錯誤外殼有兩種（2026-10-17 實測）：
   *  - 推論端：`{"error":{"message":"Service temporarily overloaded","type":"Service Unavailable","code":503}}`
   *    —— `code` 是**數字**，[OpenAi.Response.ErrorResponse.Error.code] 是 String，所以 decode 端要 `isLenient`
   *  - 閘道（NVCF）：`{"status":403,"title":"Forbidden","detail":"Authorization failed"}`
   */
  @Serializable(with = ResponseSerializer::class)
  sealed class Response {

    @Serializable
    data class NormalResponse(val id: String, val model: String, val choices: List<Choice>, val usage: Usage) : Response() {
      @Serializable
      data class Choice(
        val index: Int,
        val message: OpenAi.Message,
        @SerialName("finish_reason") val finishReason: String? = null
      )

      @Serializable
      data class Usage(
        @SerialName("prompt_tokens")
        val promptTokens: Int,
        @SerialName("completion_tokens")
        val completionTokens: Int,
        @SerialName("prompt_tokens_details")
        val promptTokensDetails: OpenAi.PromptTokensDetails? = null,
      )
    }

    @Serializable
    data class ErrorResponse(val error: OpenAi.Response.ErrorResponse.Error) : Response()

    @Serializable
    data class GatewayError(val status: Int, val title: String, val detail: String? = null) : Response()
  }

  object ResponseSerializer : JsonContentPolymorphicSerializer<Response>(Response::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Response> {
      val obj = element.jsonObject
      return when {
        "error" in obj                    -> Response.ErrorResponse.serializer()
        "status" in obj && "title" in obj -> Response.GatewayError.serializer()
        else                              -> Response.NormalResponse.serializer()
      }
    }
  }

  @Serializable
  data class ChatTemplateKwargs(@SerialName("enable_thinking") val enableThinking: Boolean)

  data class NvidiaOptions(
    /** 0 .. 1 */
    val temperature: Double? = null,
    /** 0 .. 1 */
    val topP: Double? = null,
    /** -2.0 .. 2.0 , default 0 */
    val frequencyPenalty: Double? = null,
    /** null → 不送，沿用 model 預設（Nemotron 3 預設開啟思考） */
    val enableThinking: Boolean? = null,
  ) {
    companion object {
      fun ChatOptions.toNvidia(): NvidiaOptions {
        return NvidiaOptions(
          this.temperature?.value,
          this.topP?.value,
          this.frequencyPenalty?.value?.let { it * 2 },
          when (this.thinking) {
            null                  -> null
            ThinkingMode.DISABLED -> false
            else                  -> true
          }
        )
      }
    }
  }

  @Serializable
  data class ChatModel(
    val model: String,
    val messages: List<OpenAi.Message>,
    @Transient
    val options: NvidiaOptions? = null,
    val tools: List<OpenAi.FunctionDeclaration>? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,

    @Transient
    val jsonSchemaSpec: JsonSchemaSpec? = null
  ) {

    val temperature: Double? = this.options?.temperature

    @SerialName("top_p")
    val topP: Double? = this.options?.topP

    @SerialName("frequency_penalty")
    val frequencyPenalty: Double? = this.options?.frequencyPenalty

    @SerialName("chat_template_kwargs")
    val chatTemplateKwargs: ChatTemplateKwargs? = this.options?.enableThinking?.let { ChatTemplateKwargs(it) }

    /** 實測 `json_schema` 會走受限解碼（guided decoding），不只是 JSON mode */
    @SerialName("response_format")
    val responseFormat: ResponseFormat = if (jsonSchemaSpec != null && jsonSchemaSpec.schema["type"] != JsonPrimitive("string")) {
      ResponseFormat.JsonSchemaResponse(jsonSchemaSpec)
    } else {
      ResponseFormat.TextResponse
    }
  }
}
