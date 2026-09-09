/**
 * Created by smallufo on 2026-09-09.
 */
package destiny.tools.ai

import mu.KotlinLogging
import kotlinx.serialization.json.*


/**
 * 同一份 canonical schema，送進各家 API 之前的**方言降級**。
 *
 * ## 為什麼要有這一層
 *
 * 「把 schema 送進 request」看起來是各家各自接線的小事，實際上每一家都附帶一組
 * 自己的子集限制，而那些限制彼此**方向相反**：
 *
 * | | `additionalProperties` | `minItems` | `format` |
 * |---|---|---|---|
 * | OpenAI (非 strict) | 可有可無 | 收 | 收 |
 * | OpenAI (`strict:true`) | **必須 `false`** | 收 | 收 |
 * | Gemini `responseSchema` | **不收（送了會 400）** | 收 | 只收白名單 |
 * | Claude `output_config.format` | **必須 `false`** | 只收 0 或 1 | 多數不收 |
 *
 * 沒有這一層的話，這些改寫會散在三個 `*Impl.encodeRequest` 裡各寫一次，
 * 而它們處理的是同一件事：**把一份 canonical schema 降級成某家收得下的形狀。**
 * 分散之後，新增第四家就是再抄一次，而且沒有地方可以一次看出「哪一家丟掉了什麼」。
 *
 * ## 降級一定有損失，所以要出聲
 *
 * 降級不是無損的 —— Gemini 丟掉 `additionalProperties` 之後，
 * `Map<String, X>` 的值型別就消失了，schema 只剩 `{"type":"object"}`。
 * 這種時候 [render] 會 `logger.warn` 一次，而不是靜默照做：
 * 靜默降級正是 audit §7.4 要處理的那一類問題 —— 使用者無從得知自己踩到了哪一格。
 *
 * @see JsonSchemaSpec.render
 */
enum class SchemaDialect {
  /** 原樣送出。OpenAI（非 strict）/ Mistral 走這條。 */
  CANONICAL,

  /**
   * Vertex AI `generationConfig.responseSchema` —— OpenAPI 3.0 Schema 的子集。
   *
   * - 不認得 `additionalProperties`（送了直接 400 `INVALID_ARGUMENT`）
   * - `format` 只收白名單（見 [GEMINI_FORMATS]）；本專案的 `LocalDate` 會產出
   *   `"format":"date"`，不在白名單內
   */
  GEMINI,

  /**
   * Anthropic `output_config.format.schema`。
   *
   * - 每個 object 都**必須**帶 `additionalProperties:false`
   * - 不收數值／長度類約束（`minimum` / `maximum` / `multipleOf` / `minLength` / `maxLength` / `pattern`）
   * - `minItems` 只接受 0 或 1 —— 與 [Size] 直接衝突，超過 1 的下限只能丟掉
   *   （丟掉時會 warn，因為那正是 [Size] 存在的理由：讓限制不再只是散文裡的請求）
   */
  CLAUDE,
}

private val logger = KotlinLogging.logger { }

/** Gemini `format` 白名單。其餘一律移除。 */
private val GEMINI_FORMATS = setOf("date-time", "enum", "float", "double", "int32", "int64")

/** Claude 不收的數值／長度類約束。 */
private val CLAUDE_UNSUPPORTED = setOf("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "pattern")

/**
 * 產出指定方言的 schema body。
 *
 * ⚠️ 回傳的是 **schema 本體**（[JsonSchemaSpec.schema] 的降級版），不含 `name` / `description`
 * 這層信封 —— 各家把信封放在不同位置（OpenAI 在 `json_schema.name`、Gemini 根本沒有），
 * 由呼叫端各自組裝。
 */
fun JsonSchemaSpec.render(dialect: SchemaDialect): JsonObject = when (dialect) {
  SchemaDialect.CANONICAL -> schema
  SchemaDialect.GEMINI    -> schema.transformNodes { it.toGeminiNode(name) }
  SchemaDialect.CLAUDE    -> schema.transformNodes { it.toClaudeNode(name) }
}

private fun JsonObject.toGeminiNode(specName: String): JsonObject {
  return buildJsonObject {
    this@toGeminiNode.forEach { (key, value) ->
      when {
        key == "additionalProperties" -> {
          // `additionalProperties: {…}`（generic Map 的值型別）被丟掉才是真的有損失；
          // `additionalProperties: false`（enum-keyed map 已列完 properties）只是關掉開放欄位，丟掉無妨。
          if (value is JsonObject) {
            logger.warn { "[$specName] Gemini 不支援 additionalProperties：Map 的值型別將從 schema 消失，模型只會看到 {\"type\":\"object\"}" }
          }
        }

        key == "format" && value.jsonPrimitiveContentOrNull() !in GEMINI_FORMATS -> {
          logger.warn { "[$specName] Gemini 不支援 format=${value.jsonPrimitiveContentOrNull()}，已移除（欄位仍是 string，只是少了格式提示）" }
        }

        else                          -> put(key, value)
      }
    }
  }
}

private fun JsonObject.toClaudeNode(specName: String): JsonObject {
  return buildJsonObject {
    this@toClaudeNode.forEach { (key, value) ->
      when {
        key in CLAUDE_UNSUPPORTED -> logger.warn { "[$specName] Claude 不支援 $key，已移除" }

        // Claude 要求 additionalProperties 必須是 false，所以 generic Map 的值型別無處可去。
        // 下面一律補 false，這裡只負責讓損失出聲。
        key == "additionalProperties" -> {
          if (value is JsonObject) {
            logger.warn { "[$specName] Claude 要求 additionalProperties:false：Map 的值型別將從 schema 消失" }
          }
        }

        key == "minItems"         -> {
          val n = value.jsonPrimitiveIntOrNull()
          if (n != null && n > 1) {
            // @Size(min = 2) 這種下限在 Claude 這邊表達不出來。降成 1 只保證「不得為空」，
            // 真正的下限退回散文（fieldGuidance / KDoc）—— 這正是 [Size] 想避免的狀態，所以要 warn。
            logger.warn { "[$specName] Claude 的 minItems 只接受 0 或 1，$n 已降為 1；下限退回提示詞層級，不再是硬約束" }
            put(key, JsonPrimitive(1))
          } else {
            put(key, value)
          }
        }

        else                      -> put(key, value)
      }
    }
    // 每個 object 都要顯式關閉開放欄位，否則 Claude 直接拒收
    if (this@toClaudeNode["type"].jsonPrimitiveContentOrNull() == "object") {
      put("additionalProperties", JsonPrimitive(false))
    }
  }
}

/**
 * 對 schema 樹的每個節點套用 [rule]，先套用本層再遞迴子層。
 *
 * 子層的位置只有四處：`properties` 的每個值、`items`、`additionalProperties`（值型別）、
 * `anyOf` 的每個元素。先套 [rule] 再遞迴是刻意的 —— 這樣 [rule] 新增的
 * `additionalProperties: false` 不會又被當成子 schema 走一遍。
 */
private fun JsonObject.transformNodes(rule: (JsonObject) -> JsonObject): JsonObject {
  val applied = rule(this)
  return buildJsonObject {
    applied.forEach { (key, value) ->
      when {
        key == "properties" && value is JsonObject           ->
          putJsonObject("properties") {
            value.forEach { (propName, propValue) ->
              put(propName, if (propValue is JsonObject) propValue.transformNodes(rule) else propValue)
            }
          }

        (key == "items" || key == "additionalProperties") && value is JsonObject ->
          put(key, value.transformNodes(rule))

        key == "anyOf" && value is JsonArray                 ->
          putJsonArray("anyOf") {
            value.forEach { add(if (it is JsonObject) it.transformNodes(rule) else it) }
          }

        else                                                 -> put(key, value)
      }
    }
  }
}

private fun JsonElement?.jsonPrimitiveContentOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.jsonPrimitiveIntOrNull(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()
