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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 可序列化的 sealed 基底 → `anyOf`（[toJsonSchema] 的 handleSealedType），出現兩次以上搬進 `$defs`（[hoistSealedDefs]）。
 * 只驗 schema 的形狀，以及「照 schema 寫的 JSON 解得回來」；實際交給 Claude 的測試在 destiny-core-impl `ClaudeSealedSchemaTest`
 */
class SealedSchemaTest {

  private val logger = KotlinLogging.logger { }

  @Serializable
  @Description("a shape on the canvas")
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

  /** Shape 出現兩次（清單元素、單一屬性）→ `$defs` */
  @Serializable
  data class Drawing(
    val title: String,
    val shapes: List<Shape>,
    @Description("the shape to look at first")
    val focus: Shape,
  )

  /** Shape 只出現一次 → 內嵌 */
  @Serializable
  data class Single(val shapes: List<Shape>)

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

  /** 星盤導演的形狀：Target 出現在鏡頭（只出現一次的 Camera 裡）、highlight、每一步的 draw */
  @Serializable
  sealed class Camera {
    @Serializable @SerialName("full") data object Full : Camera()
    @Serializable @SerialName("fit") data class Fit(val targets: List<Target>) : Camera()
  }

  @Serializable
  data class Step(val say: String, val draw: List<Target>)

  @Serializable
  data class DirectorShot(val camera: Camera, val highlight: List<Target>, val steps: List<Step>)

  @Serializable
  data class Script(val shots: List<DirectorShot>)

  // ───────── helpers ─────────

  private fun JsonObject.obj(vararg path: String): JsonObject = path.fold(this) { o, k -> o[k]!!.jsonObject }
  private fun JsonObject.required(): List<String> = this["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
  private fun JsonObject.typeName(): String = obj("properties", "type")["enum"]!!.jsonArray.single().jsonPrimitive.content
  private fun JsonObject.ref(): String? = (this["\$ref"] as? JsonPrimitive)?.content

  /** `$ref` 換成 `$defs` 裡的定義（測試用；只解一層） */
  private fun JsonObject.deref(root: JsonObject): JsonObject =
    ref()?.let { root.obj("\$defs", it.removePrefix("#/\$defs/")) } ?: this

  private fun JsonObject.branches(root: JsonObject): List<JsonObject> = deref(root)["anyOf"]!!.jsonArray.map { it.jsonObject }

  /** 分支依類別名排序（執行期拿不到宣告序），測試以判別值找分支 */
  private fun JsonObject.branch(root: JsonObject, type: String): JsonObject = branches(root).single { it.typeName() == type }

  private fun schemaOf(spec: FormatSpec<*>): JsonObject = spec.jsonSchema.schema.also { logger.info { it } }

  // ───────── anyOf ─────────

  @Test
  fun `sealed 屬性與清單元素都產生 anyOf 判別欄位在前`() {
    val schema = schemaOf(FormatSpec.of<Drawing>("Drawing", "d"))

    val items = schema.obj("properties", "shapes", "items")
    val branches = items.branches(schema)
    assertEquals(setOf("circle", "rect", "none"), branches.map { it.typeName() }.toSet())
    branches.forEach { assertEquals("type", it.obj("properties").keys.first(), "判別欄位放第一個") }

    val rect = items.branch(schema, "rect")
    assertEquals(listOf("type", "width", "height", "label"), rect.obj("properties").keys.toList())
    assertEquals(listOf("type", "width", "height"), rect.required(), "可省略的 label 不列 required")
    assertEquals(listOf("type"), items.branch(schema, "none").required(), "data object 只有判別欄位")

    assertEquals(3, schema.obj("properties", "focus").branches(schema).size, "單一屬性也是 anyOf")
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
    val schema = schemaOf(FormatSpec.of<Tree>("Tree", "t"))
    val names = schema.obj("properties", "nodes", "items").branches(schema).map { it.typeName() }
    assertEquals(setOf("leaf", "pair"), names.toSet(), "中間那層 sealed（Group）不是分支")
    val t = LlmJson.strict.decodeFromString(Tree.serializer(), """{"nodes":[{"type":"leaf","value":1},{"type":"pair","left":1,"right":2}]}""")
    assertEquals(listOf(Node.Leaf(1), Node.Pair(1, 2)), t.nodes)
  }

  @Test
  fun `依內容選型別的 serializer 不帶判別欄位`() {
    val schema = schemaOf(FormatSpec.of<Shot>("Shot", "s"))
    val branches = schema.obj("properties", "highlight", "items").branches(schema)
    assertEquals(setOf(listOf("point"), listOf("house")), branches.map { it.obj("properties").keys.toList() }.toSet())
    assertEquals(setOf(listOf("point"), listOf("house")), branches.map { it.required() }.toSet())
    branches.forEach { assertTrue("type" !in it.obj("properties"), "依內容選型別時沒有判別欄位") }
    val shot = LlmJson.strict.decodeFromString(Shot.serializer(), """{"highlight":[{"point":"Planet.SUN"},{"house":7}]}""")
    assertEquals(listOf(Target.PointTarget("Planet.SUN"), Target.HouseTarget(7)), shot.highlight)
  }

  // ───────── $defs ─────────

  @Test
  fun `出現兩次以上搬進 defs 出現處用 ref`() {
    val schema = schemaOf(FormatSpec.of<Drawing>("Drawing", "d"))
    assertEquals(setOf("Shape"), schema.obj("\$defs").keys)
    assertEquals("#/\$defs/Shape", schema.obj("properties", "shapes", "items").ref())
    assertEquals("#/\$defs/Shape", schema.obj("properties", "focus").ref())
    assertEquals(3, schema.obj("\$defs", "Shape")["anyOf"]!!.jsonArray.size)
  }

  @Test
  fun `只出現一次就內嵌 沒有 defs`() {
    val schema = schemaOf(FormatSpec.of<Single>("Single", "s"))
    assertNull(schema["\$defs"])
    val items = schema.obj("properties", "shapes", "items")
    assertNull(items.ref())
    assertEquals(3, items["anyOf"]!!.jsonArray.size)
    assertEquals("a shape on the canvas", items["description"]!!.jsonPrimitive.content, "內嵌時 class 層級的 description 放在節點上")
  }

  @Test
  fun `class 層級的 description 放定義上 屬性層級的留在 ref 旁`() {
    val schema = schemaOf(FormatSpec.of<Drawing>("Drawing", "d"))
    assertEquals("a shape on the canvas", schema.obj("\$defs", "Shape")["description"]!!.jsonPrimitive.content)
    val focus = schema.obj("properties", "focus")
    assertEquals("the shape to look at first", focus["description"]!!.jsonPrimitive.content)
    assertNull(schema.obj("properties", "shapes", "items")["description"], "沒有屬性層級 description 的出現處只有 \$ref")
  }

  @Test
  fun `記號不會留在送出的 schema 裡`() {
    listOf(FormatSpec.of<Drawing>("D", "d"), FormatSpec.of<Single>("S", "s"), FormatSpec.of<Script>("Sc", "s")).forEach { spec ->
      assertFalse("x-destiny-sealed" in spec.jsonSchema.schema.toString(), spec.jsonSchema.name)
    }
  }

  @Test
  fun `導演腳本的形狀 Target 共用定義 Camera 內嵌`() {
    val schema = schemaOf(FormatSpec.of<Script>("Script", "s"))
    assertEquals(setOf("Target"), schema.obj("\$defs").keys, "Target 出現三次（fit、highlight、draw）；Camera 只出現一次")
    val shot = schema.obj("properties", "shots", "items", "properties")
    val camera = shot.obj("camera")
    assertNull(camera.ref())
    val fit = camera["anyOf"]!!.jsonArray.map { it.jsonObject }.single { it.typeName() == "fit" }
    assertEquals("#/\$defs/Target", fit.obj("properties", "targets", "items").ref(), "內嵌的 Camera 裡的 Target 也用引用")
    assertEquals("#/\$defs/Target", shot.obj("highlight", "items").ref())
    assertEquals("#/\$defs/Target", shot.obj("steps", "items", "properties", "draw", "items").ref())
  }

  // ───────── 方言 ─────────

  @Test
  fun `Claude 方言 defs 裡的分支也關閉開放欄位`() {
    val rendered = FormatSpec.of<Drawing>("Drawing", "d").jsonSchema.render(SchemaDialect.CLAUDE)
    val def = rendered.obj("\$defs", "Shape")
    assertNull(def["additionalProperties"], "anyOf 節點本身沒有 type，不必加")
    def["anyOf"]!!.jsonArray.forEach { assertEquals(JsonPrimitive(false), it.jsonObject["additionalProperties"]) }
    assertNull(rendered.obj("properties", "focus")["additionalProperties"], "\$ref 節點沒有 type，不加")
  }

  @Test
  fun `Gemini 方言把 ref 展開回內嵌`() {
    val spec = FormatSpec.of<Drawing>("Drawing", "d").jsonSchema
    val rendered = spec.render(SchemaDialect.GEMINI)
    val text = rendered.toString()
    assertFalse("\$ref" in text || "\$defs" in text, text)
    val focus = rendered.obj("properties", "focus")
    assertEquals(3, focus["anyOf"]!!.jsonArray.size)
    assertEquals("the shape to look at first", focus["description"]!!.jsonPrimitive.content, "出現處的 description 蓋過定義的")
    assertEquals(3, rendered.obj("properties", "shapes", "items")["anyOf"]!!.jsonArray.size)
    assertEquals("a shape on the canvas", rendered.obj("properties", "shapes", "items")["description"]!!.jsonPrimitive.content)
  }

  @Test
  fun `hasOpenMap 也看得到 defs 裡的節點`() {
    @Suppress("unused")
    val withOpen = JsonSchemaSpec("x", null, buildJsonObject {
      put("type", "object")
      putJsonObject("properties") { putJsonObject("a") { put("\$ref", "#/\$defs/M") } }
      putJsonObject("\$defs") { putJsonObject("M") { put("type", "object"); putJsonObject("additionalProperties") { put("type", "string") } } }
    })
    assertTrue(withOpen.hasOpenMap())
  }
}
