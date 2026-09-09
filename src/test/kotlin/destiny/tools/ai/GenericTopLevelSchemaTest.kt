/**
 * Created by Claude on 2026-09-08.
 */
package destiny.tools.ai

import destiny.tools.ai.model.FormatSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `FormatSpec.of<T>` 在 **T 本身是泛型容器** 時的 schema。
 *
 * 2026-09-08 之前 `FormatSpec.of` 走的是 `T::class.toJsonSchema(...)`，型別參數在
 * `KClass` 那一步就掉了，於是頂層泛型的 schema 從「沒幫上忙」變成「主動誤導」。
 * 三種都有活的呼叫端：`Map<LifePath,String>`（DailyHoroscopeDigester）、
 * `ListContainer<EventInspection>`（MergedUserEventsPlan）、`ListContainer<String>`
 * （RandomStringDigester）。
 */
class GenericTopLevelSchemaTest {

  @Serializable
  enum class LifePath { CAREER, LOVE }

  @Serializable
  data class Item(val id: Int, val label: String)

  private fun JsonObject.obj(key: String) = this[key]!!.jsonObject
  private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

  @Test
  fun `enum-keyed Map exposes the enum keys, not kotlin Map's JVM API`() {
    val schema = FormatSpec.of<Map<LifePath, String>>("lifePath", "LifePath content").jsonSchema.schema

    val props = schema.obj("properties")
    // 舊產出：entries / keys / size / values —— 反射到的是 kotlin.collections.Map 自己
    assertEquals(setOf("CAREER", "LOVE"), props.keys)
    assertEquals("string", props.obj("CAREER").str("type"))
    assertEquals(JsonPrimitive(false), schema["additionalProperties"])
    // 頂層 enum-keyed map 同樣 fail-closed：每個 key 都 required
    assertEquals(listOf("CAREER", "LOVE"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })

    // 呼叫端的 description 與產生器自己的說明要併陳，不能互相蓋掉；
    // 但不能再說「只回提到的 key」—— 那句話與 required 打架
    val desc = schema.str("description")
    assertTrue(desc.contains("LifePath content"), desc)
    assertFalse(desc.contains("only return mentioned"), desc)
  }

  @Test
  fun `String-keyed Map falls back to additionalProperties`() {
    val schema = FormatSpec.of<Map<String, Int>>("counts", "counts by key").jsonSchema.schema

    assertEquals("object", schema.str("type"))
    assertEquals("integer", schema.obj("additionalProperties").str("type"))
    assertEquals("counts by key", schema.str("description"))
  }

  @Test
  fun `ListContainer of POJO details the element structure`() {
    val schema = FormatSpec.of<ListContainer<Item>>("items", "a list of items").jsonSchema.schema

    val array = schema.obj("properties").obj("array")
    assertEquals("array", array.str("type"))

    // 舊產出：items = {"type":"object"} —— Item 的欄位全丟
    val items = array.obj("items")
    assertEquals("object", items.str("type"))
    assertEquals(setOf("id", "label"), items.obj("properties").keys)
    assertEquals("integer", items.obj("properties").obj("id").str("type"))
    assertEquals(
      setOf("id", "label"),
      items["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
    )

    assertEquals(listOf("array"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
  }

  @Test
  fun `ListContainer of String says string, not object`() {
    val schema = FormatSpec.of<ListContainer<String>>("randomString", "random strings").jsonSchema.schema

    val array = schema.obj("properties").obj("array")
    // 舊產出：{"type":"object"} —— 叫模型在字串陣列裡填物件
    assertEquals("string", array.obj("items").str("type"))
  }

  @Test
  fun `ListContainer of enum keeps the closed value set`() {
    val schema = FormatSpec.of<ListContainer<LifePath>>("paths", "paths").jsonSchema.schema

    val items = schema.obj("properties").obj("array").obj("items")
    assertEquals("string", items.str("type"))
    assertEquals(
      setOf("CAREER", "LOVE"),
      items["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
    )
  }

  /**
   * 頂層是集合時，產得出 `{"type":"array",…}`，但 OpenAI / Claude / Gemini 的 structured output
   * 都要求 root 是 object —— 送出去就是 400。生產路徑一律走 [ListContainer]（root 是有一個
   * `array` 欄位的 object），這裡把「產得出來但送不出去」的形狀直接擋在 `FormatSpec.of`。
   */
  @Test
  fun `top-level List or Set is rejected, use ListContainer`() {
    val e = assertFailsWith<IllegalArgumentException> { FormatSpec.of<List<Item>>("items", "a list") }
    assertTrue(e.message!!.contains("ListContainer"), e.message)
    assertFailsWith<IllegalArgumentException> { FormatSpec.of<Set<Item>>("items", "a set") }
  }

  @Test
  fun `plain data class is unchanged`() {
    val schema = FormatSpec.of<Item>("item", "one item").jsonSchema.schema

    assertEquals("object", schema.str("type"))
    assertEquals(setOf("id", "label"), schema.obj("properties").keys)
    assertEquals("one item", schema.str("description"))
  }

  @Test
  fun `plain String is unchanged`() {
    val schema = FormatSpec.of<String>("text", "plain text").jsonSchema.schema

    assertEquals("string", schema.str("type"))
    assertEquals("plain text", schema.str("description"))
  }
}
