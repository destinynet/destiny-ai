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
 * `@Parameter(enum = [...])` 的傳遞鏈守衛。
 *
 * ⚠️ **這條鏈在 2026-08-27 之前整段是斷的。**
 * `IFunctionDeclaration.Parameter` 沒有 enum 欄位 → reflection 讀出來就丟掉 →
 * 五個 converter 一律送出 `enum = null`。下游既有的 enum 宣告看似有效，
 * 純粹是因為同一份清單在 `description` 裡又寫了一遍。
 *
 * ⚠️ **既有的 `OpenAiFunTest` 擋不住這個 bug** —— 它手工建構 `InputSchema.Property(enum = ...)`
 * 再序列化，證明的是「序列化器吐得出 enum」，而不是「converter 會把 enum 填進去」。
 * 本測試一律從 [IFunctionDeclaration] 出發，走完整條鏈。
 */
class FunctionDeclarationEnumTest {

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
  fun `required flag still works alongside enum`() {
    assertTrue(paramOf("group").required)
    assertTrue(!paramOf("note").required)
  }

  // ── 第二段：五個 converter 填不填 ────────────────────────────────────
  //
  // 沒有 enum 的參數必須是 null 而非 emptyList —— JSON Schema 的空 enum 代表
  // 「沒有任何合法值」，語意與「不限制」相反。

  @Test
  fun `toClaude carries enum`() {
    val props = decl.toClaude().inputSchema.properties
    assertEquals(listOf("HIGH", "HARD", "SOFT"), props.getValue("group").enum)
    assertNull(props.getValue("note").enum)
  }

  @Test
  fun `toOpenAi carries enum`() {
    val props = decl.toOpenAi().function.parameters.properties
    assertEquals(listOf("HIGH", "HARD", "SOFT"), props.getValue("group").enum)
    assertNull(props.getValue("note").enum)
  }

  @Test
  fun `toXai carries enum`() {
    val props = decl.toXai().function.parameters.properties
    assertEquals(listOf("HIGH", "HARD", "SOFT"), props.getValue("group").enum)
    assertNull(props.getValue("note").enum)
  }

  @Test
  fun `toCohere carries enum`() {
    val props = decl.toCohere().function.parameters.properties
    assertEquals(listOf("HIGH", "HARD", "SOFT"), props.getValue("group").enum)
    assertNull(props.getValue("note").enum)
  }

  @Test
  fun `toGemini carries enum`() {
    val props = decl.toGemini().parameters.properties
    assertEquals(listOf("HIGH", "HARD", "SOFT"), props.getValue("group").enum)
    assertNull(props.getValue("note").enum)
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
  // `explicitNulls = false` 讓 null 的 enum 整個欄位消失 —— 那正是我們要的。

  @Test
  fun `claude wire format`() {
    val ctx = JsonPath.parse(json.encodeToString(decl.toClaude()))
    assertEquals(listOf("HIGH", "HARD", "SOFT"), ctx.read("$.input_schema.properties.group.enum", List::class.java))
    assertEquals(listOf("group"), ctx.read("$.input_schema.required", List::class.java))
    assertTrue(ctx.read<Map<String, Any>>("$.input_schema.properties.note").keys.none { it == "enum" })
  }

  @Test
  fun `openai wire format`() {
    val ctx = JsonPath.parse(json.encodeToString(decl.toOpenAi()))
    assertEquals(listOf("HIGH", "HARD", "SOFT"), ctx.read("$.function.parameters.properties.group.enum", List::class.java))
    assertTrue(ctx.read<Map<String, Any>>("$.function.parameters.properties.note").keys.none { it == "enum" })
  }

  @Test
  fun `gemini wire format`() {
    val ctx = JsonPath.parse(json.encodeToString(decl.toGemini()))
    assertEquals(listOf("HIGH", "HARD", "SOFT"), ctx.read("$.parameters.properties.group.enum", List::class.java))
    assertTrue(ctx.read<Map<String, Any>>("$.parameters.properties.note").keys.none { it == "enum" })
  }
}
