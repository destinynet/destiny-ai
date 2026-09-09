/**
 * Created by smallufo on 2026-07-02 (as PostProcessorJsonExtractTest in destiny-core-impl); moved 2026-09-09.
 */
package destiny.tools.ai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 從夾雜散文與 markdown 圍籬的回覆裡切出 JSON 本體。
 * 先前是 core-impl 的 `PostProcessorJsonExtract`，只掛在四個 domain 上；
 * 現在是 `typedChatComplete` 對每個非 String 輸出都會做的一步。
 */
class JsonExtractTest {

  @OptIn(ExperimentalSerializationApi::class)
  private val jsonParser = Json { allowTrailingComma = true }

  @Test
  fun `fenced object is unwrapped`() {
    val raw = """
      ```json
      {
         "key1" : "value1",
         "key2" : "value2"
      }
      ```
    """.trimIndent()
    val map = jsonParser.decodeFromString<Map<String, String>>(JsonExtract.extract(raw))
    assertEquals(mapOf("key1" to "value1", "key2" to "value2"), map)
  }

  @Test
  fun `trailing comma survives extraction, the decoder deals with it`() {
    val raw = "```json\n{\n \"key1\" : \"value1\",\n \"key2\" : \"value2\",\n}\n```"
    val map = jsonParser.decodeFromString<Map<String, String>>(JsonExtract.extract(raw))
    assertEquals("value2", map["key2"])
  }

  // ─────────────────────────────────────────────────────────────
  // 2026-09-08：陣列分支從來沒有生效過（正則寫成 `\\[.*]`），物件分支貪婪地把外層 [ ] 剝掉。
  // ListContainerSerializer 明確支援裸陣列，這一步不能把它打死。
  // ─────────────────────────────────────────────────────────────

  @Test
  fun `bare array survives intact`() {
    val raw = """[{"id":1},{"id":2}]"""
    assertEquals(raw, JsonExtract.extract(raw))
  }

  @Test
  fun `bare array of scalars survives intact`() {
    val raw = """["a","b"]"""
    assertEquals(raw, JsonExtract.extract(raw))
  }

  @Test
  fun `array wrapped in prose and markdown fence is extracted`() {
    val raw = "Sure! Here you go:\n```json\n[{\"id\":1}]\n```\nHope that helps."
    assertEquals("""[{"id":1}]""", JsonExtract.extract(raw))
  }

  @Test
  fun `object containing an array keeps the object as the root`() {
    val raw = """{"array":[1,2]}"""
    assertEquals(raw, JsonExtract.extract(raw))
  }

  @Test
  fun `stray bracket in prose does not hijack a valid object`() {
    // 修好陣列分支之後的新風險：散文裡的 `[...]` 起始位置可能比真正的 JSON 更早 —— 靠「可解析者優先」擋掉
    val raw = """Note [see below]: {"a":1}"""
    assertEquals("""{"a":1}""", JsonExtract.extract(raw))
  }

  @Test
  fun `no json at all returns raw unmodified`() {
    val raw = "完全沒有 JSON 的一段話。"
    assertEquals(raw, JsonExtract.extract(raw))
  }

  @Test
  fun `unparseable candidates fall back to the earliest match`() {
    // 字串內含真實換行的「近似 JSON」：沒有候選可解析時取最早的 —— 驗證只用來排序，不用來否決
    val raw = "前言 {\"a\": \"換\n行\"} 後語"
    assertEquals("{\"a\": \"換\n行\"}", JsonExtract.extract(raw))
  }
}
