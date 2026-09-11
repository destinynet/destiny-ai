/**
 * Created by smallufo on 2026-07-25.
 *
 * 取消語意測試 —— 全部走假件（見 WorkflowTestFakes.kt），不打真 API。
 *
 * 本檔鎖住的契約分兩半：
 * 1. **我們自己被取消**（呼叫端 `job.cancel()` / `withTimeout`）→ 原樣傳播，不得回報成 segment 失敗。
 * 2. **外來的 CancellationException**（我們還活著，例外來自底層元件）→ 當成一般失敗，
 *    仍回 [ExecutionResult.Failed]，把 partialResults 與已花掉的成本帶回去。
 */
package destiny.tools.workflow

import destiny.tools.ai.*
import destiny.tools.ai.model.FormatSpec
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutionEngineCancellationTest {

  private val OK = SegmentId("ok")
  private val BOOM = SegmentId("boom")
  private val PARALLEL = SegmentId("parallel")

  /** 觸發「假想的 impl 內部逾時」的 prompt 標記。 */
  private val TIMEOUT_MARKER = "please-time-out"

  /**
   * 假想某個 provider impl 自己包了 `withTimeout` —— 逾時丟出的 [TimeoutCancellationException]
   * 是 CancellationException，但**呼叫端的 job 仍活著**，故對引擎而言屬於「外來取消」。
   *
   * 註：本 repo 目前**沒有** provider impl 這樣寫（真實逾時來自 Ktor 的 `HttpRequestTimeoutException`，
   * 屬 IOException；`HedgeOrchestrator` 用的是 `withTimeoutOrNull` 自行吞掉）。
   * 這裡純粹是用最省事的方式製造一個外來的 CancellationException。
   */
  private val innerTimeout: suspend (String) -> Unit = { prompt ->
    if (prompt.contains(TIMEOUT_MARKER)) withTimeout(50) { delay(3000) }
  }

  private fun imagePlan(vararg segments: Segment) = GenerationPlan<String>(
    planId = "img", name = "Img",
    segments = segments.toList(),
    assembler = { "done" }
  )

  private fun imageSeg(id: SegmentId, prompt: String, dependsOn: Set<SegmentId> = emptySet()) =
    Segment.ImageSegment(id, dependsOn, promptBuilder = { prompt })

  // ────────────────────────── 1. 我們自己被取消 ──────────────────────────

  /**
   * 取消 = 呼叫端的意志，不是 segment 失敗。
   *
   * 註：外部取消時 `execute` 的回傳值本來就到不了呼叫端 —— 下面 `launch` 出來的 job 已被取消，
   * 會丟棄 block 的回傳值改拋 CancellationException —— 故「回傳值」驗不出這個缺陷。
   * 真正外洩的是 [ExecutionProgressListener.onSegmentFailed]：監控／告警會平白多一筆假失敗。
   */
  @Test
  fun `外部取消 —— 不得回報成 segment 失敗`() = runBlocking {
    val listener = RecordingListener()
    val entered = CompletableDeferred<Unit>()
    // engine 在測試執行緒先建好（mockk 初始化很慢），避免取消早於 segment 啟動
    val engine = imageEngine(
      FakeImageOrchestrator(Provider.GEMINI),
      FakeImageGeneration(delayMs = 3000, entered = entered),
      progressListener = listener
    )

    val job = launch(Dispatchers.Default) { engine.execute(imagePlan(imageSeg(OK, "draw"))) }
    entered.await()          // 確認 segment 真的跑起來了
    job.cancelAndJoin()

    assertTrue(job.isCancelled)
    assertTrue(
      listener.failures.isEmpty(),
      "取消不是 segment 失敗，不該觸發 onSegmentFailed，實際收到：${listener.failures.map { it::class.simpleName }}"
    )
  }

  /**
   * 同上，但走 [Segment.ParallelAiSegment] —— per-item 的 catch 是三處中最安靜的一處：
   * 取消被降級成 `Result.failure` 後只會變成一筆 [FailedItem]，validator 若寬鬆，
   * 整段甚至會「完成」。故這裡連 onSegmentCompleted 都不許出現。
   */
  @Test
  fun `ParallelAiSegment 外部取消 —— 不得回報假的失敗或完成`() = runBlocking {
    val listener = RecordingListener()
    val entered = CompletableDeferred<Unit>()
    val engine = chatEngine(slowOrchestrator(entered, 3000), listener)

    val job = launch(Dispatchers.Default) { engine.execute(parallelPlan()) }
    entered.await()
    job.cancelAndJoin()

    assertTrue(job.isCancelled)
    assertTrue(
      listener.failures.isEmpty(),
      "取消不該被回報為 segment 失敗，實際收到：${listener.failures.map { it::class.simpleName }}"
    )
    assertTrue(
      listener.completed.isEmpty(),
      "被取消的 segment 更不該回報成完成，實際收到：${listener.completed}"
    )
  }

  /**
   * 已取消時，**每一層都被沿用**的續跑 plan 不得回 Success。
   *
   * 有 segment 要跑的層不必擔心：那層的 `coroutineScope { async { … } }` 本身就是取消檢查點
   * （子協程根本不會開始跑，實測過）。漏的是「整層都跳過」——`continue` 一路到底、組裝、回
   * Success，全程沒有任何 suspension point。故本條刻意把**所有** segment 都種進 initialContext。
   *
   * 回傳值到不了呼叫端（job 已取消），所以塞進 holder 從外面看。
   */
  @Test
  fun `已取消 —— 整份沿用的續跑 plan 不得回 Success`() = runBlocking {
    val A = SegmentId("a")
    val B = SegmentId("b")
    val seeded = ItemOutput("seeded", "seeded")
    val plan = GenerationPlan(
      planId = "cancel-all-reused", name = "Cancel All Reused",
      segments = listOf(
        Segment.ComputeSegment(id = A, compute = { error("整份沿用，不該執行") }),
        Segment.ComputeSegment(id = B, dependsOn = setOf(A), compute = { error("整份沿用，不該執行") })
      ),
      assembler = { ctx -> ctx.get<ItemOutput>(B).result }
    )
    val engine = chatEngine(slowOrchestrator(CompletableDeferred(), 0))
    val holder = java.util.concurrent.atomic.AtomicReference<ExecutionResult<String>?>()

    val job = launch(Dispatchers.Default) {
      coroutineContext.job.cancel()   // 模擬「已被取消」，再進 execute
      holder.set(engine.execute(plan, initialContext = mapOf(A to seeded, B to seeded)))
    }
    job.join()

    assertTrue(job.isCancelled)
    assertNull(holder.get(), "呼叫端已取消，引擎卻照樣跑完組裝並回 ${holder.get()}")
  }

  // ────────────────────────── 2. 外來的取消 ──────────────────────────

  /**
   * 底層元件丟出的 CancellationException 不代表「我們被取消」。此時若無條件往外拋，
   * [ExecutionResult] 會整個消失 —— 連同 partialResults 與**已經付過錢的 token／USD**。
   * 故契約是：外來取消視為一般失敗，照常回 [ExecutionResult.Failed] 並保住帳。
   */
  // 注意 `: Unit` —— 收尾的 assertIs 會回傳值，少了它整個 method 會被 JUnit 當成非 void 而**默默不執行**
  @Test
  fun `外來的取消 —— 回 Failed 且保住已花掉的成本與 partialResults`(): Unit = runBlocking {
    val pricing = ModelPricing(
      input = 0.0, output = 0.0,
      image = ImagePricing.PerImage(listOf(ImagePricing.PerImage.Entry(ImageSpec(), 0.04)))
    )
    val costService = ModelCostService(
      object : IModelCatalog {
        override fun allModels() = mapOf(
          Provider.REPLICATE to mapOf(FAKE_IMAGE_MODEL to ModelInfo(FAKE_IMAGE_MODEL, pricing))
        )
      }
    )

    val result = imageEngine(
      FakeImageOrchestrator(Provider.REPLICATE),
      FakeImageGeneration(provider = Provider.REPLICATE, onPrompt = innerTimeout),
      modelCostService = costService
    ).execute(
      imagePlan(
        imageSeg(OK, "draw"),                                   // 先花掉 0.04 美金
        imageSeg(BOOM, TIMEOUT_MARKER, dependsOn = setOf(OK))   // 再遇到外來取消
      )
    )

    assertIs<ExecutionResult.Failed<String>>(result)
    assertEquals(BOOM, result.failedSegment)
    // 帳不能跟著例外一起消失
    assertEquals(0.04, result.metadata.costUsd!!, 1e-9)
    assertIs<ImageOutput>(result.partialResults[OK])
  }

  /**
   * ParallelAiSegment 的 per-item：外來取消同樣降級為 [FailedItem]（validator 寬鬆時整段仍成功），
   * 而不是讓整個 plan 連同用量統計一起炸掉。
   */
  @Test
  fun `ParallelAiSegment 某項遇到外來取消 —— 降級為 FailedItem 而非炸掉整個 plan`() = runBlocking {
    val engine = chatEngine(timeoutOnItemOrchestrator(failOnCallIndex = 2))

    val result = engine.execute(parallelPlan())

    assertIs<ExecutionResult.Success<Pair<Int, Int>>>(result)
    val (successCount, failedCount) = result.result
    assertEquals(2, successCount)
    assertEquals(1, failedCount)
  }

  // ────────────────────────── 假件 ──────────────────────────

  @Serializable
  data class ItemOutput(val itemId: String, val result: String) : SegmentOutput

  data class ItemInput(val itemId: String) : SegmentInput

  /** 三個 item、無 validator（寬鬆）—— 正是「安靜失敗」最容易發生的設定。 */
  private fun parallelPlan() = GenerationPlan(
    planId = "parallel-cancel", name = "Parallel Cancel",
    segments = listOf(
      Segment.ParallelAiSegment<String, ItemOutput>(
        id = PARALLEL,
        itemsProvider = { listOf("i1", "i2", "i3") },
        itemInputBuilder = { item, _ -> ItemInput(item) },
        promptBuilder = { input, _ -> "Process ${(input as ItemInput).itemId}" },
        formatSpec = FormatSpec.of<ItemOutput>("ItemOutput", "item output"),
        resultValidator = null
      )
    ),
    assembler = { ctx ->
      val out = ctx.get<ParallelOutput<ItemOutput>>(PARALLEL)
      out.successful.size to out.failed.size
    }
  )

  private fun chatEngine(orchestrator: IChatOrchestrator, listener: ExecutionProgressListener? = null) =
    DefaultExecutionEngine(
      orchestrator = orchestrator,
      postProcessors = emptyList(),
      providerImpl = { throw UnsupportedOperationException() },
      progressListener = listener
    )

  /** 慢速 chat orchestrator：進來就 [entered] 握手，再 delay，讓外部有機會中途取消。 */
  private fun slowOrchestrator(entered: CompletableDeferred<Unit>, delayMs: Long) = object : IChatOrchestrator {
    override suspend fun <T : Any> chatComplete(
      formatSpec: FormatSpec<out T>,
      messages: List<Msg>,
      postProcessors: List<IPostProcessor>,
      locale: Locale,
      funCalls: Set<IFunctionDeclaration>,
      chatOptionsTemplate: ChatOptions,
      providerImpl: (Provider) -> IChatCompletion
    ): Reply.Normal<out T> {
      entered.complete(Unit)
      delay(delayMs)
      @Suppress("UNCHECKED_CAST")
      return Reply.Normal(ItemOutput("x", "y") as T, null, Provider.CLAUDE, "test-model")
    }
  }

  /** 第 [failOnCallIndex] 通呼叫遇到「假想 impl 內部逾時」的外來取消，其餘正常回。 */
  private fun timeoutOnItemOrchestrator(failOnCallIndex: Int) = object : IChatOrchestrator {
    private val callCount = java.util.concurrent.atomic.AtomicInteger(0)
    override suspend fun <T : Any> chatComplete(
      formatSpec: FormatSpec<out T>,
      messages: List<Msg>,
      postProcessors: List<IPostProcessor>,
      locale: Locale,
      funCalls: Set<IFunctionDeclaration>,
      chatOptionsTemplate: ChatOptions,
      providerImpl: (Provider) -> IChatCompletion
    ): Reply.Normal<out T> {
      val index = callCount.incrementAndGet()
      if (index == failOnCallIndex) withTimeout(50) { delay(3000) }
      @Suppress("UNCHECKED_CAST")
      return Reply.Normal(ItemOutput("item-$index", "result-$index") as T, null, Provider.CLAUDE, "test-model")
    }
  }
}
