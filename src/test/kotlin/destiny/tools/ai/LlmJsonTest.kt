/**
 * Created by smallufo on 2026-09-09.
 */
package destiny.tools.ai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 解碼 LLM 回覆的 Json 設定先前散在四處（Resilient / Hedge / 測試 / Captcha），且沒有一份能揭露
 * schema 漂移：模型多交一個欄位，`ignoreUnknownKeys` 就把它吃掉。這裡收成兩份：
 * 生產用 [LlmJson.lenient]，測試 / 體檢用 [LlmJson.strict] 讓多交的東西現形。
 */
class LlmJsonTest {

  @Serializable
  data class Item(val id: Int, val label: String)

  @Test
  fun `lenient tolerates unknown keys, trailing commas and unquoted values`() {
    val decoded = LlmJson.lenient.decodeFromString<Item>("""{"id": 1, "label": "a", "description": "copied from schema",}""")
    assertEquals(Item(1, "a"), decoded)
    assertEquals(Item(2, "b"), LlmJson.lenient.decodeFromString<Item>("""{id: 2, label: b}"""))
  }

  @Test
  fun `strict rejects an unknown key so schema drift is visible`() {
    assertFailsWith<SerializationException> {
      LlmJson.strict.decodeFromString<Item>("""{"id": 1, "label": "a", "description": "copied from schema"}""")
    }
    assertEquals(Item(1, "a"), LlmJson.strict.decodeFromString<Item>("""{"id": 1, "label": "a"}"""))
  }

  @Test
  fun `driftReport names the keys the model added`() {
    val report = LlmJson.driftReport(Item.serializer(), """{"id": 1, "label": "a", "description": "x"}""")
    assertEquals(true, report?.contains("description"), report)
    assertEquals(null, LlmJson.driftReport(Item.serializer(), """{"id": 1, "label": "a"}"""))
  }
}
