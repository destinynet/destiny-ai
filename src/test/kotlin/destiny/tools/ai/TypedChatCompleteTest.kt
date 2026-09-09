/**
 * Created by smallufo on 2026-09-09.
 */
package destiny.tools.ai

import destiny.tools.ai.model.FormatSpec
import destiny.tools.ai.model.narrowEnumKeys
import destiny.tools.ai.model.validated
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * `typedChatComplete` 是所有 typed 回覆的唯一出口：postProcessors → 切 JSON → decode → 驗證。
 * 先前 JSON 抽取是 per-domain 選配（四個 domain 有、其餘沒有），`init { require }` 的
 * IllegalArgumentException 會穿過 catch，空字串會進 decoder 得到一句看不懂的錯，
 * 而「解得開但內容缺一半」沒有任何一層看得見。
 */
class TypedChatCompleteTest {

  @Serializable
  enum class Topic { A, B, C }

  @Serializable
  data class Report(val topics: Map<Topic, String>, val summary: String)

  @Serializable
  data class Bounded(val items: List<String>) {
    init {
      require(items.size <= 2) { "at most 2 items, got ${items.size}" }
    }
  }

  /** doChatComplete 回一段固定字串的假 impl —— 只測 typed 那一段 */
  private class CannedChat(private val canned: String) : AbstractChatCompletion() {
    override val provider = Provider.OPENAI
    override suspend fun doChatComplete(
      model: String, messages: List<Msg>, user: String?, funCalls: Set<IFunctionDeclaration>,
      timeout: Duration, chatOptions: ChatOptions, jsonSchema: JsonSchemaSpec?, maxFunctionCallDepth: Int
    ): Reply<String> = Reply.Normal(canned, null, provider, model)
  }

  private val reportSpec = FormatSpec.of<Report>("report", "r")
  private val msgs = listOf(Msg(Role.USER, "hi"))

  private suspend fun <T : Any> run(canned: String, spec: FormatSpec<T>, postProcessors: List<IPostProcessor> = emptyList()): Reply<T>? =
    CannedChat(canned).typedChatComplete("m", msgs, spec, LlmJson.lenient, Locale.TAIWAN, ChatOptions(), postProcessors)

  @Test
  fun `fenced JSON with prose around it is decoded without any post-processor`() = runTest {
    val reply = run("Sure:\n```json\n{\"topics\":{\"A\":\"a\"},\"summary\":\"s\"}\n```\nDone.", reportSpec)
    assertIs<Reply.Normal<Report>>(reply)
    assertEquals("s", reply.content.summary)
  }

  @Test
  fun `String output is handed back untouched, no extraction`() = runTest {
    val prose = "Note [see below]: {\"a\":1} and more prose"
    val reply = run(prose, FormatSpec.of<String>("text", "t"))
    assertIs<Reply.Normal<String>>(reply)
    assertEquals(prose, reply.content)
  }

  @Test
  fun `post-processors run before extraction`() = runTest {
    val upper = object : IPostProcessor {
      override fun process(raw: String, locale: Locale?) = raw.replace("PLACEHOLDER", "s") to true
    }
    val reply = run("```json\n{\"topics\":{},\"summary\":\"PLACEHOLDER\"}\n```", reportSpec, listOf(upper))
    assertIs<Reply.Normal<Report>>(reply)
    assertEquals("s", reply.content.summary)
  }

  @Test
  fun `empty content is a DeserializationFailure that says so`() = runTest {
    val reply = run("   ", reportSpec)
    assertIs<Reply.Error.DeserializationFailure>(reply)
    assertTrue(reply.errorMessage.contains("empty"), reply.errorMessage)
  }

  @Test
  fun `init require in the DTO is classified as DeserializationFailure, not thrown`() = runTest {
    val reply = run("""{"items":["a","b","c"]}""", FormatSpec.of<Bounded>("bounded", "b"))
    assertIs<Reply.Error.DeserializationFailure>(reply)
    assertTrue(reply.errorMessage.contains("at most 2"), reply.errorMessage)
  }

  @Test
  fun `validator on the spec turns a half-empty reply into a DeserializationFailure`() = runTest {
    val strict = reportSpec.validated { r -> if (r.topics.size < 3) "expected 3 topics, got ${r.topics.size}" else null }
    val reply = run("""{"topics":{"A":"a"},"summary":"s"}""", strict)
    assertIs<Reply.Error.DeserializationFailure>(reply)
    assertTrue(reply.errorMessage.contains("expected 3 topics"), reply.errorMessage)
    // originalContent 是切出來的 JSON，方便事後看
    assertEquals("""{"topics":{"A":"a"},"summary":"s"}""", reply.originalContent)
  }

  @Test
  fun `validator passes through when content is complete`() = runTest {
    val strict = reportSpec.validated { r -> if (r.topics.size < 1) "empty" else null }
    val reply = run("""{"topics":{"A":"a"},"summary":"s"}""", strict)
    assertIs<Reply.Normal<Report>>(reply)
  }

  @Test
  fun `validator survives narrowEnumKeys`() = runTest {
    val spec = reportSpec.validated { "always fails" }.narrowEnumKeys("topics", listOf(Topic.A))
    val reply = run("""{"topics":{"A":"a"},"summary":"s"}""", spec)
    assertIs<Reply.Error.DeserializationFailure>(reply)
  }
}
