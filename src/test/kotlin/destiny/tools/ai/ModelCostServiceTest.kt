package destiny.tools.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModelCostServiceTest {

  private val catalog = object : IModelCatalog {
    override fun allModels(): Map<Provider, Map<String, ModelInfo>> = mapOf(
      Provider.CLAUDE to mapOf(
        "c1" to ModelInfo("c1", ModelPricing(3.0, 15.0)),
        "c2" to ModelInfo("c2", ModelPricing(3.0, 15.0, cacheRead = 0.3, cacheWrite = 3.75)),
      ),
      Provider.OPENAI to mapOf("o1" to ModelInfo("o1", ModelPricing(2.0, 8.0))),
    )
  }

  private val service = ModelCostService(catalog)

  private fun normal(provider: Provider, model: String, inTok: Int, outTok: Int,
                     cacheReadTok: Int = 0, cacheCreationTok: Int = 0) =
    Reply.Normal(content = "x", think = null, provider = provider, model = model,
      inputTokens = inTok, outputTokens = outTok,
      cacheReadTokens = cacheReadTok, cacheCreationTokens = cacheCreationTok)

  @Test
  fun `cost — resolves via provider then model`() {
    // (1000*3 + 500*15)/1e6 = 0.0105
    val actual = assertNotNull(service.cost(normal(Provider.CLAUDE, "c1", 1000, 500)))
    assertEquals(0.0105, actual, 1e-12)
  }

  @Test
  fun `cost — unknown model returns null`() {
    assertNull(service.cost(normal(Provider.CLAUDE, "unknown", 100, 100)))
  }

  @Test
  fun `cost — unknown provider returns null`() {
    assertNull(service.cost(normal(Provider.GEMINI, "x", 100, 100)))
  }

  @Test
  fun `cost — includes cache tokens with correct read-vs-write mapping`() {
    // (1000*3 + 500*15 + 2000*0.3[read] + 4000*3.75[write]) / 1e6 = 0.0261
    val actual = assertNotNull(service.cost(normal(Provider.CLAUDE, "c2", 1000, 500, cacheReadTok = 2000, cacheCreationTok = 4000)))
    assertEquals(0.0261, actual, 1e-12)
  }

  // ─── 一次編排的總帳 ───

  private val c1 = ProviderModel(Provider.CLAUDE, "c1")
  private val o1 = ProviderModel(Provider.OPENAI, "o1")

  @Test
  fun `usage — failed validation attempts are billed too`() {
    val o = Orchestration.Success(
      normal(Provider.CLAUDE, "c1", 1000, 500),
      listOf(
        Orchestration.Attempt(c1, error = Reply.Error.DeserializationFailure("bad", "{}", Provider.CLAUDE, "c1", 1000, 300), usage = TokenUsage(1000, 300)),
        Orchestration.Attempt(c1, loop = 2, usage = TokenUsage(1000, 500)),
      ),
    )
    val u = service.usage(o)
    assertEquals(2, u.attempts)
    assertEquals(TokenUsage(2000, 800), u.tokens)
    // (2000*3 + 800*15)/1e6 = 0.018
    assertEquals(0.018, u.costUsd!!, 1e-12)
    assertEquals(0, u.unpricedAttempts)
  }

  @Test
  fun `usage — unknown usage or price is counted, not guessed`() {
    val o = Orchestration.Exhausted(
      listOf(
        Orchestration.Attempt(o1, usage = TokenUsage(1000, 1000)),                       // 0.01
        Orchestration.Attempt(c1, thrown = IllegalStateException("timeout")),            // 用量不明
        Orchestration.Attempt(ProviderModel(Provider.CLAUDE, "unknown"), usage = TokenUsage(5, 5)), // 價格不明
      )
    )
    val u = service.usage(o)
    assertEquals(3, u.attempts)
    assertEquals(0.01, u.costUsd!!, 1e-12)
    assertEquals(2, u.unpricedAttempts)
    assertEquals(TokenUsage(1005, 1005), u.tokens)
  }

  @Test
  fun `usage — nothing sent`() {
    val u = service.usage(Orchestration.Exhausted(emptyList(), note = "no provider models"))
    assertEquals(OrchestrationUsage(0, null, null, 0), u)
  }
}
