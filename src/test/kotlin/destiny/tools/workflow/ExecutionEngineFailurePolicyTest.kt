/**
 * Created by smallufo on 2026-07-25.
 *
 * 同層失敗政策測試 —— 全部走假件（見 WorkflowTestFakes.kt），不打真 API。
 *
 * 鎖住的契約：同層任一 segment 失敗時，
 * 1. 兄弟會被**取消**（不再把錢花在已註定丟棄的結果上），且此為**預設**行為；
 *    明示 [FailurePolicy.AWAIT_SIBLINGS] 才改為等兄弟跑完；
 * 2. 已經花掉的錢**一定要記進帳**（[ExecutionMetadata.costUsd]）—— 不論它是在失敗之前或之後才寫入；
 * 3. 已經跑完的 segment 成果**一定留在** partialResults（兩種政策皆然）—— 那是續跑的本錢。
 * 4. 失敗**歸屬得出去**：連使用者 lambda（`itemsProvider`）拋的例外都要指名是哪一段炸的。
 */
package destiny.tools.workflow

import destiny.tools.ai.*
import io.mockk.mockk
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

class ExecutionEngineFailurePolicyTest {

  private val FAST = SegmentId("fast")
  private val SLOW = SegmentId("slow")

  /** prompt 標記：帶此標記的 segment 會（在 delay 之後）拋出例外。 */
  private val BOOM = "boom"

  private fun imagePlan(vararg segments: Segment) = GenerationPlan<String>(
    planId = "policy", name = "Policy",
    segments = segments.toList(),
    assembler = { "done" }
  )

  /** 無 dependsOn ⇒ 全部落在同一層並行執行。 */
  private fun imageSeg(id: SegmentId, prompt: String) =
    Segment.ImageSegment(id, emptySet(), promptBuilder = { prompt })

  /**
   * 本檔專用的引擎 builder —— [policy] 刻意是**必填**的：政策仍出現在每個呼叫端（測誰一目了然），
   * 但建構參數只有一份，不再靠手抄（三份手抄正是上一輪修掉的漂移風險）。
   * 走 [imageEngine] 的只剩「鎖住預設政策」那一條，它本來就不該看到這個參數。
   */
  private fun engine(
    policy: FailurePolicy,
    impl: FakeImageGeneration,
    modelCostService: ModelCostService? = null,
    progressListener: ExecutionProgressListener? = null
  ) = DefaultExecutionEngine(
    orchestrator = mockk(),
    postProcessors = emptyList(),
    providerImpl = { mockk() },
    modelCostService = modelCostService,
    progressListener = progressListener,
    imageOrchestrator = FakeImageOrchestrator(impl.provider),
    imageProviderImpl = { impl },
    failurePolicy = policy
  )

  // ────────────────────────── 1. fail-fast：取消兄弟 ──────────────────────────

  /**
   * 同層 A 先炸、B 還要跑 [SIBLING_WORK_MS]：B 必須被取消。
   *
   * 這在生圖特別有感 —— 兄弟每張 $0.04，繼續跑完只是「付錢買一個註定被丟棄的結果」。
   * 斷言刻意分成三層，避免「其實 B 根本沒開跑」這種假綠：
   * B 有進到 impl（[FakeImageGeneration.prompts]）、但沒跑完（[FakeImageGeneration.finished]），
   * 且整體耗時遠小於 B 的工時。
   */
  @Test
  fun `同層一支失敗 —— 兄弟被取消，不等它跑完`(): Unit = runBlocking {
    val impl = FakeImageGeneration(onPrompt = boomOrSlowWork)
    // engine 先建好再計時 —— 建構時第一次 mockk() 要花上百毫秒（ByteBuddy 暖機），會污染耗時斷言。
    val engine = engine(FailurePolicy.CANCEL_SIBLINGS, impl)

    val (result, elapsed) = measureTimedValue {
      engine.execute(imagePlan(imageSeg(FAST, BOOM), imageSeg(SLOW, "draw")))
    }

    assertIs<ExecutionResult.Failed<String>>(result)
    assertEquals(FAST, result.failedSegment)
    assertSiblingCancelled(impl, elapsed.inWholeMilliseconds, result.metadata)
  }

  /**
   * **不傳** `failurePolicy` 時行為必須與明示 `CANCEL_SIBLINGS` 相同 —— 鎖住預設值。
   * （[imageEngine] 一律不轉傳該參數，故這裡走的是引擎建構子自己的預設。）
   */
  @Test
  fun `未指定 failurePolicy —— 預設就是取消兄弟`(): Unit = runBlocking {
    val impl = FakeImageGeneration(onPrompt = boomOrSlowWork)
    val engine = imageEngine(FakeImageOrchestrator(Provider.GEMINI), impl)

    val (result, elapsed) = measureTimedValue {
      engine.execute(imagePlan(imageSeg(FAST, BOOM), imageSeg(SLOW, "draw")))
    }

    assertIs<ExecutionResult.Failed<String>>(result)
    assertEquals(FAST, result.failedSegment)
    assertSiblingCancelled(impl, elapsed.inWholeMilliseconds, result.metadata)
  }

  // ────────────────────────── 1b. AWAIT_SIBLINGS：等兄弟跑完 ──────────────────────────

  /**
   * 明示 [FailurePolicy.AWAIT_SIBLINGS] 時，同層一支炸掉**不得**中斷兄弟。
   *
   * 這條政策的價值不在「錢會少花」（不會），而在**換到三樣東西**，故三者缺一即算失敗：
   * 兄弟跑完（[FakeImageGeneration.finished]）、成本進帳、成果留在 partialResults 可續跑。
   */
  @Test
  fun `AWAIT_SIBLINGS —— 兄弟跑完，成本與成果都留下`(): Unit = runBlocking {
    val impl = FakeImageGeneration(provider = Provider.REPLICATE, onPrompt = boomOrSlowWork)
    val engine = engine(
      FailurePolicy.AWAIT_SIBLINGS, impl, modelCostService = ModelCostService(PerImageCatalog())
    )

    val result = engine.execute(imagePlan(imageSeg(FAST, BOOM), imageSeg(SLOW, "draw")))

    assertIs<ExecutionResult.Failed<String>>(result)
    assertEquals(FAST, result.failedSegment, "失敗仍要如實回報是哪一支炸的")
    assertTrue(impl.finished.contains("draw"), "兄弟被取消了 —— 讓它跑完是本政策的前提，其餘斷言都建立在此")
    assertEquals(2, result.metadata.totalImageCalls, "兩支都發起了")
    assertEquals(
      0.04, result.metadata.costUsd ?: error("兄弟的 \$0.04 沒進帳 —— 等它跑完卻不記帳，等於白等"), 1e-9
    )
    assertSiblingOutputUsable(result, sibling = SLOW, failed = FAST)
  }

  /**
   * 本政策的成果**只**經由「暫存區 → flushCompletedOutputs」進 context（`settled` 的回傳值除了挑
   * 例外之外整批丟棄），與 fail-fast 走的不是同一段路；而上面三條全都有一支失敗，故「下游讀得到
   * 上游輸出、assembler 拿到完整結果」在本政策下等於零覆蓋。第一個把 production plan 切過來的人，
   * 要的第一個保證正是這條。
   */
  @Test
  fun `AWAIT_SIBLINGS 全部成功 —— 下游讀得到上游輸出，帳也對得上`(): Unit = runBlocking {
    val A = SegmentId("a")
    val B = SegmentId("b")
    val MERGED = SegmentId("merged")
    val impl = FakeImageGeneration(provider = Provider.REPLICATE)
    val engine = engine(
      FailurePolicy.AWAIT_SIBLINGS, impl, modelCostService = ModelCostService(PerImageCatalog())
    )

    val plan = GenerationPlan(
      planId = "await-happy", name = "Await Happy",
      segments = listOf(
        imageSeg(A, "a"), imageSeg(B, "b"),
        Segment.ImageSegment(
          MERGED, dependsOn = setOf(A, B),
          // 讀兩個上游的輸出：上游成果沒併入 context 的話這裡直接 NoSuchElementException
          promptBuilder = { ctx ->
            "merge ${ctx.get<ImageOutput>(A).images.size}+${ctx.get<ImageOutput>(B).images.size}"
          }
        )
      ),
      assembler = { ctx -> ctx.get<ImageOutput>(MERGED).images.size }
    )

    val result = engine.execute(plan)

    assertIs<ExecutionResult.Success<Int>>(result)
    assertEquals(1, result.result, "assembler 沒拿到下游的完整成果")
    assertTrue("merge 1+1" in impl.prompts, "下游讀不到上游輸出。全部 prompt：${impl.prompts}")
    assertEquals(1, result.metadata.parallelExecutions, "上游那層兩支同時跑，算一次並行")
    assertEquals(3, result.metadata.totalImageCalls)
    assertEquals(0.12, result.metadata.costUsd ?: error("三張圖的 \$0.12 沒進帳"), 1e-9)
    assertEquals(setOf(A, B, MERGED), result.metadata.segmentUsages.keys)
    result.metadata.segmentUsages.forEach { (id, usage) ->
      assertEquals(1, usage.calls, "$id 的呼叫數不對")
      assertEquals(0.04, usage.costUsd ?: error("$id 的成本沒進帳"), 1e-9)
    }
  }

  // ────────────────────────── 2. 成本不得漏記 ──────────────────────────

  /**
   * 兄弟**先**完成（錢已經花掉）、失敗**後**才發生：那筆成本必須進帳。
   *
   * 難點在於「進帳」不是瞬間的：引擎拿到回應後還要查計價才寫入 `callUsages`。
   * 這裡用一個會卡住的 catalog 把那個時間窗撐開（卡到 A 失敗之後），逼出真正的缺陷 ——
   * 舊版 `supervisorScope` + `awaitAll` 在 A 一失敗就結算 usage，兄弟那筆還沒寫進去，
   * 於是報表顯示 $0 而錢照付。改成每層一個 `coroutineScope` 後，scope 回來時子協程必定
   * 全部結束，這個漏帳是被**結構性**消除的，不是靠 sleep 賭時序。
   */
  // Dispatchers.Default 是**載重的**，不是隨手挑的：下面 GatedCatalog.getModel 會**阻塞執行緒**，
  // 單執行緒 dispatcher 下它會先卡死唯一那條執行緒，害另一支 segment 連排程都排不到，時序整個翻掉。
  @Test
  fun `兄弟先完成後才失敗 —— 已花掉的成本不得漏記`(): Unit = runBlocking(Dispatchers.Default) {
    val siblingFailed = CountDownLatch(1)
    val impl = FakeImageGeneration(
      provider = Provider.REPLICATE,
      onPrompt = { prompt ->
        if (prompt == BOOM) {
          delay(150)                 // 讓兄弟先拿到回應
          siblingFailed.countDown()  // 通知：計價可以放行了
          throw IllegalStateException("boom")
        }
      }
    )
    // 兄弟（FAST，立刻回應）寫入用量前必須查計價 —— 卡到 A 失敗後 100ms 才放行。
    // 這是**非 suspend** 的阻塞，故取消打不斷它：兄弟一定會寫入，問題只在引擎有沒有等它。
    val catalog = GatedCatalog(siblingFailed)

    val result = imageEngine(
      FakeImageOrchestrator(Provider.REPLICATE), impl, modelCostService = ModelCostService(catalog)
    ).execute(imagePlan(imageSeg(FAST, "draw"), imageSeg(SLOW, BOOM)))

    assertIs<ExecutionResult.Failed<String>>(result)
    assertEquals(SLOW, result.failedSegment)
    assertEquals(
      0.04, result.metadata.costUsd ?: error("兄弟已完成的 \$0.04 漏記了（costUsd=null）—— 錢花了卻沒進帳"), 1e-9
    )
    // 沒有這條，本測試會靜默退化：閘門若逾時（或計價查詢哪天被快取繞過）就不曾撐開時間窗，
    // costUsd 照樣 0.04 而測試照樣綠 —— 變成一條什麼都沒驗到的空測。
    assertTrue(catalog.gated.get(), "計價閘門沒生效（await 逾時或根本沒被呼叫），本條已無法驗證漏帳")
  }

  // ────────────────────────── 3. 已完成的成果不得被丟棄 ──────────────────────────

  /**
   * 兄弟**先**跑完、失敗**後**才發生：那份已經付過錢的成果必須留在 [ExecutionResult.Failed.partialResults]。
   *
   * 這條連 fail-fast 都適用 —— 政策管的是「還在跑的兄弟要不要取消」，不是「跑完的成果要不要作廢」。
   * 呼叫端拿 partialResults 當 `initialContext` 續跑時，少一筆就是重付一次 API 費用。
   */
  @Test
  fun `兄弟先完成後才失敗 —— 已跑完的成果仍留在 partialResults`(): Unit = runBlocking {
    val impl = FakeImageGeneration(onPrompt = { prompt ->
      if (prompt == BOOM) {
        delay(150)   // 讓兄弟先跑完（含寫回成果），失敗才發生
        throw IllegalStateException("boom")
      }
    })
    val engine = DefaultExecutionEngine(
      orchestrator = mockk(),
      postProcessors = emptyList(),
      providerImpl = { mockk() },
      imageOrchestrator = FakeImageOrchestrator(Provider.GEMINI),
      imageProviderImpl = { impl },
      failurePolicy = FailurePolicy.CANCEL_SIBLINGS
    )

    val result = engine.execute(imagePlan(imageSeg(FAST, "draw"), imageSeg(SLOW, BOOM)))

    assertIs<ExecutionResult.Failed<String>>(result)
    assertEquals(SLOW, result.failedSegment)
    // 前提檢查：兄弟真的跑完了。少了這條，「兄弟其實沒跑完」也會讓下面的期待落空，
    // 卻報成引擎丟棄成果，指錯病灶。
    assertTrue(impl.finished.contains("draw"), "前提不成立：兄弟沒跑完，本條測不到「成果被丟棄」")
    assertSiblingOutputUsable(result, sibling = FAST, failed = SLOW)
  }

  // ────────────────────────── 4. AWAIT_SIBLINGS 下的取消語意 ──────────────────────────
  // AWAIT_SIBLINGS 走的是全新的一段路（runCatchingCancellable），而 ExecutionEngineCancellationTest
  // 全部走 imageEngine（= 預設政策），故那邊鎖住的契約在這條政策下等於沒測。以下兩條是對應版本。

  /** 呼叫端 `job.cancel()`：取消是呼叫端的意志，不是 segment 失敗 —— 不得多噴一筆假失敗事件。 */
  @Test
  fun `AWAIT_SIBLINGS 外部取消 —— 原樣傳播，不得回報成 segment 失敗`() = runBlocking {
    val listener = RecordingListener()
    val entered = CompletableDeferred<Unit>()
    val engine = engine(
      FailurePolicy.AWAIT_SIBLINGS,
      FakeImageGeneration(delayMs = 3000, entered = entered),
      progressListener = listener
    )

    val job = launch(Dispatchers.Default) { engine.execute(imagePlan(imageSeg(FAST, "draw"))) }
    entered.await()   // 確認 segment 真的跑起來了，否則測到的是「取消早於啟動」
    job.cancelAndJoin()

    assertTrue(job.isCancelled)
    assertTrue(
      listener.failures.isEmpty(),
      "取消不是 segment 失敗，不該觸發 onSegmentFailed，實際收到：${listener.failures.map { it::class.simpleName }}"
    )
    assertTrue(listener.completed.isEmpty(), "被取消的 segment 更不該回報成完成，實際收到：${listener.completed}")
  }

  /**
   * 外來取消（假想某 impl 內部包了 `withTimeout`）在本政策下仍是**一般失敗**：
   * 照樣回 [ExecutionResult.Failed] 並保住帳與已完成成果，而不是讓 ExecutionResult 整個消失。
   */
  @Test
  fun `AWAIT_SIBLINGS 外來的取消 —— 仍回 Failed 並保住兄弟的成果`(): Unit = runBlocking {
    val impl = FakeImageGeneration(
      provider = Provider.REPLICATE,
      onPrompt = { prompt ->
        // 兄弟慢慢做，確認外來取消**不會**波及它（本政策的重點）
        if (prompt == TIMEOUT_MARKER) withTimeout(50) { delay(3000) } else delay(SIBLING_WORK_MS)
      }
    )
    val engine = engine(
      FailurePolicy.AWAIT_SIBLINGS, impl, modelCostService = ModelCostService(PerImageCatalog())
    )

    val result = engine.execute(imagePlan(imageSeg(FAST, TIMEOUT_MARKER), imageSeg(SLOW, "draw")))

    assertIs<ExecutionResult.Failed<String>>(result)
    assertEquals(FAST, result.failedSegment)
    assertTrue(impl.finished.contains("draw"), "外來取消把兄弟一起帶走了 —— 那是 fail-fast 的行為")
    assertEquals(0.04, result.metadata.costUsd ?: error("兄弟的 \$0.04 沒進帳"), 1e-9)
    assertSiblingOutputUsable(result, sibling = SLOW, failed = FAST)
  }

  /**
   * 同層兩支都炸：第一支往外拋，其餘掛成 suppressed。
   *
   * 沒有這條，第二支的例外會**完全消失**：`progressListener` 在四個 production 建構點都沒傳，
   * `logger.error` 與 `Failed.error` 只帶被 rethrow 的那一支。等兄弟跑完卻丟掉它帶回來的壞消息，
   * 與本政策的用意（收齊資訊）自相矛盾。
   */
  @Test
  fun `AWAIT_SIBLINGS 同層兩支都失敗 —— 其餘失敗掛成 suppressed 不得消失`(): Unit = runBlocking {
    val impl = FakeImageGeneration(onPrompt = { prompt -> throw IllegalStateException("boom-$prompt") })

    val result = engine(FailurePolicy.AWAIT_SIBLINGS, impl)
      .execute(imagePlan(imageSeg(FAST, "a"), imageSeg(SLOW, "b")))

    assertIs<ExecutionResult.Failed<String>>(result)
    assertEquals(FAST, result.failedSegment)
    val suppressed = result.error.suppressed.map { assertIs<SegmentExecutionException>(it).segmentId }
    assertEquals(listOf(SLOW), suppressed, "第二支失敗沒有任何出口 —— 維運只會看到一支，容易誤判成單一 provider 問題")
  }

  // ────────────────────────── 5. 失敗歸屬 ──────────────────────────

  /**
   * `itemsProvider` 是**使用者 lambda**（典型內容 `ctx.get<X>(id)`）：dependsOn 寫錯、或續跑時
   * `initialContext` 餵進型別不符的上游值，它就會拋。引擎在「計數已發起呼叫」時就會呼到它，
   * 故這支例外必須與其他 segment 例外走同一條路 —— 否則 [ExecutionResult.Failed.failedSegment]
   * 落成 `unknown`（Failed 最主要的診斷價值消失），且該段在 [ExecutionProgressListener] 的事件流裡
   * 完全不存在（既無 started 也無 failed），監控看不到這次失敗。
   */
  @Test
  fun `itemsProvider 拋例外 —— 仍要歸到該 segment 名下`(): Unit = runBlocking {
    val ITEMS = SegmentId("items")
    val listener = RecordingListener()
    val plan = GenerationPlan(
      planId = "items-boom", name = "Items Boom",
      segments = listOf(
        Segment.ParallelAiSegment<String, TextOutput>(
          id = ITEMS,
          itemsProvider = { ctx -> listOf(ctx.get<TextOutput>(SegmentId("nobody")).text) },
          itemInputBuilder = { item, _ -> TextInput(item) },
          promptBuilder = { input, _ -> (input as TextInput).prompt },
          formatSpec = TEXT_FORMAT_SPEC
        )
      ),
      assembler = { ctx -> ctx.get<ParallelOutput<TextOutput>>(ITEMS).successful.size }
    )

    val result = imageEngine(null, null, progressListener = listener).execute(plan)

    assertIs<ExecutionResult.Failed<Int>>(result)
    assertEquals(ITEMS, result.failedSegment, "使用者 lambda 的例外裸奔了 —— failedSegment 指不出病灶")
    assertEquals(listOf(ITEMS), listener.started, "連 onSegmentStarted 都沒發")
    assertEquals(
      1, listener.failures.size,
      "該段在事件流裡完全不存在，實際收到的失敗事件：${listener.failures.map { it::class.simpleName }}"
    )
  }

  // ────────────────────────── 假件 ──────────────────────────

  /** 兄弟被取消時該做完的工時；取消生效的話整體耗時會遠低於它。 */
  private val SIBLING_WORK_MS = 400L

  /** 觸發「假想的 impl 內部逾時」→ 外來 CancellationException 的 prompt 標記。 */
  private val TIMEOUT_MARKER = "please-time-out"

  /**
   * partialResults 是 Task 4 續跑的本錢，故要驗的是「能不能直接沿用」，分兩面：
   * - [sibling]（已跑完）的產出要**有內容**；空的 [ImageOutput] 也能通過型別斷言，續跑時卻是廢的。
   * - [failed]（炸掉那支）**不得**留下半成品：續跑若把它當成已完成而跳過，就是靜默少一段。
   */
  private fun assertSiblingOutputUsable(
    result: ExecutionResult.Failed<String>, sibling: SegmentId, failed: SegmentId
  ) {
    val output = assertIs<ImageOutput>(result.partialResults[sibling], "已跑完（錢已付）的成果被丟棄了 —— 續跑時得重付一次")
    assertEquals(1, output.images.size, "成果留下了但是空的，續跑時沿用不了")
    assertFalse(result.partialResults.containsKey(failed), "失敗的 segment 不得在 partialResults 留下半成品")
  }

  /** [BOOM] 立刻炸；其餘 prompt 慢慢做（模擬還在燒錢的兄弟）。 */
  private val boomOrSlowWork: suspend (String) -> Unit = { prompt ->
    if (prompt == BOOM) {
      delay(50)  // 讓兄弟確實開跑，否則測到的是「根本沒啟動」而非「被取消」
      throw IllegalStateException("boom")
    } else {
      delay(SIBLING_WORK_MS)
    }
  }

  private fun assertSiblingCancelled(impl: FakeImageGeneration, elapsedMs: Long, metadata: ExecutionMetadata) {
    assertTrue(impl.prompts.contains("draw"), "兄弟根本沒開跑，這條測不到取消（測到的是排程順序）")
    assertFalse(impl.finished.contains("draw"), "兄弟不該跑完 —— 錢白花了")
    assertTrue(elapsedMs < SIBLING_WORK_MS / 2, "不該等兄弟做完才回；實際耗時 ${elapsedMs}ms")
    // 兩支請求都真的送出去了（見上面的 prompts 斷言），故計數必須是 2 ——
    // 計數與 costUsd 得用同一口徑，否則同一次失敗會有兩種說法。
    assertEquals(2, metadata.totalImageCalls, "已發起的生圖呼叫是 2 次（都可能被計費），計數不得抹掉失敗與被取消者")
  }

  /** 只認 [Provider.REPLICATE] 的假 catalog：每張圖 $0.04（成本斷言的基準單價）。 */
  private open class PerImageCatalog : IModelCatalog {
    private val pricing = ModelPricing(
      input = 0.0, output = 0.0,
      image = ImagePricing.PerImage(listOf(ImagePricing.PerImage.Entry(ImageSpec(), 0.04)))
    )
    private val models = mapOf(Provider.REPLICATE to mapOf(FAKE_IMAGE_MODEL to ModelInfo(FAKE_IMAGE_MODEL, pricing)))

    override fun allModels() = models

    override fun getModel(provider: Provider, model: String): ModelInfo? = models[provider]?.get(model)
  }

  /** 同上，但查價會卡到 [gate] 放行 —— 用來把「拿到回應」與「用量寫入」之間的時間窗撐開。 */
  private class GatedCatalog(private val gate: CountDownLatch) : PerImageCatalog() {

    /** 閘門是否真的把查價擋住過（等到放行、而非逾時或未被呼叫）—— 測試據此確認時間窗有撐開。 */
    val gated = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun getModel(provider: Provider, model: String): ModelInfo? {
      if (gate.await(3, TimeUnit.SECONDS)) gated.set(true)
      Thread.sleep(100)   // 拉開時間窗：舊碼此刻早已結算完 usage
      return super.getModel(provider, model)
    }
  }
}
