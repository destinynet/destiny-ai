/**
 * Created by smallufo on 2026-11-10.
 *
 * 編排的**每一次嘗試**都入帳：驗證不過後重試成功、全部失敗的段落，用量與成本都要出現在 metadata。
 * 舊行為只記成功的那一份 reply —— 驗證不過那幾次（照樣計費）與失敗段落花掉的錢都漏掉。
 */
package destiny.tools.workflow

import destiny.tools.ai.*
import destiny.tools.ai.model.FormatSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExecutionEngineAttemptUsageTest {

  @Serializable
  data class Out(val text: String) : SegmentOutput

  data class In(val text: String) : SegmentInput

  private val spec = FormatSpec.of<Out>("Out", "attempt usage test output")
  private val pm = ProviderModel(Provider.CLAUDE, "m")
  private val seg = SegmentId("read")

  private val costs = ModelCostService(object : IModelCatalog {
    override fun allModels() = mapOf(Provider.CLAUDE to mapOf("m" to ModelInfo("m", ModelPricing(3.0, 15.0))))
  })

  /** 回一個寫死的編排結果 */
  private class FixedOrchestrator(val result: Orchestration<*>) : IChatOrchestrator {
    override suspend fun <T : Any> chatComplete(
      formatSpec: FormatSpec<out T>, messages: List<Msg>, postProcessors: List<IPostProcessor>, locale: Locale,
      funCalls: Set<IFunctionDeclaration>, chatOptionsTemplate: ChatOptions, providerImpl: (Provider) -> IChatCompletion
    ): Reply.Normal<out T>? = error("unused")

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> chatCompleteOrExplain(
      formatSpec: FormatSpec<out T>, messages: List<Msg>, postProcessors: List<IPostProcessor>, locale: Locale,
      funCalls: Set<IFunctionDeclaration>, chatOptionsTemplate: ChatOptions, providerImpl: (Provider) -> IChatCompletion
    ): Orchestration<out T> = result as Orchestration<T>
  }

  private fun engine(o: Orchestration<*>) = DefaultExecutionEngine(
    orchestrator = FixedOrchestrator(o),
    postProcessors = emptyList(),
    providerImpl = { throw UnsupportedOperationException() },
    modelCostService = costs,
  )

  private val plan = GenerationPlan<String>(
    planId = "attempts", name = "Attempts",
    segments = listOf(Segment.AiSegment(id = seg, inputBuilder = { In("x") }, promptBuilder = { _, _ -> "x" }, formatSpec = spec)),
    assembler = { "done" },
  )

  private val invalid = Orchestration.Attempt(
    pm, error = Reply.Error.DeserializationFailure("bad", "{}", pm.provider, pm.model, 1000, 100), usage = TokenUsage(1000, 100),
  )

  @Test
  fun `a failed validation before success is billed`() = runBlocking {
    val reply = Reply.Normal(Out("ok"), null, pm.provider, pm.model, inputTokens = 1100, outputTokens = 200)
    val o = Orchestration.Success(reply, listOf(invalid, Orchestration.Attempt(pm, loop = 2, usage = TokenUsage(1100, 200))))
    val meta = assertIs<ExecutionResult.Success<String>>(engine(o).execute(plan)).metadata

    assertEquals(2, meta.totalAiCalls)
    assertEquals(2, meta.segmentUsages[seg]?.calls)
    assertEquals(2100, meta.tokenUsage?.inputTokens)
    assertEquals(300, meta.tokenUsage?.outputTokens)
    // (2100*3 + 300*15)/1e6
    assertEquals(0.0108, meta.costUsd!!, 1e-12)
  }

  @Test
  fun `an exhausted segment still records what it spent`() = runBlocking {
    val timedOut = Orchestration.Attempt(pm, thrown = IllegalStateException("timeout"), loop = 2)
    val result = engine(Orchestration.Exhausted(listOf(invalid, timedOut))).execute(plan)
    val failed = assertIs<ExecutionResult.Failed<String>>(result)
    // 引擎把段落的例外包成 SegmentExecutionException；編排的說明在 cause
    assertTrue("exhausted" in failed.error.cause?.message.orEmpty(), failed.error.cause?.message)

    val meta = failed.metadata
    // 已發起 2 次；只有帶用量的 1 次入 segmentUsages（逾時那次金額不明）
    assertEquals(2, meta.totalAiCalls)
    assertEquals(1, meta.segmentUsages[seg]?.calls)
    assertEquals(1000, meta.tokenUsage?.inputTokens)
    assertEquals(0.0045, meta.costUsd!!, 1e-12)
  }
}
