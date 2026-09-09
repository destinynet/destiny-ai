/**
 * Created by smallufo on 2026-09-09.
 */
package destiny.tools.ai

import destiny.tools.ai.model.FormatSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * orchestrator 的邊界先前把錯誤分類全部抹成 `null`：全部 DeserializationFailure（schema / serializer
 * 的 bug，重試沒用）、全部 RateLimited（稍後再試）、MaxTokensReached（換 model）到了呼叫端長得一模一樣，
 * 只能 `it!!` 或 `?: throw IllegalStateException("returned null")`。
 * [Orchestration.Exhausted] 帶著每一次嘗試的分類結果，呼叫端才分得出來。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrchestrationTest {

  private val pmA = ProviderModel(Provider.OPENAI, "model-a")
  private val pmB = ProviderModel(Provider.GEMINI, "model-b")
  private fun normal(content: String, pm: ProviderModel) = Reply.Normal(content, null, pm.provider, pm.model)
  private fun deserFail(pm: ProviderModel) = Reply.Error.DeserializationFailure("bad", "{}", pm.provider, pm.model)

  // ─── ResilientOrchestrator ───

  @Test
  fun `success is wrapped, and successOrNull unwraps it`() = runTest {
    val result = ResilientOrchestrator(setOf(pmA)).executeExplained { pm -> normal("ok", pm) }
    assertIs<Orchestration.Success<String>>(result)
    assertEquals("ok", result.reply.content)
    assertEquals("ok", result.successOrNull()?.content)
  }

  @Test
  fun `exhausted keeps one attempt per call, with loop number and classified error`() = runTest {
    val result = ResilientOrchestrator(setOf(pmA, pmB), delayBetweenModelLoops = 1.seconds, maxTotalAttempts = 2)
      .executeExplained<String> { pm -> deserFail(pm) }
    assertIs<Orchestration.Exhausted>(result)
    assertEquals(4, result.attempts.size)
    assertEquals(setOf(1, 2), result.attempts.map { it.loop }.toSet())
    assertTrue(result.attempts.all { it.error is Reply.Error.DeserializationFailure })
    assertTrue(result.allDeserializationFailures, "全部都是 schema/serializer 分歧 → 重試無用，該回報給人")
    assertFalse(result.allRetryable)
    assertEquals(null, result.successOrNull())
  }

  @Test
  fun `retryable-only exhaustion is distinguishable from deterministic failure`() = runTest {
    val result = ResilientOrchestrator(setOf(pmA), delayBetweenModelLoops = 1.seconds, maxTotalAttempts = 2)
      .executeExplained<String> { pm -> Reply.Error.Busy(pm.provider) }
    assertIs<Orchestration.Exhausted>(result)
    assertTrue(result.allRetryable)
    assertFalse(result.allDeserializationFailures)
  }

  @Test
  fun `a thrown exception is recorded on the attempt`() = runTest {
    val result = ResilientOrchestrator(setOf(pmA), maxTotalAttempts = 1)
      .executeExplained<String> { throw IllegalStateException("network broke") }
    assertIs<Orchestration.Exhausted>(result)
    val attempt = result.attempts.single()
    assertEquals(pmA, attempt.providerModel)
    assertEquals("network broke", attempt.thrown?.message)
    assertEquals(null, attempt.error)
  }

  @Test
  fun `invalid api key stops the provider and the exhaustion says so`() = runTest {
    val result = ResilientOrchestrator(setOf(pmA), maxTotalAttempts = 3)
      .executeExplained<String> { pm -> Reply.Error.InvalidApiKey(pm.provider) }
    assertIs<Orchestration.Exhausted>(result)
    assertEquals(1, result.attempts.size)
    assertNotNull(result.note)
  }

  @Test
  fun `no provider models is an exhaustion with a note, not a crash`() = runTest {
    val result = ResilientOrchestrator(emptySet()).executeExplained<String> { pm -> normal("ok", pm) }
    assertIs<Orchestration.Exhausted>(result)
    assertTrue(result.attempts.isEmpty())
    assertNotNull(result.note)
  }

  @Test
  fun `describe names every provider and its error`() = runTest {
    val result = ResilientOrchestrator(setOf(pmA), maxTotalAttempts = 1)
      .executeExplained<String> { pm -> Reply.Error.MaxTokensReached(pm.provider, pm.model) }
    assertIs<Orchestration.Exhausted>(result)
    val text = result.describe()
    assertTrue(text.contains("OPENAI/model-a"), text)
    assertTrue(text.contains("MaxTokensReached"), text)
  }

  // ─── HedgeOrchestrator ───

  private val preferred = ProviderModel(Provider.CLAUDE, "claude-x")

  @Test
  fun `hedge exhaustion lists the preferred timeout and every fallback failure`() = runTest {
    val hedge = HedgeOrchestrator(preferred, setOf(pmA), preferredWait = 1.seconds, context = EmptyCoroutineContext)
    val result = hedge.executeExplained<String> { pm ->
      when (pm) {
        preferred -> { delay(5.seconds); normal("late", pm) }
        else      -> deserFail(pm)
      }
    }
    assertIs<Orchestration.Exhausted>(result)
    assertEquals(setOf(preferred, pmA), result.attempts.map { it.providerModel }.toSet())
    assertTrue(result.attempts.first { it.providerModel == pmA }.error is Reply.Error.DeserializationFailure)
    assertNotNull(result.attempts.first { it.providerModel == preferred }.note, "preferred 是超時，不是 error")
  }

  @Test
  fun `hedge success is wrapped`() = runTest {
    val hedge = HedgeOrchestrator(preferred, setOf(pmA), preferredWait = 1.seconds, context = EmptyCoroutineContext)
    val result = hedge.executeExplained { pm -> delay(10.milliseconds); normal("fast", pm) }
    assertIs<Orchestration.Success<String>>(result)
  }

  // ─── ResilientChatService：chatCompleteOrExplain ───

  private class FakeChat(override val provider: Provider, val handler: (String) -> Reply<String>) : IChatCompletion {
    override suspend fun chatComplete(
      model: String, messages: List<Msg>, user: String?, funCalls: Set<IFunctionDeclaration>,
      timeout: Duration, chatOptions: ChatOptions, jsonSchema: JsonSchemaSpec?, maxFunctionCallDepth: Int
    ): Reply<String> = handler(model)

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> typedChatComplete(
      model: String, messages: List<Msg>, formatSpec: FormatSpec<T>, json: Json, locale: Locale,
      chatOptions: ChatOptions, postProcessors: List<IPostProcessor>, user: String?,
      funCalls: Set<IFunctionDeclaration>, timeout: Duration, maxFunctionCallDepth: Int
    ): Reply<T>? = handler(model) as Reply<T>?
  }

  @Test
  fun `chatCompleteOrExplain surfaces the deserialization failures the old API hid behind null`() = runTest {
    val spec = FormatSpec.of<String>("str", "s")
    val service = ResilientChatService(ResilientConfig(setOf(pmA), 30.seconds, 1.seconds, maxTotalAttempts = 2))
    val result = service.chatCompleteOrExplain(spec, listOf(Msg(Role.USER, "hi")), emptyList()) { provider ->
      FakeChat(provider) { model -> deserFail(ProviderModel(provider, model)) }
    }
    assertIs<Orchestration.Exhausted>(result)
    assertTrue(result.allDeserializationFailures)
    assertEquals(2, result.attempts.size)
    // 舊 API 行為不變：仍是 null
    assertEquals(null, service.chatComplete(spec, listOf(Msg(Role.USER, "hi")), emptyList()) { provider ->
      FakeChat(provider) { model -> deserFail(ProviderModel(provider, model)) }
    })
  }
}
