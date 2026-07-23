package destiny.tools.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class ResilientOrchestratorTest {

  private val pmA = ProviderModel(Provider.OPENAI, "model-a")
  private val pmB = ProviderModel(Provider.GEMINI, "model-b")

  private fun normal(content: String, pm: ProviderModel) =
    Reply.Normal(content, null, pm.provider, pm.model)

  @Test
  fun `success on first attempt returns Normal`() = runTest {
    val orchestrator = ResilientOrchestrator(setOf(pmA))
    var calls = 0
    val result = orchestrator.execute { pm ->
      calls++
      normal("ok", pm)
    }
    assertEquals("ok", result?.content)
    assertEquals(1, calls)
  }

  @Test
  fun `retryable error retries in next loop after backoff and succeeds`() = runTest {
    val orchestrator = ResilientOrchestrator(setOf(pmA), delayBetweenModelLoops = 2.seconds)
    var calls = 0
    val result = orchestrator.execute { pm ->
      calls++
      if (calls == 1) Reply.Error.Busy(pm.provider) else normal("ok", pm)
    }
    assertEquals("ok", result?.content)
    assertEquals(2, calls)
    // 第二輪前必須等待 delayBetweenModelLoops
    assertEquals(2000, currentTime)
  }

  @Test
  fun `terminal error also moves to next loop and may succeed later`() = runTest {
    val orchestrator = ResilientOrchestrator(setOf(pmA), delayBetweenModelLoops = 1.seconds)
    var calls = 0
    val result = orchestrator.execute { pm ->
      calls++
      if (calls == 1) Reply.Error.Unknown("boom", pm.provider) else normal("ok", pm)
    }
    assertEquals("ok", result?.content)
    assertEquals(2, calls)
  }

  @Test
  fun `invalid api key disables provider for remaining loops`() = runTest {
    val orchestrator = ResilientOrchestrator(setOf(pmA, pmB), delayBetweenModelLoops = 1.seconds, maxTotalAttempts = 3)
    var callsA = 0
    var callsB = 0
    val result = orchestrator.execute { pm ->
      if (pm == pmA) {
        callsA++
        Reply.Error.InvalidApiKey(pm.provider)
      } else {
        callsB++
        Reply.Error.Busy(pm.provider)
      }
    }
    assertNull(result)
    assertEquals(1, callsA)   // InvalidApiKey → 之後 loop 不再嘗試
    assertEquals(3, callsB)   // 每輪都試
  }

  @Test
  fun `returns null when all providers disabled`() = runTest {
    val orchestrator = ResilientOrchestrator(setOf(pmA), maxTotalAttempts = 3)
    var calls = 0
    val result = orchestrator.execute { pm ->
      calls++
      Reply.Error.InvalidApiKey(pm.provider)
    }
    assertNull(result)
    assertEquals(1, calls)
  }

  @Test
  fun `returns null after maxTotalAttempts loops exhausted`() = runTest {
    val orchestrator = ResilientOrchestrator(setOf(pmA), delayBetweenModelLoops = 1.seconds, maxTotalAttempts = 3)
    var calls = 0
    val result = orchestrator.execute { pm ->
      calls++
      Reply.Error.Busy(pm.provider)
    }
    assertNull(result)
    assertEquals(3, calls)
  }

  @Test
  fun `rateLimited retryAfter extends backoff beyond delayBetweenModelLoops`() = runTest {
    val orchestrator = ResilientOrchestrator(setOf(pmA), delayBetweenModelLoops = 2.seconds)
    var calls = 0
    val result = orchestrator.execute { pm ->
      calls++
      if (calls == 1) Reply.Error.RateLimited(pm.provider, retryAfter = 10.seconds) else normal("ok", pm)
    }
    assertEquals("ok", result?.content)
    // backoff = max(delayBetweenModelLoops, retryAfter) = 10s
    assertEquals(10_000, currentTime)
  }

  @Test
  fun `exception from attempt is treated as failure and retried`() = runTest {
    val orchestrator = ResilientOrchestrator(setOf(pmA), delayBetweenModelLoops = 1.seconds)
    var calls = 0
    val result = orchestrator.execute { pm ->
      calls++
      if (calls == 1) throw RuntimeException("network broke") else normal("ok", pm)
    }
    assertEquals("ok", result?.content)
    assertEquals(2, calls)
  }

  @Test
  fun `cancellation propagates instead of being swallowed`() = runTest {
    val orchestrator = ResilientOrchestrator(setOf(pmA))
    assertFailsWith<CancellationException> {
      orchestrator.execute<String> { throw CancellationException("cancelled") }
    }
  }

  @Test
  fun `empty providerModels returns null without calling attempt`() = runTest {
    val orchestrator = ResilientOrchestrator(emptySet())
    var calls = 0
    val result = orchestrator.execute { _ ->
      calls++
      normal("ok", pmA)
    }
    assertNull(result)
    assertEquals(0, calls)
  }
}
