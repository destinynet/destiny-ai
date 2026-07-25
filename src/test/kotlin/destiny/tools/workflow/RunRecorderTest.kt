/**
 * Created by smallufo on 2026-07-25.
 *
 * 釘住帳務純函數（fold / aggregateUsage）與 RunRecorder 的語意。
 * 這些契約原本只存在於 ExecutionEngine 的註解與 core-impl 的引擎測試，搬進 destiny-ai 後就近釘住。
 */
package destiny.tools.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunRecorderTest {

  private val SEG_A = SegmentId("a")
  private val SEG_B = SegmentId("b")

  private fun usage(
    segmentId: SegmentId = SEG_A,
    provider: String = "CLAUDE",
    model: String = "claude-opus-4-8",
    input: Int? = null,
    output: Int? = null,
    cacheCreation: Int? = null,
    cacheRead: Int? = null,
    costUsd: Double? = null
  ) = CallUsage(segmentId, provider, model, input, output, cacheCreation, cacheRead, costUsd)

  // ── fold ──

  @Test
  fun `fold - token null 語意 - 某維度從未收到值即維持 null，收過值後缺漏輪視為 0`() {
    val folded = fold(
      listOf(
        usage(input = 100, output = 200),
        usage(input = null, output = 50)
      )
    )
    assertEquals(2, folded.calls)
    assertEquals(100, folded.tokenUsage?.inputTokens)
    assertEquals(250, folded.tokenUsage?.outputTokens)
    // cache 維度兩筆皆 null → 維持 null（「未知」），不是 0
    assertNull(folded.tokenUsage?.cacheCreationTokens)
    assertNull(folded.tokenUsage?.cacheReadTokens)
  }

  @Test
  fun `fold - 成本 - 全 null 則 null，部分有值則只加總有值者`() {
    assertNull(fold(listOf(usage(), usage())).costUsd)

    val folded = fold(listOf(usage(costUsd = 0.03), usage(costUsd = null), usage(costUsd = 0.04)))
    assertEquals(0.07, folded.costUsd!!, 1e-9)
  }

  @Test
  fun `fold - model 去重並保留首次出現順序`() {
    val folded = fold(
      listOf(
        usage(provider = "CLAUDE", model = "opus"),
        usage(provider = "GEMINI", model = "flash"),
        usage(provider = "CLAUDE", model = "opus")
      )
    )
    assertEquals(
      listOf(UsedModel("CLAUDE", "opus"), UsedModel("GEMINI", "flash")),
      folded.modelsUsed
    )
  }

  // ── aggregateUsage ──

  @Test
  fun `aggregateUsage - 空集防線 - 無任何呼叫時 tokenUsage 與 costUsd 為 null 而非零值物件`() {
    val agg = aggregateUsage(emptyList())
    assertNull(agg.tokenUsage)
    assertNull(agg.costUsd)
    assertTrue(agg.modelsUsed.isEmpty())
    assertTrue(agg.perSegment.isEmpty())
  }

  @Test
  fun `aggregateUsage - 分段加總等於總額`() {
    val agg = aggregateUsage(
      listOf(
        usage(segmentId = SEG_A, input = 100, output = 200, costUsd = 0.03),
        usage(segmentId = SEG_B, input = 10, output = 20, costUsd = 0.04),
        usage(segmentId = SEG_B, input = 1, output = 2, costUsd = 0.05)
      )
    )
    assertEquals(setOf(SEG_A, SEG_B), agg.perSegment.keys)
    assertEquals(1, agg.perSegment[SEG_A]!!.calls)
    assertEquals(2, agg.perSegment[SEG_B]!!.calls)

    val segCostSum = agg.perSegment.values.sumOf { it.costUsd!! }
    assertEquals(agg.costUsd!!, segCostSum, 1e-9)

    val segInputSum = agg.perSegment.values.sumOf { it.tokenUsage!!.inputTokens!! }
    assertEquals(agg.tokenUsage!!.inputTokens, segInputSum)
  }

  // ── RunRecorder ──

  @Test
  fun `RunRecorder - 記帳全數落進 buildMetadata`() {
    val recorder = RunRecorder()
    recorder.onAiCallsLaunched(1)
    recorder.onAiCallsLaunched(3)
    recorder.onImageCallLaunched()
    recorder.onParallelLayer()
    recorder.onSegmentFinished(SEG_A, 123L)
    recorder.record(usage(segmentId = SEG_A, input = 100, output = 200, costUsd = 0.03))

    val meta = recorder.buildMetadata()
    assertEquals(4, meta.totalAiCalls)
    assertEquals(1, meta.totalImageCalls)
    assertEquals(1, meta.parallelExecutions)
    assertEquals(mapOf(SEG_A to 123L), meta.segmentDurations)
    assertEquals(100, meta.tokenUsage?.inputTokens)
    assertEquals(0.03, meta.costUsd!!, 1e-9)
    assertEquals(listOf(UsedModel("CLAUDE", "claude-opus-4-8")), meta.modelsUsed)
    assertEquals(setOf(SEG_A), meta.segmentUsages.keys)
    assertTrue(meta.totalDurationMs >= 0)
  }

  @Test
  fun `RunRecorder - 什麼都沒記時 metadata 維持 null 與空集`() {
    val meta = RunRecorder().buildMetadata()
    assertEquals(0, meta.totalAiCalls)
    assertEquals(0, meta.totalImageCalls)
    assertEquals(0, meta.parallelExecutions)
    assertNull(meta.tokenUsage)
    assertNull(meta.costUsd)
    assertTrue(meta.modelsUsed.isEmpty())
    assertTrue(meta.segmentUsages.isEmpty())
    assertTrue(meta.segmentDurations.isEmpty())
  }
}
