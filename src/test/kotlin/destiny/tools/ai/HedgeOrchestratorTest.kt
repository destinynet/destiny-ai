package destiny.tools.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
}
