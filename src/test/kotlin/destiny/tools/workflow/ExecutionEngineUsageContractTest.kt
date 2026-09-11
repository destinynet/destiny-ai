/**
 * Created by smallufo on 2026-07-25.
 *
 * 釘住兩條帳務契約（p0-p1 修正輪登記的 TODO #2）：
 *
 * 1. `Σ segmentUsages.calls ≤ totalAiCalls + totalImageCalls` —— 兩個欄位口徑不同
 *    （calls = 有拿到回應；total*Calls = 已發起），KDoc 講了但先前沒有測試釘住。
 * 2. `ParallelAiSegment` 被取消（同層兄弟失敗、CANCEL_SIBLINGS）時，取消前已拿到回應的
 *    item 用量必須入帳 —— 這正是「每層一個 coroutineScope、scope 回來子協程必定全部結束」
 *    買到的保證；改回 supervisorScope 式的提早結算，本測試會紅。
 *
 * 防空測：斷言全部用精確數字（不是只斷 ≤ 這種對 0 也恆真的關係式）。
 */
package destiny.tools.workflow

import destiny.tools.ai.*
import destiny.tools.ai.model.FormatSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutionEngineUsageContractTest {

  @Serializable
  data class ItemOut(val text: String) : SegmentOutput

  data class ItemInput(val item: String) : SegmentInput

  private val formatSpec = FormatSpec.of<ItemOut>("ItemOut", "contract test output")

  /**
   * prompt 驅動的假 orchestrator：含 BOOM 立刻炸、含 DELAY_BOOM 等 300ms 再炸、
   * 含 SLOW 慢 10s（用來被取消）；其餘回一筆 10/20 token 的正常回應。
   */
  private class ScriptedOrchestrator : IChatOrchestrator {
    override suspend fun <T : Any> chatComplete(
      formatSpec: FormatSpec<out T>,
      messages: List<Msg>,
      postProcessors: List<IPostProcessor>,
      locale: Locale,
      funCalls: Set<IFunctionDeclaration>,
      chatOptionsTemplate: ChatOptions,
      providerImpl: (Provider) -> IChatCompletion
    ): Reply.Normal<out T>? {
      val prompt = messages.joinToString { it.stringContents }
      if (prompt.contains("DELAY_BOOM")) {
        delay(300)
        throw RuntimeException("delayed boom")
      }
      if (prompt.contains("BOOM")) throw RuntimeException("boom")
      if (prompt.contains("SLOW")) delay(10_000)
      @Suppress("UNCHECKED_CAST")
      return Reply.Normal(
        ItemOut(prompt) as T, null, Provider.CLAUDE, "contract-model",
        inputTokens = 10, outputTokens = 20
      )
    }
  }

  private fun engine() = DefaultExecutionEngine(
    orchestrator = ScriptedOrchestrator(),
    postProcessors = emptyList(),
    providerImpl = { throw UnsupportedOperationException("orchestrator fake 不會用到") }
  )

  private val READ = SegmentId("read")
  private val FANOUT = SegmentId("fanout")
  private val SIDE = SegmentId("side")

  /**
   * 「已發起 4 次、只有 3 筆回應」的計畫：
   * layer-1 AiSegment 成功（1 發起/1 回應）→ layer-2 ParallelAiSegment 3 items，
   * 其中 1 item BOOM、validator requireAll 打回整段（3 發起/2 回應）。
   *
   * 防漂移錨點：若日後有人把 total*Calls 口徑改成「只計成功」，totalAiCalls 會變 2≠4 而紅；
   * 若把失敗 item 的回應也塞進 segmentUsages，Σcalls 會變 4≠3 而紅。
   */
  @Test
  fun `口徑契約 - Σcalls 為有回應數、total*Calls 為已發起數，前者 ≤ 後者`() = runBlocking {
    val plan = GenerationPlan<String>(
      planId = "quota", name = "Quota",
      segments = listOf(
        Segment.AiSegment(
          id = READ,
          inputBuilder = { ItemInput("read") },
          promptBuilder = { _, _ -> "read chart" },
          formatSpec = formatSpec
        ),
        Segment.ParallelAiSegment(
          id = FANOUT,
          dependsOn = setOf(READ),
          itemsProvider = { listOf("ok-1", "BOOM", "ok-2") },
          itemInputBuilder = { item, _ -> ItemInput(item) },
          promptBuilder = { input, _ -> (input as ItemInput).item },
          formatSpec = formatSpec,
          resultValidator = Validators.requireAll
        )
      ),
      assembler = { "unused" }
    )

    val result = engine().execute(plan)

    assertIs<ExecutionResult.Failed<String>>(result)
    assertEquals(FANOUT, result.failedSegment)

    val meta = result.metadata
    // 已發起：1 (AiSegment) + 3 (ParallelAiSegment items)，含炸掉的那 item
    assertEquals(4, meta.totalAiCalls)
    assertEquals(0, meta.totalImageCalls)

    // 有回應：READ 1 筆 + FANOUT 2 筆（BOOM 那 item 沒有回應、沒有用量可記）
    assertEquals(1, meta.segmentUsages[READ]?.calls)
    assertEquals(2, meta.segmentUsages[FANOUT]?.calls)

    val sigmaCalls = meta.segmentUsages.values.sumOf { it.calls }
    assertEquals(3, sigmaCalls)
    assertTrue(sigmaCalls <= meta.totalAiCalls + meta.totalImageCalls, "Σcalls 不得超過已發起數")

    // 3 筆回應 × (10/20) —— 失敗段中成功 item 的 token 一樣要入總帳
    assertEquals(30, meta.tokenUsage?.inputTokens)
    assertEquals(60, meta.tokenUsage?.outputTokens)
  }

  /**
   * ParallelAiSegment 執行中被取消（同層兄弟 300ms 後失敗、預設 CANCEL_SIBLINGS）：
   * 取消前已拿到回應的 item（ok-fast，~0ms 完成）用量必須入帳；還在跑的 item（SLOW）沒有回應、
   * 不入 segmentUsages，但已發起數照計。
   *
   * 防漂移錨點：p0-p1 修正前的 supervisorScope + awaitAll 在失敗當下就結算，
   * 這筆 10/20 會漏記（metadata.tokenUsage 直接 null）—— 該行為回歸時本測試會紅。
   */
  @Test
  fun `取消契約 - 被取消的 ParallelAiSegment，已回應 item 的用量入帳`() = runBlocking {
    val plan = GenerationPlan<String>(
      planId = "cancelled-fanout", name = "CancelledFanout",
      segments = listOf(
        Segment.ParallelAiSegment(
          id = FANOUT,
          itemsProvider = { listOf("ok-fast", "SLOW") },
          itemInputBuilder = { item, _ -> ItemInput(item) },
          promptBuilder = { input, _ -> (input as ItemInput).item },
          formatSpec = formatSpec
        ),
        Segment.AiSegment(
          id = SIDE,
          inputBuilder = { ItemInput("side") },
          promptBuilder = { _, _ -> "DELAY_BOOM" },
          formatSpec = formatSpec
        )
      ),
      assembler = { "unused" }
    )

    val result = engine().execute(plan)

    assertIs<ExecutionResult.Failed<String>>(result)
    assertEquals(SIDE, result.failedSegment)
    // FANOUT 整段被取消，沒有完成 → 不進 partialResults
    assertNull(result.partialResults[FANOUT])

    val meta = result.metadata
    // 已發起：2 (FANOUT items) + 1 (SIDE)
    assertEquals(3, meta.totalAiCalls)

    // ok-fast 在兄弟炸掉（300ms）前早已回應 —— 段被取消，這筆用量仍須入帳
    assertEquals(1, meta.segmentUsages[FANOUT]?.calls)
    assertEquals(10, meta.segmentUsages[FANOUT]?.tokenUsage?.inputTokens)
    assertEquals(20, meta.segmentUsages[FANOUT]?.tokenUsage?.outputTokens)
    // SIDE 炸掉、SLOW 被取消：都沒有回應 → 全計畫就這一筆
    assertEquals(setOf(FANOUT), meta.segmentUsages.keys)
    assertEquals(10, meta.tokenUsage?.inputTokens)
    assertEquals(20, meta.tokenUsage?.outputTokens)

    val sigmaCalls = meta.segmentUsages.values.sumOf { it.calls }
    assertTrue(sigmaCalls <= meta.totalAiCalls + meta.totalImageCalls, "Σcalls 不得超過已發起數")
  }
}
