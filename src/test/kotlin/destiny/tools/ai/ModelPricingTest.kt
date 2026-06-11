package destiny.tools.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelPricingTest {

  /** input/output only: (1000*3 + 500*15)/1e6 = 0.0105 */
  @Test
  fun `cost — input and output only`() {
    val p = ModelPricing(input = 3.0, output = 15.0)
    assertEquals(0.0105, p.cost(input = 1000, output = 500), 1e-12)
  }

  /** with explicit cache prices:
   *  (1000*3 + 500*15 + 2000*0.3 + 4000*3.75)/1e6 = 0.0261 */
  @Test
  fun `cost — with explicit cache prices`() {
    val p = ModelPricing(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75)
    assertEquals(0.0261, p.cost(input = 1000, output = 500, cacheRead = 2000, cacheWrite = 4000), 1e-12)
  }

  /** cacheRead/cacheWrite null → fall back to input price.
   *  1_000_000 cacheRead tokens * input(2.0) / 1e6 = 2.0 */
  @Test
  fun `cost — null cache prices fall back to input price`() {
    val p = ModelPricing(input = 2.0, output = 8.0)
    assertEquals(2.0, p.cost(input = 0, output = 0, cacheRead = 1_000_000), 1e-12)
  }

  @Test
  fun `cost — all zero tokens is zero`() {
    assertEquals(0.0, ModelPricing(1.0, 1.0).cost(0, 0), 1e-12)
  }
}
