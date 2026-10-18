/**
 * Created by smallufo on 2026-10-18.
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
 * OpenRouter（`openrouter.ai/api/v1`）chat schema。https://openrouter.ai/docs/api-reference/chat-completion
 *
 * wire shape 與 OpenAI 相同，message 直接複用 [OpenAi.Message]。差異：
 *  - 思考開關走統一的 `reasoning: {enabled}`，由 OpenRouter 轉成各上游自家的參數
 *  - 回覆的思考內容放在 `message.reasoning`（不是 `reasoning_content`），[OpenAi.Message] 不讀它，所以 think 會是 null
 *  - 錯誤外殼 `{"error":{"code":429,"message":…,"metadata":{…}}}` 的 `code` 是**數字**，decode 端要 `isLenient`
 */
class OpenRouter {

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
  }

  object ResponseSerializer : JsonContentPolymorphicSerializer<Response>(Response::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Response> {
      return if ("error" in element.jsonObject) Response.ErrorResponse.serializer()
      else Response.NormalResponse.serializer()
    }
  }

  @Serializable
  data class Reasoning(val enabled: Boolean)

  data class OpenRouterOptions(
    /** 0 .. 2 */
    val temperature: Double? = null,
    /** 0 .. 1 */
    val topP: Double? = null,
    /** -2.0 .. 2.0 , default 0 */
    val frequencyPenalty: Double? = null,
    /** null → 不送，沿用 model 預設 */
    val enableReasoning: Boolean? = null,
  ) {
    companion object {
      fun ChatOptions.toOpenRouter(): OpenRouterOptions {
        return OpenRouterOptions(
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
    val options: OpenRouterOptions? = null,
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

    val reasoning: Reasoning? = this.options?.enableReasoning?.let { Reasoning(it) }

    /** 上游不支援時 OpenRouter 預設忽略（不加 `provider.require_parameters`），不會回錯 */
    @SerialName("response_format")
    val responseFormat: ResponseFormat = if (jsonSchemaSpec != null && jsonSchemaSpec.schema["type"] != JsonPrimitive("string")) {
      ResponseFormat.JsonSchemaResponse(jsonSchemaSpec)
    } else {
      ResponseFormat.TextResponse
    }
  }
}
