package destiny.tools.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TokenUsageAccumulatorTest {

  @Test
  fun `fresh accumulator is all null`() {
    val acc = TokenUsageAccumulator()
    assertNull(acc.inputTokens)
    assertNull(acc.outputTokens)
    assertNull(acc.cacheCreationTokens)
    assertNull(acc.cacheReadTokens)
  }

  @Test
  fun `single add reflects values, untouched dims stay null`() {
    val acc = TokenUsageAccumulator()
    acc.add(input = 10, output = 5)
    assertEquals(10, acc.inputTokens)
    assertEquals(5, acc.outputTokens)
    assertNull(acc.cacheCreationTokens)
    assertNull(acc.cacheReadTokens)
  }

  @Test
  fun `multiple adds sum each dimension`() {
    val acc = TokenUsageAccumulator()
    acc.add(input = 10, output = 5, cacheCreation = 1, cacheRead = 2)
    acc.add(input = 20, output = 7, cacheCreation = 3, cacheRead = 4)
    assertEquals(30, acc.inputTokens)
    assertEquals(12, acc.outputTokens)
    assertEquals(4, acc.cacheCreationTokens)
    assertEquals(6, acc.cacheReadTokens)
  }

  @Test
  fun `null in a round counts as zero but does not erase prior value`() {
    val acc = TokenUsageAccumulator()
    acc.add(input = 10)            // output null this round
    acc.add(input = 20, output = 7)
    assertEquals(30, acc.inputTokens)
    assertEquals(7, acc.outputTokens)  // first round's null treated as 0
  }

  @Test
  fun `a dimension never supplied stays null even after adds`() {
    val acc = TokenUsageAccumulator()
    acc.add(input = 10)
    acc.add(input = 20)
    assertNull(acc.outputTokens)   // never provided → still unknown, not 0
    assertEquals(30, acc.inputTokens)
  }
}
