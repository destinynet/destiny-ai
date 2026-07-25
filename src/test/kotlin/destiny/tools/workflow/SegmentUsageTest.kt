/**
 * Created by smallufo on 2026-07-25.
 */
package destiny.tools.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmentUsageTest {

  @Test
  fun `SegmentUsage 承載單段的呼叫次數、token、成本、model`() {
    val usage = SegmentUsage(
      calls = 2,
      tokenUsage = TokenUsage(inputTokens = 100, outputTokens = 200),
      costUsd = 0.03,
      modelsUsed = listOf(UsedModel("CLAUDE", "claude-opus-4-8"))
    )
    assertEquals(2, usage.calls)
    assertEquals(200, usage.tokenUsage?.outputTokens)
    assertEquals(0.03, usage.costUsd)
    assertEquals("claude-opus-4-8", usage.modelsUsed.single().model)
  }

  @Test
  fun `ExecutionMetadata 的 segmentUsages 預設為空 —— 既有 caller 不受影響`() {
    val meta = ExecutionMetadata.empty()
    assertTrue(meta.segmentUsages.isEmpty())
  }
}
