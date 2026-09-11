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
}
