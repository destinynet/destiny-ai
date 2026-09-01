package destiny.tools.ai

import mu.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsonToolsTest {

  private val logger = KotlinLogging.logger { }

  // ========== Shared test classes ==========
  data class Foo(val id: Int, val name: String)

  enum class MyEnum { A, B }

  // Enum with @SerialName annotations
  enum class Status {
    @SerialName("active") ACTIVE,
    @SerialName("inactive") INACTIVE,
    @SerialName("pending_review") PENDING_REVIEW
  }

  @JvmInline
  value class UserId(val value: String)

  @JvmInline
  value class Email(val value: String)

  @JvmInline
  value class UserEmail(val email: Email)

  // Circular reference test classes (must be at outer class level)
  data class Node(val value: String, val next: Node?)
  data class RefA(val name: String, val refB: RefB?)
  data class RefB(val id: Int, val refA: RefA?)
  data class TreeNode(val label: String, val children: List<TreeNode>)
  data class Shared(val data: String)
  data class Diamond(val left: Shared, val right: Shared)

  // ========== Nested Test Classes ==========

  @Nested
  inner class PrimitiveTypeTest {

    @Test
    fun string() {
      val spec = String::class.toJsonSchema("String", "A simple string")
      val schema = spec.schema
      logger.info { "schema: $schema" }
      assertEquals("string", schema["type"]!!.jsonPrimitive.content)
      assertEquals("A simple string", schema["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun int() {
      val spec = Integer::class.toJsonSchema("Int", "A simple integer")
      val schema = spec.schema
      logger.info { "schema: $schema" }
      assertEquals("integer", schema["type"]!!.jsonPrimitive.content)
      assertEquals("A simple integer", schema["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `float double boolean`() {
      data class PrimsHolder(val f: Float, val d: Double, val b: Boolean)
      val spec = PrimsHolder::class.toJsonSchema("PrimsHolder", null)
      val props = spec.schema["properties"]!!.jsonObject
      assertEquals("number", props["f"]!!.jsonObject["type"]!!.jsonPrimitive.content)
      assertEquals("number", props["d"]!!.jsonObject["type"]!!.jsonPrimitive.content)
      assertEquals("boolean", props["b"]!!.jsonObject["type"]!!.jsonPrimitive.content)
      val req = spec.schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
      assertEquals(setOf("f", "d", "b"), req)
    }

    @Test
    fun `big types map to string without format`() {
      data class BigHolder(
        val bi: java.math.BigInteger,
        val bd: java.math.BigDecimal
      )
      val spec = BigHolder::class.toJsonSchema("BigHolder", null)
      val props = spec.schema["properties"]!!.jsonObject
      listOf("bi", "bd").forEach { name ->
        assertEquals("string", props[name]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(props[name]!!.jsonObject.containsKey("format"), "$name should not have format")
      }
    }
  }

  @Nested
  inner class DateTimeFormatTest {

    @Test
    fun `LocalDate should have format date`() {
      data class LocalDateHolder(val date: java.time.LocalDate)
      val spec = LocalDateHolder::class.toJsonSchema("LocalDateHolder", null)
      val prop = spec.schema["properties"]!!.jsonObject["date"]!!.jsonObject
      logger.info { "LocalDate schema: $prop" }
      assertEquals("string", prop["type"]!!.jsonPrimitive.content)
      assertEquals("date", prop["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `LocalDateTime should have format date-time`() {
      data class LocalDateTimeHolder(val dateTime: java.time.LocalDateTime)
      val spec = LocalDateTimeHolder::class.toJsonSchema("LocalDateTimeHolder", null)
      val prop = spec.schema["properties"]!!.jsonObject["dateTime"]!!.jsonObject
      logger.info { "LocalDateTime schema: $prop" }
      assertEquals("string", prop["type"]!!.jsonPrimitive.content)
      assertEquals("date-time", prop["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ZonedDateTime should have format date-time`() {
      data class ZonedDateTimeHolder(val zonedDateTime: java.time.ZonedDateTime)
      val spec = ZonedDateTimeHolder::class.toJsonSchema("ZonedDateTimeHolder", null)
      val prop = spec.schema["properties"]!!.jsonObject["zonedDateTime"]!!.jsonObject
      logger.info { "ZonedDateTime schema: $prop" }
      assertEquals("string", prop["type"]!!.jsonPrimitive.content)
      assertEquals("date-time", prop["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `OffsetDateTime should have format date-time`() {
      data class OffsetDateTimeHolder(val offsetDateTime: java.time.OffsetDateTime)
      val spec = OffsetDateTimeHolder::class.toJsonSchema("OffsetDateTimeHolder", null)
      val prop = spec.schema["properties"]!!.jsonObject["offsetDateTime"]!!.jsonObject
      logger.info { "OffsetDateTime schema: $prop" }
      assertEquals("string", prop["type"]!!.jsonPrimitive.content)
      assertEquals("date-time", prop["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Instant should have format date-time`() {
      data class InstantHolder(val instant: java.time.Instant)
      val spec = InstantHolder::class.toJsonSchema("InstantHolder", null)
      val prop = spec.schema["properties"]!!.jsonObject["instant"]!!.jsonObject
      logger.info { "Instant schema: $prop" }
      assertEquals("string", prop["type"]!!.jsonPrimitive.content)
      assertEquals("date-time", prop["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `java util Date should have format date-time`() {
      data class UtilDateHolder(val date: java.util.Date)
      val spec = UtilDateHolder::class.toJsonSchema("UtilDateHolder", null)
      val prop = spec.schema["properties"]!!.jsonObject["date"]!!.jsonObject
      logger.info { "java.util.Date schema: $prop" }
      assertEquals("string", prop["type"]!!.jsonPrimitive.content)
      assertEquals("date-time", prop["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `all date time types in one class`() {
      data class AllDateTimeTypes(
        val localDate: java.time.LocalDate,
        val localDateTime: java.time.LocalDateTime,
        val zonedDateTime: java.time.ZonedDateTime,
        val offsetDateTime: java.time.OffsetDateTime,
        val instant: java.time.Instant,
        val utilDate: java.util.Date
      )
      val spec = AllDateTimeTypes::class.toJsonSchema("AllDateTimeTypes", null)
      val props = spec.schema["properties"]!!.jsonObject
      logger.info { "All date/time types schema: ${spec.schema}" }

      // LocalDate -> format: date
      assertEquals("date", props["localDate"]!!.jsonObject["format"]!!.jsonPrimitive.content)

      // All others -> format: date-time
      listOf("localDateTime", "zonedDateTime", "offsetDateTime", "instant", "utilDate").forEach { name ->
        assertEquals("date-time", props[name]!!.jsonObject["format"]!!.jsonPrimitive.content,
          "$name should have format date-time")
      }
    }

    @Test
    fun `date in list should have format`() {
      data class DateListHolder(val dates: List<java.time.LocalDate>)
      val spec = DateListHolder::class.toJsonSchema("DateListHolder", null)
      val datesSchema = spec.schema["properties"]!!.jsonObject["dates"]!!.jsonObject
      logger.info { "List<LocalDate> schema: $datesSchema" }
      assertEquals("array", datesSchema["type"]!!.jsonPrimitive.content)
      val items = datesSchema["items"]!!.jsonObject
      assertEquals("string", items["type"]!!.jsonPrimitive.content)
      assertEquals("date", items["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `date in map value should have format`() {
      data class DateMapHolder(val dateMap: Map<String, java.time.LocalDateTime>)
      val spec = DateMapHolder::class.toJsonSchema("DateMapHolder", null)
      val mapSchema = spec.schema["properties"]!!.jsonObject["dateMap"]!!.jsonObject
      logger.info { "Map<String, LocalDateTime> schema: $mapSchema" }
      val additionalProps = mapSchema["additionalProperties"]!!.jsonObject
      assertEquals("string", additionalProps["type"]!!.jsonPrimitive.content)
      assertEquals("date-time", additionalProps["format"]!!.jsonPrimitive.content)
    }
  }

  @Nested
  inner class ValueClassTest {

    @Test
    fun `value class support`() {
      val spec = UserId::class.toJsonSchema("UserId", "User identifier")
      val schema = spec.schema
      logger.info { "schema: $schema" }
      assertEquals("string", schema["type"]!!.jsonPrimitive.content)
      assertEquals("User identifier", schema["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nested value class unwrap`() {
      val spec = UserEmail::class.toJsonSchema("UserEmail", "Nested email wrapper")
      val schema = spec.schema
      logger.info { "schema: $schema" }
      assertEquals("string", schema["type"]!!.jsonPrimitive.content)
      assertEquals("Nested email wrapper", schema["description"]!!.jsonPrimitive.content)
    }
  }

  @Nested
  inner class DataClassTest {

    @Test
    fun `simple data class`() {
      val spec = Foo::class.toJsonSchema("Foo", "A foo class")
      val schema = spec.schema
      logger.info { "schema: $schema" }
      assertEquals("object", schema["type"]!!.jsonPrimitive.content)
      val props = schema["properties"]!!.jsonObject
      assertTrue(props.containsKey("id"))
      assertTrue(props.containsKey("name"))
      assertEquals("integer", props["id"]!!.jsonObject["type"]!!.jsonPrimitive.content)
      assertEquals("string", props["name"]!!.jsonObject["type"]!!.jsonPrimitive.content)
      val req = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
      assertEquals(setOf("id", "name"), req)
    }

    @Test
    fun `nullable field not in required`() {
      data class OptHolder(val req: String, val opt: Int?)
      val spec = OptHolder::class.toJsonSchema("OptHolder", null)
      val schema = spec.schema
      val props = schema["properties"]!!.jsonObject
      assertTrue(props.containsKey("req"))
      assertTrue(props.containsKey("opt"))
      val reqFields = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
      assertEquals(setOf("req"), reqFields)
    }
  }

  @Nested
  inner class EnumTest {

    @Test
    fun `enum property`() {
      data class HasEnum(val status: MyEnum)
      val spec = HasEnum::class.toJsonSchema("HasEnum", null)
      val prop = spec.schema["properties"]!!.jsonObject["status"]!!.jsonObject
      assertEquals("string", prop["type"]!!.jsonPrimitive.content)
      assertEquals("Enum of MyEnum", prop["description"]!!.jsonPrimitive.content)
      val enums = prop["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
      assertEquals(setOf("A", "B"), enums)
      val req = spec.schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
      assertEquals(setOf("status"), req)
    }

    @Test
    fun `enum property with SerialName should use serialized names`() {
      data class HasStatus(val status: Status)
      val spec = HasStatus::class.toJsonSchema("HasStatus", null)
      val prop = spec.schema["properties"]!!.jsonObject["status"]!!.jsonObject
      logger.info { "Enum with @SerialName schema: $prop" }
      assertEquals("string", prop["type"]!!.jsonPrimitive.content)
      val enums = prop["enum"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
      // Should use @SerialName values, not enum constant names
      assertEquals(setOf("active", "inactive", "pending_review"), enums)
    }

    @Test
    fun `map with SerialName enum keys should use serialized names`() {
      data class MapWithSerialNameEnum(val statusMap: Map<Status, String>)
      val spec = MapWithSerialNameEnum::class.toJsonSchema("MapWithSerialNameEnum", null)
      val schema = spec.schema
      logger.info { "Map with @SerialName enum keys: $schema" }
      val statusMap = schema["properties"]!!.jsonObject["statusMap"]!!.jsonObject
      val props = statusMap["properties"]!!.jsonObject
      // Should use @SerialName values as keys
      assertTrue(props.containsKey("active"), "Should have 'active' key")
      assertTrue(props.containsKey("inactive"), "Should have 'inactive' key")
      assertTrue(props.containsKey("pending_review"), "Should have 'pending_review' key")
      assertFalse(props.containsKey("ACTIVE"), "Should NOT have 'ACTIVE' key")
      assertFalse(props.containsKey("INACTIVE"), "Should NOT have 'INACTIVE' key")
    }

    @Test
    fun `toEnumMapJsonSchema with SerialName enum should use serialized names`() {
      val spec = toEnumMapJsonSchema<Status, Int>("statusMap", "Status scores")
      val schema = spec.schema
      logger.info { "toEnumMapJsonSchema with @SerialName: $schema" }
      val props = schema["properties"]!!.jsonObject
      // Should use @SerialName values as keys
      assertTrue(props.containsKey("active"), "Should have 'active' key")
      assertTrue(props.containsKey("inactive"), "Should have 'inactive' key")
      assertTrue(props.containsKey("pending_review"), "Should have 'pending_review' key")
      assertFalse(props.containsKey("ACTIVE"), "Should NOT have 'ACTIVE' key")
      // Required should also use serialized names
      val req = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
      assertEquals(setOf("active", "inactive", "pending_review"), req)
    }

    @Test
    fun `toEnumMapJsonSchema with int values`() {
      val spec = toEnumMapJsonSchema<MyEnum, Int>("enumMap", "desc")
      assertEquals("enumMap", spec.name)
      assertEquals("desc", spec.description)
      val sch = spec.schema
      logger.info { "schema: $sch" }
      assertEquals("object", sch["type"]!!.jsonPrimitive.content)
      assertEquals("desc", sch["description"]!!.jsonPrimitive.content)
      val props = sch["properties"]!!.jsonObject
      assertTrue(props.containsKey("A"))
      assertTrue(props.containsKey("B"))
      assertEquals("integer", props["A"]!!.jsonObject["type"]!!.jsonPrimitive.content)
      val req = sch["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
      assertEquals(setOf("A", "B"), req)
      assertEquals(false, sch["additionalProperties"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `toEnumMapJsonSchema with list values`() {
      val spec = toEnumMapJsonSchema<MyEnum, List<Int>>("enumMap2", null)
      val props = spec.schema["properties"]!!.jsonObject
      assertEquals("array", props["A"]!!.jsonObject["type"]!!.jsonPrimitive.content)
      assertEquals("array", props["B"]!!.jsonObject["type"]!!.jsonPrimitive.content)
      val req = spec.schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
      assertEquals(setOf("A", "B"), req)
      assertFalse(spec.schema["additionalProperties"]!!.jsonPrimitive.boolean)
    }
  }

  @Nested
  inner class CollectionTest {

    @Test
    fun `list and array of primitives`() {
      data class ListPrimsHolder(val ints: List<Int>, val strings: Array<String>) {
        override fun equals(other: Any?): Boolean {
          if (this === other) return true
          if (other !is ListPrimsHolder) return false
          if (ints != other.ints) return false
          if (!strings.contentEquals(other.strings)) return false
          return true
        }

        override fun hashCode(): Int {
          var result = ints.hashCode()
          result = 31 * result + strings.contentHashCode()
          return result
        }
      }

      val spec = ListPrimsHolder::class.toJsonSchema("ListPrimsHolder", null)
      val props = spec.schema["properties"]!!.jsonObject
      val ints = props["ints"]!!.jsonObject
      assertEquals("array", ints["type"]!!.jsonPrimitive.content)
      assertEquals("integer", ints["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
      val strs = props["strings"]!!.jsonObject
      assertEquals("array", strs["type"]!!.jsonPrimitive.content)
      assertEquals("string", strs["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
  }

  @Nested
  inner class MapTest {

    @Test
    fun `map of primitives`() {
      data class MapPrims(val m: Map<String, Boolean>)
      val spec = MapPrims::class.toJsonSchema("MapPrims", null)
      val m = spec.schema["properties"]!!.jsonObject["m"]!!.jsonObject
      assertEquals("object", m["type"]!!.jsonPrimitive.content)
      val ap = m["additionalProperties"]!!.jsonObject
      assertEquals("boolean", ap["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `map of objects`() {
      data class MapObj(val m: Map<String, Foo>)
      val spec = MapObj::class.toJsonSchema("MapObj", null)
      val m = spec.schema["properties"]!!.jsonObject["m"]!!.jsonObject
      assertEquals("object", m["type"]!!.jsonPrimitive.content)
      val ap = m["additionalProperties"]!!.jsonObject
      assertEquals("object", ap["type"]!!.jsonPrimitive.content)
      val fprops = ap["properties"]!!.jsonObject
      assertTrue(fprops.containsKey("id"))
      assertTrue(fprops.containsKey("name"))
    }

    @Test
    fun `map with enum keys`() {
      data class MapEnumHolder(val mapEnum: Map<MyEnum, String>)
      val spec = MapEnumHolder::class.toJsonSchema("EnumHolder", null)
      val schema = spec.schema
      logger.info { "schema: $schema" }
      val me = schema["properties"]!!.jsonObject["mapEnum"]!!.jsonObject
      assertEquals("object", me["type"]!!.jsonPrimitive.content)
      assertTrue { me["description"]!!.jsonPrimitive.content.startsWith("Map with keys from MyEnum enum") }
      assertEquals(false, me["additionalProperties"]!!.jsonPrimitive.boolean)
      val ep = me["properties"]!!.jsonObject
      assertTrue(ep.containsKey("A"))
      assertTrue(ep.containsKey("B"))
      assertEquals("string", ep["A"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `map of list of objects`() {
      data class MapListFooHolder(val mapList: Map<String, List<Foo>>)
      val spec = MapListFooHolder::class.toJsonSchema("Holder", null)
      val schema = spec.schema
      val ml = schema["properties"]!!.jsonObject["mapList"]!!.jsonObject
      assertEquals("object", ml["type"]!!.jsonPrimitive.content)
      val add = ml["additionalProperties"]!!.jsonObject
      assertEquals("array", add["type"]!!.jsonPrimitive.content)
      val items = add["items"]!!.jsonObject
      assertEquals("object", items["type"]!!.jsonPrimitive.content)
      val fprops = items["properties"]!!.jsonObject
      assertTrue(fprops.containsKey("id"))
      assertTrue(fprops.containsKey("name"))
    }

    @Test
    fun `map with enum keys and list values`() {
      data class MapEnumList(val m: Map<MyEnum, List<Foo>>)
      val spec = MapEnumList::class.toJsonSchema("MapEnumList", null)
      val m = spec.schema["properties"]!!.jsonObject["m"]!!.jsonObject
      assertEquals("object", m["type"]!!.jsonPrimitive.content)
      assertTrue { m["description"]!!.jsonPrimitive.content.startsWith("Map with keys from MyEnum enum") }
      assertFalse(m["additionalProperties"]!!.jsonPrimitive.boolean)
      val props = m["properties"]!!.jsonObject
      props.keys.forEach { key -> assertTrue(key == "A" || key == "B") }
      val arr = props["A"]!!.jsonObject
      assertEquals("array", arr["type"]!!.jsonPrimitive.content)
      val items = arr["items"]!!.jsonObject
      assertEquals("object", items["type"]!!.jsonPrimitive.content)
      val fprops = items["properties"]!!.jsonObject
      assertTrue(fprops.containsKey("id"))
      assertTrue(fprops.containsKey("name"))
    }
  }

  @Nested
  inner class CircularReferenceTest {

    @Test
    fun `self referencing class should not stackoverflow`() {
      val spec = Node::class.toJsonSchema("Node", "A linked list node")
      val schema = spec.schema
      logger.info { "Self-referencing schema: $schema" }

      assertEquals("object", schema["type"]!!.jsonPrimitive.content)
      val props = schema["properties"]!!.jsonObject
      assertTrue(props.containsKey("value"))
      assertTrue(props.containsKey("next"))
      assertEquals("string", props["value"]!!.jsonObject["type"]!!.jsonPrimitive.content)

      // 循環參照：帶說明的裸 object，不再發懸空 $ref（schema 從不產出 definitions 區）
      val nextProp = props["next"]!!.jsonObject
      assertFalse(nextProp.containsKey("\$ref"), "不得發指向不存在 definitions 的 \$ref")
      assertEquals("object", nextProp["type"]!!.jsonPrimitive.content)
      assertTrue("Recursive Node" in nextProp["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `mutual reference should not stackoverflow`() {
      val spec = RefA::class.toJsonSchema("RefA", "Class A referencing B")
      val schema = spec.schema
      logger.info { "Mutual reference schema: $schema" }

      assertEquals("object", schema["type"]!!.jsonPrimitive.content)
      val props = schema["properties"]!!.jsonObject
      assertTrue(props.containsKey("name"))
      assertTrue(props.containsKey("refB"))

      // RefB property should be an object
      val refBProp = props["refB"]!!.jsonObject
      assertEquals("object", refBProp["type"]!!.jsonPrimitive.content)

      // RefB.refA 循環回 RefA：帶說明的裸 object，不再發懸空 $ref
      val refBProps = refBProp["properties"]!!.jsonObject
      assertTrue(refBProps.containsKey("refA"))
      val refAProp = refBProps["refA"]!!.jsonObject
      assertFalse(refAProp.containsKey("\$ref"), "不得發指向不存在 definitions 的 \$ref")
      assertEquals("object", refAProp["type"]!!.jsonPrimitive.content)
      assertTrue("Recursive RefA" in refAProp["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tree structure with self referencing list should not stackoverflow`() {
      val spec = TreeNode::class.toJsonSchema("TreeNode", "A tree node with children")
      val schema = spec.schema
      logger.info { "Tree structure schema: $schema" }

      assertEquals("object", schema["type"]!!.jsonPrimitive.content)
      val props = schema["properties"]!!.jsonObject
      assertTrue(props.containsKey("label"))
      assertTrue(props.containsKey("children"))

      // children should be an array
      val childrenProp = props["children"]!!.jsonObject
      assertEquals("array", childrenProp["type"]!!.jsonPrimitive.content)

      // 集合元素循環參照：同樣不發懸空 $ref
      val items = childrenProp["items"]!!.jsonObject
      assertFalse(items.containsKey("\$ref"), "不得發指向不存在 definitions 的 \$ref")
      assertEquals("object", items["type"]!!.jsonPrimitive.content)
      assertTrue("Recursive TreeNode" in items["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `diamond pattern same class in different branches should work`() {
      val spec = Diamond::class.toJsonSchema("Diamond", "Diamond pattern")
      val schema = spec.schema
      logger.info { "Diamond pattern schema: $schema" }

      assertEquals("object", schema["type"]!!.jsonPrimitive.content)
      val props = schema["properties"]!!.jsonObject
      assertTrue(props.containsKey("left"))
      assertTrue(props.containsKey("right"))

      // Both left and right should be fully expanded Shared objects (not $ref)
      val leftProp = props["left"]!!.jsonObject
      assertEquals("object", leftProp["type"]!!.jsonPrimitive.content)
      assertFalse(leftProp.containsKey("\$ref"), "left should be fully expanded, not \$ref")
      assertTrue(leftProp["properties"]!!.jsonObject.containsKey("data"))

      val rightProp = props["right"]!!.jsonObject
      assertEquals("object", rightProp["type"]!!.jsonPrimitive.content)
      assertFalse(rightProp.containsKey("\$ref"), "right should be fully expanded, not \$ref")
      assertTrue(rightProp["properties"]!!.jsonObject.containsKey("data"))
    }
  }

  // ========== 2026-08-26 的四項優化 ==========

  /** 欄位刻意反字母序宣告 —— 字母序會排成 alpha/mango/zebra，宣告序才是 zebra/alpha/mango */
  data class DeclOrder(val zebra: String, val alpha: Int, val mango: Boolean)

  /** 建構子參數 ＋ body 屬性：body 屬性（字母序）附在建構子參數（宣告序）之後 */
  @Suppress("unused")
  class WithBodyProp(val zulu: String, val echo: Int) {
    val bravo: String get() = "$zulu-$echo"
  }

  data class WithDefaults(
    val gate: Int,                       // 無預設、非 null → required
    val convenience: List<String> = emptyList(),  // 有預設 → not required
    val maybe: String? = null,           // nullable → not required（舊規則不變）
  )

  data class Described(
    @Description("windows where the configuration was in effect (one pass = one occasion)")
    val baseRateHits: Int,
    @Description("overrides the auto-generated enum description")
    val status: MyEnum,
    val plain: String,
  )

  @Description("a nested payload with its own class-level description")
  data class DescribedNested(val data: String)

  data class HasNested(
    val nested: DescribedNested,
    @Description("property-level wins over class-level")
    val overridden: DescribedNested,
  )

  @Nested
  inner class DeclarationOrderTest {

    @Test
    fun `properties follow constructor declaration order not alphabetical`() {
      val schema = DeclOrder::class.toJsonSchema("DeclOrder").schema
      logger.info { "schema: $schema" }
      assertEquals(listOf("zebra", "alpha", "mango"), schema["properties"]!!.jsonObject.keys.toList(),
        "欄位順序必須是宣告序 —— autoregressive 模型依 schema 順序生成欄位")
      assertEquals(listOf("zebra", "alpha", "mango"),
        schema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        "required 也依宣告序")
    }

    @Test
    fun `body properties come after constructor parameters`() {
      val schema = WithBodyProp::class.toJsonSchema("WithBodyProp").schema
      logger.info { "schema: $schema" }
      assertEquals(listOf("zulu", "echo", "bravo"), schema["properties"]!!.jsonObject.keys.toList(),
        "建構子參數（宣告序）在前，body 屬性附於其後")
    }
  }

  @Nested
  inner class RequiredByOptionalityTest {

    @Test
    fun `params with default values are not required`() {
      val schema = WithDefaults::class.toJsonSchema("WithDefaults").schema
      logger.info { "schema: $schema" }
      val required = schema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
      assertEquals(listOf("gate"), required,
        "有預設值的欄位不必逼 LLM 填（漏填時 kotlinx 反序列化自動補預設）；nullable 維持舊規則")
      // 欄位本身都還在 properties 裡
      assertEquals(setOf("gate", "convenience", "maybe"), schema["properties"]!!.jsonObject.keys)
    }
  }

  @Nested
  inner class DescriptionAnnotationTest {

    @Test
    fun `property level Description lands in schema`() {
      val schema = Described::class.toJsonSchema("Described").schema
      logger.info { "schema: $schema" }
      val props = schema["properties"]!!.jsonObject
      assertEquals("windows where the configuration was in effect (one pass = one occasion)",
        props["baseRateHits"]!!.jsonObject["description"]!!.jsonPrimitive.content)
      assertFalse(props["plain"]!!.jsonObject.containsKey("description"),
        "沒掛 annotation 的欄位不生 description")
    }

    @Test
    fun `property Description overrides enum auto description but keeps enum values`() {
      val schema = Described::class.toJsonSchema("Described").schema
      val statusProp = schema["properties"]!!.jsonObject["status"]!!.jsonObject
      assertEquals("overrides the auto-generated enum description",
        statusProp["description"]!!.jsonPrimitive.content)
      assertEquals(listOf("A", "B"), statusProp["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        "值域約束必須保留 —— @Description 只換說明文字")
    }

    @Test
    fun `class level Description describes nested objects and property level wins`() {
      val schema = HasNested::class.toJsonSchema("HasNested").schema
      logger.info { "schema: $schema" }
      val props = schema["properties"]!!.jsonObject
      assertEquals("a nested payload with its own class-level description",
        props["nested"]!!.jsonObject["description"]!!.jsonPrimitive.content)
      assertEquals("property-level wins over class-level",
        props["overridden"]!!.jsonObject["description"]!!.jsonPrimitive.content,
        "屬性層級的 @Description 蓋過 class 層級（就近者勝）")
    }

    @Test
    fun `toJsonSchema falls back to class level Description when description param is null`() {
      val schema = DescribedNested::class.toJsonSchema("DescribedNested").schema
      assertEquals("a nested payload with its own class-level description",
        schema["description"]!!.jsonPrimitive.content)
      // 參數優先
      val explicit = DescribedNested::class.toJsonSchema("DescribedNested", "explicit wins").schema
      assertEquals("explicit wins", explicit["description"]!!.jsonPrimitive.content)
    }
  }

  /**
   * [Size] —— 集合長度限制進 schema。
   *
   * 存在的理由是一次實測：契約的 KDoc 寫著「2~4 條」，而既有產出的 199 個窗裡
   * 140 個（70%）超過 4 條。散文限制與沒有限制的差別，只在人以為有。
   */
  @kotlinx.serialization.Serializable
  data class Sized(
    @Size(2, 4) val watchFor: List<String>,
    @Size(min = 3) val atLeastThree: List<String>,
    @Size(max = 5) val atMostFive: List<String>,
    val unbounded: List<String>,
    /** 掛在非集合欄位上必須被靜默忽略 —— 不能讓 schema 長出無意義的 minItems */
    @Size(1, 2) val notACollection: String,
  )

  @Nested
  inner class SizeTest {

    private val props = Sized::class.toJsonSchema("Sized").schema["properties"]!!.jsonObject

    @Test
    fun `min 與 max 都出現在 schema 裡`() {
      val w = props["watchFor"]!!.jsonObject
      assertEquals(2, w["minItems"]!!.jsonPrimitive.content.toInt())
      assertEquals(4, w["maxItems"]!!.jsonPrimitive.content.toInt())
      assertEquals("array", w["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `只給一邊時只出現那一邊`() {
      val lo = props["atLeastThree"]!!.jsonObject
      assertEquals(3, lo["minItems"]!!.jsonPrimitive.content.toInt())
      assertFalse(lo.containsKey("maxItems"))
      val hi = props["atMostFive"]!!.jsonObject
      assertEquals(5, hi["maxItems"]!!.jsonPrimitive.content.toInt())
      assertFalse(hi.containsKey("minItems"))
    }

    @Test
    fun `沒掛 annotation 的集合不受影響`() {
      val u = props["unbounded"]!!.jsonObject
      assertFalse(u.containsKey("minItems"))
      assertFalse(u.containsKey("maxItems"))
    }

    /** 掛錯地方要靜默忽略：長出一個 minItems 的字串欄位，嚴格驗證器會炸 */
    @Test
    fun `掛在非集合欄位上被忽略`() {
      val n = props["notACollection"]!!.jsonObject
      assertEquals("string", n["type"]!!.jsonPrimitive.content)
      assertFalse(n.containsKey("minItems"))
      assertFalse(n.containsKey("maxItems"))
    }
  }
}
