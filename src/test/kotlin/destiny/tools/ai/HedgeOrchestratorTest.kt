package destiny.tools.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HedgeOrchestratorTest {

  private val preferred = ProviderModel(Provider.CLAUDE, "claude-x")
  private val fb1 = ProviderModel(Provider.OPENAI, "gpt-x")
  private val fb2 = ProviderModel(Provider.GEMINI, "gemini-x")

  private fun normal(content: String, pm: ProviderModel) =
    Reply.Normal(content, null, pm.provider, pm.model)

  private fun orchestrator(vararg fallbacks: ProviderModel) =
    HedgeOrchestrator(preferred, fallbacks.toSet(), preferredWait = 1.seconds, context = EmptyCoroutineContext)

  @Test
  fun `preferred succeeding within wait wins even if a fallback is faster`() = runTest {
    val result = orchestrator(fb1).execute { pm ->
      when (pm) {
        preferred -> { delay(100.milliseconds); normal("preferred", pm) }
        else      -> { delay(50.milliseconds); normal("fallback", pm) }
      }
    }
    assertEquals("preferred", result?.content)
  }

  @Test
  fun `slow fallback is cancelled once preferred wins`() = runTest {
    val result = orchestrator(fb1).execute { pm ->
      when (pm) {
        preferred -> { delay(100.milliseconds); normal("preferred", pm) }
        else      -> { delay(10.seconds); normal("fallback", pm) }
      }
    }
    assertEquals("preferred", result?.content)
    // fallback 被取消，不必等它跑完 10 秒
    assertEquals(100, currentTime)
  }

  @Test
  fun `preferred timeout falls back to successful fallback`() = runTest {
    val result = orchestrator(fb1).execute { pm ->
      when (pm) {
        preferred -> { delay(5.seconds); normal("preferred", pm) }
        else      -> { delay(2.seconds); normal("fallback", pm) }
      }
    }
    assertEquals("fallback", result?.content)
    assertEquals(2000, currentTime)
  }

  @Test
  fun `preferred error falls back without waiting full preferredWait`() = runTest {
    val result = orchestrator(fb1).execute { pm ->
      when (pm) {
        preferred -> Reply.Error.Busy(pm.provider)
        else      -> { delay(500.milliseconds); normal("fallback", pm) }
      }
    }
    assertEquals("fallback", result?.content)
    assertEquals(500, currentTime)
  }

  @Test
  fun `faster failing fallback does not mask slower successful fallback`() = runTest {
    val result = orchestrator(fb1, fb2).execute { pm ->
      when (pm) {
        preferred -> Reply.Error.Busy(pm.provider)
        fb1       -> { delay(100.milliseconds); Reply.Error.Busy(pm.provider) }
        else      -> { delay(300.milliseconds); normal("slow-win", pm) }
      }
    }
    assertEquals("slow-win", result?.content)
  }

  @Test
  fun `exception in one fallback does not kill the race`() = runTest {
    val result = orchestrator(fb1, fb2).execute { pm ->
      when (pm) {
        preferred -> Reply.Error.Busy(pm.provider)
        fb1       -> throw RuntimeException("network broke")
        else      -> { delay(100.milliseconds); normal("survivor", pm) }
      }
    }
    assertEquals("survivor", result?.content)
  }

  @Test
  fun `returns null when preferred and all fallbacks fail`() = runTest {
    val result = orchestrator(fb1, fb2).execute { pm ->
      Reply.Error.Busy(pm.provider)
    }
    assertNull(result)
  }

  @Test
  fun `returns null when preferred fails and no fallbacks exist`() = runTest {
    val result = orchestrator().execute { pm ->
      Reply.Error.Busy(pm.provider)
    }
    assertNull(result)
  }

  // ─── 每一次嘗試的用量（全部都已送出，都可能已計費） ───

  private fun billed(content: String, pm: ProviderModel, inTok: Int) = Reply.Normal(content, null, pm.provider, pm.model, inputTokens = inTok, outputTokens = 10)

  @Test
  fun `preferred win records the finished and the cancelled fallbacks too`() = runTest {
    val o = orchestrator(fb1, fb2).executeExplained { pm ->
      when (pm) {
        preferred -> { delay(100.milliseconds); billed("preferred", pm, 300) }
        fb1       -> { delay(50.milliseconds); billed("fast fallback", pm, 200) }
        else      -> { delay(10.seconds); billed("slow fallback", pm, 100) }
      }
    } as Orchestration.Success
    assertEquals(3, o.attempts.size)
    // 贏家排最後、帶著用量
    assertEquals(preferred, o.attempts.last().providerModel)
    assertEquals(300, o.attempts.last().usage?.inputTokens)
    // 先跑完的 fallback 照實記；還在跑的被取消，用量不明
    assertEquals(200, o.attempts.single { it.providerModel == fb1 }.usage?.inputTokens)
    val cancelled = o.attempts.single { it.providerModel == fb2 }
    assertNull(cancelled.usage)
    assertEquals("cancelled: another model won", cancelled.note)
  }

  @Test
  fun `fallback win keeps the failed preferred attempt with its usage`() = runTest {
    val o = orchestrator(fb1).executeExplained { pm ->
      when (pm) {
        preferred -> Reply.Error.DeserializationFailure("bad", "{}", pm.provider, pm.model, inputTokens = 400, outputTokens = 20)
        else      -> { delay(2.seconds); billed("fallback", pm, 100) }
      }
    } as Orchestration.Success
    assertEquals(listOf(preferred, fb1), o.attempts.map { it.providerModel })
    assertEquals(400, o.attempts.first().usage?.inputTokens)
    assertEquals(100, o.attempts.last().usage?.inputTokens)
  }
}
