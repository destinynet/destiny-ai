/**
 * Created by smallufo on 2025-04-04.
 */
package destiny.tools.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class JsonSchemaSpec(
  /**
   * pattern '^[a-zA-Z0-9_-]+$'
   */
  val name: String,
  val description: String?, val schema: JsonObject)


/**
 * schema → 提示詞裡的「輸出格式」區塊。
 *
 * ## 為什麼需要它
 *
 * `IChatCompletion.chatComplete` 收得到 [JsonSchemaSpec]，但十二個 chat impl 對它的處置差很多
 * （2026-09-09 現況）：
 *
 * | 處置 | impl |
 * |---|---|
 * | 送出完整 schema | OpenAi、Mistral（`response_format: json_schema`）、Gemini（`responseSchema`）、Claude（`output_config.format`） |
 * | 只開 JSON mode，schema 丟掉 | Groq、XiaoMi（`response_format: json_object`） |
 * | 刻意不開 | Deepseek（實測 `json_object` 反而讓品質變差，見 `DeepseekImpl` 的 KDoc） |
 * | 簽名收下就沒有下文 | Cohere、Reka、Cerebras、Together、Xai |
 *
 * 而提示詞那側原本寫的是
 *
 * > only provide a RFC8259 compliant JSON response **following this format** without deviation
 *
 * —— "this format" 指的那份 format 從來沒有出現在提示詞裡。對上述九家而言，模型收到的全部訊息
 * 就是「請回 JSON」：欄位名、型別、enum 值域、`minItems` 一個都拿不到，只有實作了
 * `fieldGuidance()` / `exampleOutput()` 的 digester 靠序列化一份實例間接補上。
 *
 * 本函式就是把那份 format 補回去。langchain4j 在 provider 不支援 JSON schema 時會自動退回
 * 這條路（它自承 "quite unreliable"，但至少模型看得到欄位）；spring-ai 的
 * `BeanOutputConverter.getFormat()` 更是**預設**就走這條，其樣板的最後一行正是本函式的出處。
 *
 * ⚠️ **對已經原生送出 schema 的四家（OpenAi / Mistral / Gemini / Claude）這是重複的 token。**
 * 這是刻意的取捨：digest 階段拿不到 provider，而「少數人多付一點 token」遠優於
 * 「多數人完全不知道要填什麼」。要改成 provider-aware 的開關，需要一個
 * per-model 的能力宣告（audit §7.3：`Capability.JSON_SCHEMA`）—— 那件事還沒做。
 *
 * 用裸 ``` 圍籬（不寫 ```json）—— 同一段指令的下一句要求模型「把輸出的 ```json 拿掉」，
 * 圍籬標成 json 會讓那兩句互相打架。
 */
fun JsonSchemaSpec.toPromptBlock(): String = buildString {
  appendLine("Here is the JSON Schema instance your output must adhere to:")
  appendLine("```")
  appendLine(schema.toString())
  append("```")
}
