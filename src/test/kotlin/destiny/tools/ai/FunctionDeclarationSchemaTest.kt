/**
 * Created by Claude on 2026-08-27.
 */
package destiny.tools.ai

import com.jayway.jsonpath.JsonPath
import destiny.tools.ai.llm.toClaude
import destiny.tools.ai.llm.toCohere
import destiny.tools.ai.llm.toDeepseek
import destiny.tools.ai.llm.toGemini
import destiny.tools.ai.llm.toMistral
import destiny.tools.ai.llm.toOpenAi
import destiny.tools.ai.llm.toXai
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `@Parameter` 的約束欄位（`enum` / `minimum` / `maximum`）送不送得到 provider。
 *
 * ⚠️ **這條鏈在 2026-08-27 之前整段是斷的。**
 * `IFunctionDeclaration.Parameter` 只有 `name/type/description/required` 四個欄位，
 * 於是 reflection 讀出 annotation 後就把約束丟掉，五個 converter 一律送出 null。
 * 下游既有的 enum 宣告看似有效，純粹是因為同一份清單在 `description` 裡又寫了一遍。
 *
 * ⚠️ **既有的 `OpenAiFunTest` 擋不住這個 bug** —— 它手工建構
 * `InputSchema.Property(enum = ...)` 再序列化，證明的是「序列化器吐得出 enum」，
 * 而不是「converter 會把 enum 填進去」。本測試一律從 [IFunctionDeclaration] 出發，
 * 走完 annotation → reflection → converter → JSON 整條鏈。
 */
class FunctionDeclarationSchemaTest {

  private val json = Json {
    encodeDefaults = true
    prettyPrint = true
    explicitNulls = false
    ignoreUnknownKeys = true
  }

  @FunctionDeclaration(name = "sample_tool", description = "A tool for testing", keywords = ["test"])
  class SampleCall : AnnotatedFunctionDeclaration() {
    override val callbackName: String = ::run.name
    override fun applied(msgs: List<Msg>): Boolean = true

    @Suppress("UNUSED_PARAMETER")
    fun run(
      @Parameter("Which side", enum = ["HIGH", "HARD", "SOFT"])
      group: String,
      @Parameter("Free text, no closed vocabulary", required = false)
      note: String,
      @Parameter("Window width in months", required = false, minimum = 1, maximum = 12)
      windowMonths: Int,
      @Parameter("Only a lower bound", required = false, minimum = 0)
      offset: Int,
      // 界限掛在字串上是宣告端的錯 —— 必須被丟棄，不可汙染送出的 schema
      @Parameter("Bounds on a string are meaningless", required = false, minimum = 1, maximum = 5)
      label: String,
    ): String = "ok"
  }

  private val decl: IFunctionDeclaration = SampleCall()

  private fun paramOf(name: String) = decl.parameters.single { it.name == name }

  // ── 第一段：reflection 讀不讀得到 ─────────────────────────────────────

  @Test
  fun `annotation enum reaches Parameter`() {
    assertEquals(listOf("HIGH", "HARD", "SOFT"), paramOf("group").enum)
  }

  @Test
  fun `absent enum is an empty list, not null`() {
    assertEquals(emptyList(), paramOf("note").enum)
  }

  @Test
  fun `annotation bounds reach Parameter`() {
    assertEquals(1, paramOf("windowMonths").minimum)
    assertEquals(12, paramOf("windowMonths").maximum)
  }

  @Test
  fun `sentinel bounds are normalised to null`() {
    // Kotlin annotation 的參數不能有 null 預設值，故以 Int.MIN_VALUE/MAX_VALUE 當 sentinel
    assertEquals(0, paramOf("offset").minimum)
    assertNull(paramOf("offset").maximum)
    assertNull(paramOf("note").minimum)
    assertNull(paramOf("note").maximum)
  }

  @Test
  fun `required flag still works alongside constraints`() {
    assertTrue(paramOf("group").required)
    assertTrue(!paramOf("note").required)
    assertTrue(!paramOf("windowMonths").required)
  }

  @Test
  fun `Int maps to the integer json type`() {
    assertEquals("integer", paramOf("windowMonths").type)
    assertEquals("string", paramOf("label").type)
  }

  // ── 第二段：五個 converter 填不填 ────────────────────────────────────
  //
  // 沒有 enum 的參數必須是 null 而非 emptyList —— JSON Schema 的空 enum 代表
  // 「沒有任何合法值」，語意與「不限制」相反。

  @Test
  fun `toClaude carries constraints`() {
    val props = decl.toClaude().inputSchema.properties
    assertEquals(listOf("HIGH", "HARD", "SOFT"), props.getValue("group").enum)
    assertNull(props.getValue("note").enum)
    assertEquals(1, props.getValue("windowMonths").minimum)
    assertEquals(12, props.getValue("windowMonths").maximum)
  }

  @Test
  fun `toOpenAi carries constraints`() {
    val props = decl.toOpenAi().function.parameters.properties
    assertEquals(listOf("HIGH", "HARD", "SOFT"), props.getValue("group").enum)
    assertNull(props.getValue("note").enum)
    assertEquals(1, props.getValue("windowMonths").minimum)
    assertEquals(12, props.getValue("windowMonths").maximum)
  }

  @Test
  fun `toXai carries constraints`() {
    val props = decl.toXai().function.parameters.properties
    assertEquals(listOf("HIGH", "HARD", "SOFT"), props.getValue("group").enum)
    assertEquals(1, props.getValue("windowMonths").minimum)
  }

  @Test
  fun `toCohere carries constraints`() {
    val props = decl.toCohere().function.parameters.properties
    assertEquals(listOf("HIGH", "HARD", "SOFT"), props.getValue("group").enum)
    assertEquals(1, props.getValue("windowMonths").minimum)
  }

  @Test
  fun `toGemini carries constraints`() {
    val props = decl.toGemini().parameters.properties
    assertEquals(listOf("HIGH", "HARD", "SOFT"), props.getValue("group").enum)
    assertNull(props.getValue("note").enum)
    assertEquals(1, props.getValue("windowMonths").minimum)
    assertEquals(12, props.getValue("windowMonths").maximum)
  }

  @Test
  fun `bounds on a non-numeric parameter are dropped`() {
    // Parameter 層照實保留（那是宣告的事實），schema 層才丟棄
    assertEquals(1, paramOf("label").minimum)

    assertNull(decl.toClaude().inputSchema.properties.getValue("label").minimum)
    assertNull(decl.toClaude().inputSchema.properties.getValue("label").maximum)
    assertNull(decl.toGemini().parameters.properties.getValue("label").minimum)
    assertNull(decl.toGemini().parameters.properties.getValue("label").maximum)
  }

  @Test
  fun `toDeepseek and toMistral delegate to toOpenAi`() {
    // 兩者都是 `= this.toOpenAi()`，此處只確認委派沒有被改成另一份複製品
    assertEquals(decl.toOpenAi(), decl.toDeepseek())
    assertEquals(decl.toOpenAi(), decl.toMistral())
  }

  // ── 第三段：真的送得上線嗎 ──────────────────────────────────────────
  //
  // 前兩段驗的是物件，這一段驗的是實際打進 HTTP body 的位元組。
  // `explicitNulls = false` 讓未設定的欄位整個消失 —— 那正是我們要的。

  @Test
  fun `claude wire format`() {
    val ctx = JsonPath.parse(json.encodeToString(decl.toClaude()))
    assertEquals(listOf("HIGH", "HARD", "SOFT"), ctx.read("$.input_schema.properties.group.enum", List::class.java))
    assertEquals(1, ctx.read("$.input_schema.properties.windowMonths.minimum", Int::class.java))
    assertEquals(12, ctx.read("$.input_schema.properties.windowMonths.maximum", Int::class.java))
    assertEquals(listOf("group"), ctx.read("$.input_schema.required", List::class.java))
    assertEquals(setOf("type", "description"), ctx.read<Map<String, Any>>("$.input_schema.properties.note").keys)
    assertEquals(setOf("type", "description"), ctx.read<Map<String, Any>>("$.input_schema.properties.label").keys)
  }

  @Test
  fun `openai wire format`() {
    val ctx = JsonPath.parse(json.encodeToString(decl.toOpenAi()))
    val base = "$.function.parameters.properties"
    assertEquals(listOf("HIGH", "HARD", "SOFT"), ctx.read("$base.group.enum", List::class.java))
    assertEquals(1, ctx.read("$base.windowMonths.minimum", Int::class.java))
    assertEquals(setOf("type", "description"), ctx.read<Map<String, Any>>("$base.note").keys)
  }

  @Test
  fun `gemini wire format`() {
    val ctx = JsonPath.parse(json.encodeToString(decl.toGemini()))
    val base = "$.parameters.properties"
    assertEquals(listOf("HIGH", "HARD", "SOFT"), ctx.read("$base.group.enum", List::class.java))
    assertEquals(1, ctx.read("$base.windowMonths.minimum", Int::class.java))
    assertEquals(12, ctx.read("$base.windowMonths.maximum", Int::class.java))
    assertEquals(setOf("type", "description"), ctx.read<Map<String, Any>>("$base.note").keys)
  }
}
