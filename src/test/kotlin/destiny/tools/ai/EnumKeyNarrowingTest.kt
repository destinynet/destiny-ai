/**
 * Created by smallufo on 2026-09-09.
 */
package destiny.tools.ai

import destiny.tools.ai.model.FormatSpec
import destiny.tools.ai.model.narrowEnumKeys
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * enum-keyed map 預設「每個 key 都 required」（fail-closed）。
 * 只想要子集的呼叫端，用 [narrowEnumKeys] 把 schema 收窄到它真正會填的那幾個 key ——
 * 收窄的是 properties 本身而不只是 required：配上 `additionalProperties:false`，
 * 未列入的 key 從「請忽略」變成「填了就不合法」。
 */
class EnumKeyNarrowingTest {

  enum class Domain {
    GENERAL, LOVE, CAREER,
    @SerialName("secret_stuff") SECRET,
  }

  @Serializable
  data class Reply(val domains: Map<Domain, String>, val note: String)

  private val full = FormatSpec.of<Reply>("reply", "test")

  private fun JsonObject.obj(vararg path: String): JsonObject = path.fold(this) { acc, k -> acc[k]!!.jsonObject }
  private fun JsonObject.strings(key: String): List<String> = this[key]!!.jsonArray.map { it.jsonPrimitive.content }

  @Test
  fun `narrowing keeps only the listed keys, in schema order, and requires exactly them`() {
    val narrowed = full.narrowEnumKeys("domains", listOf(Domain.CAREER, Domain.GENERAL, Domain.SECRET))
    val domains = narrowed.jsonSchema.schema.obj("properties", "domains")

    assertEquals(listOf("GENERAL", "CAREER", "secret_stuff"), domains.obj("properties").keys.toList())
    assertEquals(listOf("GENERAL", "CAREER", "secret_stuff"), domains.strings("required"))
    assertEquals(JsonPrimitive(false), domains["additionalProperties"])
    // 收窄後的值 schema 原封不動
    assertEquals("string", domains.obj("properties", "CAREER")["type"]!!.jsonPrimitive.content)
  }

  @Test
  fun `everything outside the narrowed property is untouched`() {
    val narrowed = full.narrowEnumKeys("domains", listOf(Domain.LOVE))
    val schema = narrowed.jsonSchema.schema

    assertEquals(full.jsonSchema.schema.obj("properties", "note"), schema.obj("properties", "note"))
    assertEquals(full.jsonSchema.schema.strings("required"), schema.strings("required"))
    assertEquals(full.jsonSchema.name, narrowed.jsonSchema.name)
    assertEquals(full.jsonSchema.description, narrowed.jsonSchema.description)
    assertSame(full.serializer, narrowed.serializer)
    assertSame(full.kClass, narrowed.kClass)
  }

  @Test
  fun `the original spec is not mutated`() {
    full.narrowEnumKeys("domains", listOf(Domain.LOVE))
    assertEquals(4, full.jsonSchema.schema.obj("properties", "domains").obj("properties").size)
  }

  @Test
  fun `a property that is not an enum-keyed map is rejected`() {
    assertFailsWith<IllegalArgumentException> { full.narrowEnumKeys("note", listOf(Domain.LOVE)) }
    assertFailsWith<IllegalArgumentException> { full.narrowEnumKeys("nope", listOf(Domain.LOVE)) }
  }

  @Test
  fun `an empty key set is rejected`() {
    assertFailsWith<IllegalArgumentException> { full.narrowEnumKeys("domains", emptyList()) }
  }
}
