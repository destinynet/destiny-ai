/**
 * Created by smallufo on 2026-11-03.
 */
package destiny.tools.ai

import destiny.tools.ai.model.FormatSpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import mu.KotlinLogging
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 可序列化的 sealed 基底 → `anyOf`（[toJsonSchema] 的 handleSealedType）。
 * 只驗 schema 的形狀，以及「照 schema 寫的 JSON 解得回來」；實際交給 Claude 的測試在 destiny-core-impl `ClaudeSealedSchemaTest`
 */
class SealedSchemaTest {

  private val logger = KotlinLogging.logger { }

  @Serializable
  sealed class Shape {
    @Serializable
    @SerialName("circle")
    data class Circle(val radius: Double) : Shape()

    @Serializable
    @SerialName("rect")
    data class Rect(val width: Double, val height: Double, val label: String? = null) : Shape()

    @Serializable
    @SerialName("none")
    data object Empty : Shape()
  }

  @Serializable
  data class Drawing(val title: String, val shapes: List<Shape>, val focus: Shape)

  /** sealed 底下還有 sealed：攤平成葉子 */
  @Serializable
  sealed interface Node {
    @Serializable
    @SerialName("leaf")
    data class Leaf(val value: Int) : Node

    @Serializable
    sealed interface Group : Node

    @Serializable
    @SerialName("pair")
    data class Pair(val left: Int, val right: Int) : Group
  }

  @Serializable
  data class Tree(val nodes: List<Node>)

  /** 依出現的 key 選子類別（DSL 的 `{point}`／`{house}` 寫法）：沒有判別欄位 */
  @Serializable(with = TargetSerializer::class)
  sealed interface Target {
    @Serializable
    data class PointTarget(val point: String) : Target

    @Serializable
    data class HouseTarget(val house: Int) : Target
  }

  object TargetSerializer : JsonContentPolymorphicSerializer<Target>(Target::class) {
    override fun selectDeserializer(element: JsonElement) = when {
      "point" in element.jsonObject -> Target.PointTarget.serializer()
      else                          -> Target.HouseTarget.serializer()
    }
  }

  @Serializable
  data class Shot(val highlight: List<Target>)

  private fun JsonObject.obj(vararg path: String): JsonObject = path.fold(this) { o, k -> o[k]!!.jsonObject }
  private fun JsonObject.branches(): List<JsonObject> = this["anyOf"]!!.jsonArray.map { it.jsonObject }
  private fun JsonObject.required(): List<String> = this["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
  private fun JsonObject.typeName(): String = obj("properties", "type")["enum"]!!.jsonArray.single().jsonPrimitive.content
  /** 分支依類別名排序（執行期拿不到宣告序），測試以判別值找分支 */
  private fun JsonObject.branch(type: String): JsonObject = branches().single { it.typeName() == type }

  @Test
  fun `sealed 屬性與清單元素都產生 anyOf 判別欄位在前`() {
    val schema = FormatSpec.of<Drawing>("Drawing", "d").jsonSchema.schema
    logger.info { schema }

    val items = schema.obj("properties", "shapes", "items")
    val branches = items.branches()
    assertEquals(3, branches.size)
    assertEquals(setOf("circle", "rect", "none"), branches.map { it.typeName() }.toSet())
    branches.forEach { assertEquals("type", it.obj("properties").keys.first(), "判別欄位放第一個") }

    val rect = items.branch("rect")
    assertEquals(listOf("type", "width", "height", "label"), rect.obj("properties").keys.toList())
    assertEquals(listOf("type", "width", "height"), rect.required(), "可省略的 label 不列 required")
    assertEquals(listOf("type"), items.branch("none").required(), "data object 只有判別欄位")

    assertEquals(3, schema.obj("properties", "focus").branches().size, "單一屬性也是 anyOf")
  }

  @Test
  fun `照 schema 寫的 JSON 解得回 sealed 型別`() {
    val json = """
      {"title":"t","shapes":[{"type":"circle","radius":1.5},{"type":"rect","width":2,"height":3},{"type":"none"}],
       "focus":{"type":"rect","width":1,"height":1,"label":"x"}}
    """.trimIndent()
    val d = LlmJson.strict.decodeFromString(Drawing.serializer(), json)
    assertEquals(listOf(Shape.Circle(1.5), Shape.Rect(2.0, 3.0), Shape.Empty), d.shapes)
    assertEquals(Shape.Rect(1.0, 1.0, "x"), d.focus)
  }

  @Test
  fun `巢狀 sealed 攤平成葉子`() {
    val schema = FormatSpec.of<Tree>("Tree", "t").jsonSchema.schema
    val names = schema.obj("properties", "nodes", "items").branches().map { it.typeName() }
    assertEquals(setOf("leaf", "pair"), names.toSet(), "中間那層 sealed（Group）不是分支")
    val t = LlmJson.strict.decodeFromString(Tree.serializer(), """{"nodes":[{"type":"leaf","value":1},{"type":"pair","left":1,"right":2}]}""")
    assertEquals(listOf(Node.Leaf(1), Node.Pair(1, 2)), t.nodes)
  }

  @Test
  fun `依內容選型別的 serializer 不帶判別欄位`() {
    val schema = FormatSpec.of<Shot>("Shot", "s").jsonSchema.schema
    logger.info { schema }
    val branches = schema.obj("properties", "highlight", "items").branches()
    assertEquals(setOf(listOf("point"), listOf("house")), branches.map { it.obj("properties").keys.toList() }.toSet())
    assertEquals(setOf(listOf("point"), listOf("house")), branches.map { it.required() }.toSet())
    branches.forEach { assertTrue("type" !in it.obj("properties"), "依內容選型別時沒有判別欄位") }
    val shot = LlmJson.strict.decodeFromString(Shot.serializer(), """{"highlight":[{"point":"Planet.SUN"},{"house":7}]}""")
    assertEquals(listOf(Target.PointTarget("Planet.SUN"), Target.HouseTarget(7)), shot.highlight)
  }

  @Test
  fun `Claude 方言：每個分支都關閉開放欄位`() {
    val rendered = FormatSpec.of<Drawing>("Drawing", "d").jsonSchema.render(SchemaDialect.CLAUDE)
    val items = rendered.obj("properties", "shapes", "items")
    assertNull(items["additionalProperties"], "anyOf 節點本身沒有 type，不必加")
    items.branches().forEach { assertEquals(JsonPrimitive(false), it["additionalProperties"]) }
    assertTrue(items.branch("rect").obj("properties").containsKey("label"))
  }
}
