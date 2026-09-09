/**
 * Created by smallufo on 2026-09-09.
 */
package destiny.tools.ai

import destiny.tools.ai.model.FormatSpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 方言降級的回歸測試。
 *
 * 這一層存在的理由是「同一份 canonical schema，三家收得下的形狀不同、而且方向相反」——
 * 所以每個測試都同時釘住**改了什麼**與**沒改什麼**：只驗前者的話，
 * 哪天降級把整棵樹壓平了也照樣綠燈。
 */
class SchemaDialectTest {

  /** destiny-ai 不依賴 destiny-core，拿不到那邊的 LocalDateSerializer；這裡只需要它讓 `Inner` 可序列化。 */
  object LocalDateAsString : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
  }

  @Serializable
  enum class Kind { A, B }

  @Serializable
  data class Inner(
    val label: String,
    @Serializable(with = LocalDateAsString::class)
    val born: LocalDate,
  )

  @Serializable
  data class Outer(
    val name: String,
    val kind: Kind,
    val inner: Inner,
    @Size(min = 3, max = 7)
    val items: List<Inner> = emptyList(),
  )

  private val spec = FormatSpec.of<Outer>("outer", "測試用").jsonSchema

  private fun JsonObject.obj(vararg path: String): JsonObject = path.fold(this) { acc, k -> acc[k]!!.jsonObject }
  private fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.content

  /** 走遍整棵樹的每個 schema 節點（含 properties / items 的子節點）。 */
  private fun JsonObject.allNodes(): List<JsonObject> = buildList {
    add(this@allNodes)
    (this@allNodes["properties"] as? JsonObject)?.values?.filterIsInstance<JsonObject>()?.forEach { addAll(it.allNodes()) }
    (this@allNodes["items"] as? JsonObject)?.let { addAll(it.allNodes()) }
  }

  // ─── canonical：什麼都不做 ───

  @Test
  fun `CANONICAL is the identity`() {
    assertEquals(spec.schema, spec.render(SchemaDialect.CANONICAL))
  }

  @Test
  fun `the fixture really does contain everything the dialects must handle`() {
    // 這個測試不驗方言，它驗的是「測資本身有代表性」——
    // 產生器哪天不再產出 format:date 或 minItems，下面幾個測試會變成空轉而不自知
    val canonical = spec.render(SchemaDialect.CANONICAL)
    assertEquals("date", canonical.obj("properties", "inner", "properties", "born").strOrNull("format"))
    assertEquals(3, canonical.obj("properties", "items")["minItems"]!!.jsonPrimitive.int)
    assertEquals(7, canonical.obj("properties", "items")["maxItems"]!!.jsonPrimitive.int)
  }

  // ─── Gemini ───

  @Test
  fun `GEMINI drops the format values outside its whitelist, keeps the field`() {
    val born = spec.render(SchemaDialect.GEMINI).obj("properties", "inner", "properties", "born")

    assertNull(born["format"], "format=date 不在 Gemini 白名單，送了會 400")
    assertEquals("string", born.strOrNull("type"), "欄位本身必須留著 —— 只是少了格式提示")
  }

  @Test
  fun `GEMINI keeps everything it does support`() {
    val gemini = spec.render(SchemaDialect.GEMINI)

    assertEquals(setOf("name", "kind", "inner", "items"), gemini.obj("properties").keys)
    assertEquals(listOf("name", "kind", "inner"), gemini["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertEquals(listOf("A", "B"), gemini.obj("properties", "kind")["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
    // minItems / maxItems 是 Gemini 收得下的，不該被順手清掉
    assertEquals(3, gemini.obj("properties", "items")["minItems"]!!.jsonPrimitive.int)
    assertEquals(7, gemini.obj("properties", "items")["maxItems"]!!.jsonPrimitive.int)
    // 巢狀 object 的欄位要完整走下去，不能只處理根層
    assertEquals(setOf("label", "born"), gemini.obj("properties", "items", "items", "properties").keys)
  }

  @Test
  fun `GEMINI removes additionalProperties at every depth`() {
    val schema = buildJsonObject {
      put("type", "object")
      put("additionalProperties", false)
      putJsonObject("properties") {
        putJsonObject("child") {
          put("type", "object")
          put("additionalProperties", false)
          putJsonObject("properties") {
            putJsonObject("leaf") { put("type", "string") }
          }
        }
      }
    }

    val gemini = JsonSchemaSpec("t", null, schema).render(SchemaDialect.GEMINI)

    assertTrue(gemini.allNodes().all { it["additionalProperties"] == null }, gemini.toString())
    // 但結構本身要原封不動
    assertEquals("string", gemini.obj("properties", "child", "properties", "leaf").strOrNull("type"))
  }

  // ─── Claude ───

  @Test
  fun `CLAUDE closes every object, and only objects`() {
    val claude = spec.render(SchemaDialect.CLAUDE)

    val objects = claude.allNodes().filter { it.strOrNull("type") == "object" }
    assertEquals(3, objects.size, "根 + inner + items 的元素")
    assertTrue(objects.all { it["additionalProperties"] == JsonPrimitive(false) }, claude.toString())

    // 非 object 不該被塞進這個欄位
    assertNull(claude.obj("properties", "name")["additionalProperties"])
    assertNull(claude.obj("properties", "items")["additionalProperties"])
  }

  @Test
  fun `CLAUDE clamps minItems to 1 but leaves maxItems alone`() {
    val items = spec.render(SchemaDialect.CLAUDE).obj("properties", "items")

    // Claude 的 minItems 只收 0 或 1 —— @Size(min=3) 的下限在這一家表達不出來
    assertEquals(1, items["minItems"]!!.jsonPrimitive.int)
    assertEquals(7, items["maxItems"]!!.jsonPrimitive.int)
  }

  @Test
  fun `CLAUDE leaves a minItems it can actually express`() {
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("xs") {
          put("type", "array")
          put("minItems", 1)
        }
      }
    }

    val claude = JsonSchemaSpec("t", null, schema).render(SchemaDialect.CLAUDE)
    assertEquals(1, claude.obj("properties", "xs")["minItems"]!!.jsonPrimitive.int)
  }

  @Test
  fun `CLAUDE strips the numeric and length constraints it rejects`() {
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("n") {
          put("type", "integer")
          put("minimum", 0)
          put("maximum", 100)
        }
        putJsonObject("s") {
          put("type", "string")
          put("minLength", 2)
          put("pattern", "^[a-z]+$")
        }
      }
    }

    val claude = JsonSchemaSpec("t", null, schema).render(SchemaDialect.CLAUDE)

    claude.obj("properties", "n").also {
      assertNull(it["minimum"])
      assertNull(it["maximum"])
      assertEquals("integer", it.strOrNull("type"))
    }
    claude.obj("properties", "s").also {
      assertNull(it["minLength"])
      assertNull(it["pattern"])
      assertEquals("string", it.strOrNull("type"))
    }
  }

  // ─── 兩家的方向相反，這是這一層存在的理由 ───

  @Test
  fun `the two dialects disagree about additionalProperties on the same input`() {
    val gemini = spec.render(SchemaDialect.GEMINI)
    val claude = spec.render(SchemaDialect.CLAUDE)

    assertFalse("additionalProperties" in gemini.keys, "Gemini 送了會 400")
    assertEquals(JsonPrimitive(false), claude["additionalProperties"], "Claude 不送會拒收")
  }

  @Test
  fun `no dialect mutates the spec it was rendered from`() {
    val before = spec.schema.toString()
    spec.render(SchemaDialect.GEMINI)
    spec.render(SchemaDialect.CLAUDE)
    assertEquals(before, spec.schema.toString())
  }

  // ─── open map（Map<String, V>）：Claude 方言下是死結，要在送出前偵測 ───

  @Serializable
  data class Scores(val byName: Map<String, Int>)

  @Serializable
  data class Nested(val rows: List<Scores>)

  /**
   * `additionalProperties: {…}` 被丟掉、再補上 `false`：這個節點沒有 properties 又禁止額外欄位，
   * 唯一合法的值是 `{}`。yearly 的 `scores: Map<String, Int>` 就是這個形狀 —— 目前只因為該 segment
   * 帶著 tools 而沒送 schema。所以送出前必須能問「這份 schema 有 open map 嗎」。
   */
  @Test
  fun `CLAUDE turns an open map into a dead end, and hasOpenMap detects it before sending`() {
    val scores = FormatSpec.of<Scores>("scores", "probe").jsonSchema
    val node = scores.render(SchemaDialect.CLAUDE).obj("properties", "byName")
    assertEquals(JsonPrimitive(false), node["additionalProperties"])
    assertNull(node["properties"])

    assertTrue(scores.hasOpenMap())
    assertTrue(FormatSpec.of<Nested>("nested", "probe").jsonSchema.hasOpenMap(), "藏在 items 底下也要抓到")
    assertFalse(spec.hasOpenMap(), "enum-keyed map 與一般物件不是 open map")
  }
}
