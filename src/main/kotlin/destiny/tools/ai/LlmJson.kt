/**
 * Created by smallufo on 2026-09-09.
 */
package destiny.tools.ai

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json


/**
 * 解碼 LLM 回覆用的 [Json]，全專案只有這兩份。
 *
 * 先前 `ResilientChatService` / `HedgeChatService` / `AbstractChatCompletionTest` 各抄一份相同設定，
 * `Captcha*Impl` 的那份又少了 `ignoreUnknownKeys`。設定分散還不是重點 —— 重點是**沒有一份能揭露
 * schema 漂移**：模型多交一個欄位（例如把 schema 根層的 `description` 抄成輸出欄位），
 * `ignoreUnknownKeys` 就靜靜吃掉，沒有人知道。
 *
 * - [lenient]：生產用。容忍未知欄位、尾逗號、未加引號的值 —— 模型的小瑕疵不該讓整份回覆作廢。
 * - [strict]：測試 / 體檢用。與 [lenient] **只差** `ignoreUnknownKeys = false`，所以它失敗而
 *   [lenient] 成功時，差別一定是「模型多交了什麼」。[driftReport] 就是這個差集。
 */
object LlmJson {

  val lenient: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    allowTrailingComma = true
  }

  val strict: Json = Json {
    ignoreUnknownKeys = false
    isLenient = true
    allowTrailingComma = true
  }

  /**
   * 模型多交了什麼？[lenient] 解得開、[strict] 解不開時回 kotlinx 的訊息（含未知的 key 名與路徑）；
   * 兩者都解得開回 null。呼叫端把它 log 出來就好 —— 這不是失敗，是提示詞或 schema 該檢查的訊號。
   */
  fun <T> driftReport(deserializer: DeserializationStrategy<T>, raw: String): String? = try {
    strict.decodeFromString(deserializer, raw)
    null
  } catch (e: SerializationException) {
    e.message ?: e::class.simpleName
  }
}
