/**
 * Created by smallufo on 2026-07-25.
 *
 * DefaultExecutionEngine 的 image segment 能力測試 —— 全部走假 impl / 假 orchestrator，不打真 API。
 */
package destiny.tools.workflow

import destiny.tools.ai.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutionEngineImageTest {

  data class StringOutput(val value: String) : SegmentOutput

  private val SCENE = SegmentId("scene")
  private val PAINT = SegmentId("paint")

  // 假件（FakeImageGeneration / FakeImageOrchestrator / imageEngine）見 WorkflowTestFakes.kt
  private val stubPng = STUB_PNG
  private val fakeModel = FAKE_IMAGE_MODEL

  /** 「文字 → 圖」：ImageSegment 的 prompt 由上游 segment 的輸出組成。 */
  private fun textThenImagePlan(options: ImageOptions = ImageOptions()) = GenerationPlan(
    planId = "text-to-image",
    name = "Text to Image",
    segments = listOf(
      Segment.StaticSegment(id = SCENE, content = StringOutput("a lone crane over misty river")),
      Segment.ImageSegment(
        id = PAINT,
        dependsOn = setOf(SCENE),
        promptBuilder = { ctx -> "ink wash painting of ${ctx.get<StringOutput>(SCENE).value}" },
        options = options
      )
    ),
    assembler = { ctx -> ctx.get<ImageOutput>(PAINT) }
  )

  private val READING = SegmentId("reading")

  /** 八字意境圖的骨架：靜態命盤 → AI 讀盤（token 計價）→ 生圖（per-image 計價）。 */
  private fun readingThenImagePlan() = GenerationPlan(
    planId = "reading-to-image", name = "Reading to Image",
    segments = listOf(
      Segment.StaticSegment(id = SCENE, content = StringOutput("甲子 乙丑 丙寅 丁卯")),
      Segment.AiSegment(
        id = READING, dependsOn = setOf(SCENE),
        inputBuilder = { ctx -> TextInput(ctx.get<StringOutput>(SCENE).value) },
        promptBuilder = { input, _ -> "讀盤：${(input as TextInput).prompt}" },
        formatSpec = TEXT_FORMAT_SPEC
      ),
      Segment.ImageSegment(
        id = PAINT, dependsOn = setOf(READING),
        promptBuilder = { ctx -> "ink wash of ${ctx.get<TextOutput>(READING).text}" }
      )
    ),
    assembler = { ctx -> ctx.get<ImageOutput>(PAINT) }
  )

  @Test
  fun `文字 segment 接 ImageSegment —— 產出 ImageOutput`() = runBlocking {
    val impl = FakeImageGeneration(Provider.GEMINI, fakeModel, stubPng)
    val result = imageEngine(FakeImageOrchestrator(Provider.GEMINI), impl).execute(textThenImagePlan())

    assertIs<ExecutionResult.Success<ImageOutput>>(result)
    assertEquals(1, result.result.images.size)
    assertEquals("image/png", result.result.images.first().mimeType)

    // prompt 確實由上游輸出組成
    assertEquals(listOf("ink wash painting of a lone crane over misty river"), impl.prompts)

    // 生圖與文字呼叫分開計數
    assertEquals(0, result.metadata.totalAiCalls)
    assertEquals(1, result.metadata.totalImageCalls)
  }

  @Test
  fun `ImageOptions 的張數會傳到 impl，多張圖全數回傳`() = runBlocking {
    val impl = FakeImageGeneration(Provider.GEMINI, fakeModel, stubPng)
    val result = imageEngine(FakeImageOrchestrator(Provider.GEMINI), impl)
      .execute(textThenImagePlan(ImageOptions(n = 3, aspectRatio = AspectRatio.PORTRAIT_3_4)))

    assertIs<ExecutionResult.Success<ImageOutput>>(result)
    assertEquals(3, result.result.images.size)
    // 一次呼叫產 3 張 —— totalImageCalls 記的是「呼叫次數」而非張數
    assertEquals(1, result.metadata.totalImageCalls)
  }

  @Test
  fun `plan 含 ImageSegment 但引擎未注入 imageOrchestrator —— Failed 且指出該 segment`() = runBlocking {
    val result = imageEngine(null, null).execute(textThenImagePlan())

    assertIs<ExecutionResult.Failed<ImageOutput>>(result)
    assertEquals(PAINT, result.failedSegment)
    assertIs<SegmentExecutionException>(result.error)
    assertIs<IllegalStateException>(result.error.cause)

    // 前段已成功的輸出仍要保留
    assertEquals(StringOutput("a lone crane over misty river"), result.partialResults[SCENE])
    assertNull(result.partialResults[PAINT])
  }

  @Test
  fun `有 orchestrator 但未注入 imageProviderImpl —— 同樣 Failed`() = runBlocking {
    val result = imageEngine(FakeImageOrchestrator(Provider.GEMINI), null).execute(textThenImagePlan())

    assertIs<ExecutionResult.Failed<ImageOutput>>(result)
    assertEquals(PAINT, result.failedSegment)
  }

  /** orchestrator 已用盡 retry / failover 仍失敗（回 null）→ 整個 plan Failed，前段輸出保留。 */
  @Test
  fun `orchestrator 回 null —— Failed 且 partialResults 帶前段輸出`() = runBlocking {
    val orchestrator = object : IImageOrchestrator {
      override suspend fun generateImage(
        prompt: String,
        options: ImageOptions,
        providerImpl: (Provider) -> IImageGeneration?
      ): Reply.Normal<List<GeneratedImage>>? = null
    }
    val result = imageEngine(orchestrator, FakeImageGeneration(Provider.GEMINI, fakeModel, stubPng)).execute(textThenImagePlan())

    assertIs<ExecutionResult.Failed<ImageOutput>>(result)
    assertEquals(PAINT, result.failedSegment)
    assertEquals(StringOutput("a lone crane over misty river"), result.partialResults[SCENE])
  }

  /** provider 在我方 stack 無法生圖（resolver 回 null）—— 假 orchestrator 據此回 null，視同全數失敗。 */
  @Test
  fun `resolver 對該 provider 回 null —— Failed`() = runBlocking {
    // orchestrator 找 REPLICATE，但注入的 impl 是 GEMINI → resolver 回 null
    val result = imageEngine(FakeImageOrchestrator(Provider.REPLICATE), FakeImageGeneration(Provider.GEMINI, fakeModel, stubPng))
      .execute(textThenImagePlan())

    assertIs<ExecutionResult.Failed<ImageOutput>>(result)
    assertEquals(PAINT, result.failedSegment)
  }

  // ────────────────────────── 成本 accounting ──────────────────────────

  /** 只回答本測試用得到的 (provider, model)。 */
  private class FakeCatalog(private val models: Map<Provider, Map<String, ModelInfo>>) : IModelCatalog {
    override fun allModels(): Map<Provider, Map<String, ModelInfo>> = models
  }

  private fun costService(provider: Provider, model: String, pricing: ModelPricing) =
    ModelCostService(FakeCatalog(mapOf(provider to mapOf(model to ModelInfo(model, pricing)))))

  /** per-image 計價（Replicate/FLUX 型）：回應無 token → 走 ImagePricing.estimate × 張數。 */
  @Test
  fun `無 token 的生圖 —— 成本 = 每張價 × 張數`() = runBlocking {
    val pricing = ModelPricing(
      input = 0.0, output = 0.0,
      image = ImagePricing.PerImage(listOf(ImagePricing.PerImage.Entry(ImageSpec(), 0.04)))
    )
    val impl = FakeImageGeneration(Provider.REPLICATE, fakeModel, stubPng)   // token 全 null
    val result = imageEngine(
      FakeImageOrchestrator(Provider.REPLICATE), impl,
      costService(Provider.REPLICATE, fakeModel, pricing)
    ).execute(textThenImagePlan(ImageOptions(n = 2)))

    assertIs<ExecutionResult.Success<ImageOutput>>(result)
    assertEquals(0.08, result.metadata.costUsd!!, 1e-9)
    // 無 token 的 provider 不得污染 token 統計
    assertNull(result.metadata.tokenUsage?.inputTokens)
    assertNull(result.metadata.tokenUsage?.outputTokens)
    assertEquals(listOf(UsedModel(Provider.REPLICATE.name, fakeModel)), result.metadata.modelsUsed)
  }

  /** chat-native 生圖（Gemini 型）：回應帶真實 token → 走 token 精算，不用 estimate。 */
  @Test
  fun `有 token 的生圖 —— 走 token 精算而非每張估價`() = runBlocking {
    val pricing = ModelPricing(
      input = 0.3, output = 30.0,
      // 故意放一個「錯得離譜」的 per-image 價：若誤走估價路徑，數字會差很多
      image = ImagePricing.PerImage(listOf(ImagePricing.PerImage.Entry(ImageSpec(), 9.99)))
    )
    val impl = FakeImageGeneration(Provider.GEMINI, fakeModel, stubPng, inputTokens = 100, outputTokens = 1290)
    val result = imageEngine(
      FakeImageOrchestrator(Provider.GEMINI), impl,
      costService(Provider.GEMINI, fakeModel, pricing)
    ).execute(textThenImagePlan())

    assertIs<ExecutionResult.Success<ImageOutput>>(result)
    // 100 × 0.3/1M + 1290 × 30/1M
    assertEquals(0.03873, result.metadata.costUsd!!, 1e-9)
    // 真實 token 有被計入（與文字 segment 共用同一組 TokenUsage —— 見 ExecutionMetadata KDoc）
    assertEquals(100, result.metadata.tokenUsage?.inputTokens)
    assertEquals(1290, result.metadata.tokenUsage?.outputTokens)
  }

  @Test
  fun `未注入 ModelCostService —— 成本為 null 但不阻斷`() = runBlocking {
    val result = imageEngine(FakeImageOrchestrator(Provider.GEMINI), FakeImageGeneration(Provider.GEMINI, fakeModel, stubPng))
      .execute(textThenImagePlan())

    assertIs<ExecutionResult.Success<ImageOutput>>(result)
    assertNull(result.metadata.costUsd)
    assertEquals(1, result.metadata.totalImageCalls)
  }

  /** catalog 查不到該 model 的 image 計價 → cost 留 null，不影響出圖。 */
  @Test
  fun `catalog 無此 model 的 image 計價 —— 成本 null 但仍成功`() = runBlocking {
    val noImagePricing = ModelPricing(input = 0.0, output = 0.0)   // image = null
    val result = imageEngine(
      FakeImageOrchestrator(Provider.REPLICATE), FakeImageGeneration(Provider.REPLICATE, fakeModel, stubPng),
      costService(Provider.REPLICATE, fakeModel, noImagePricing)
    ).execute(textThenImagePlan())

    assertIs<ExecutionResult.Success<ImageOutput>>(result)
    assertEquals(1, result.result.images.size)
    assertNull(result.metadata.costUsd)
  }

  // ────────────────────────── per-segment 用量 ──────────────────────────

  /**
   * 讀盤（token 計價）+ 生圖（per-image 計價）混編：兩段的錢必須分得開。
   *
   * 這是產品端要顯示「讀盤 $0.032 / 生圖 $0.04」的依據 —— 全計畫總額做不到，實跑八字意境圖時
   * 只能去 ClaudeImpl 的 log 撈 usage 再相減。token 數與單價刻意取自那次實跑（Opus $5/$25、
   * 1821/924 → $0.032205）。
   */
  @Test
  fun `segmentUsages —— 讀盤與生圖各自的成本分得開，加總等於總額`(): Unit = runBlocking {
    val chat = FakeChatOrchestrator(Provider.CLAUDE, FAKE_TEXT_MODEL, inputTokens = 1821, outputTokens = 924)
    val impl = FakeImageGeneration(Provider.REPLICATE, fakeModel, stubPng)
    val catalog = FakeCatalog(
      mapOf(
        Provider.CLAUDE to mapOf(FAKE_TEXT_MODEL to ModelInfo(FAKE_TEXT_MODEL, ModelPricing(input = 5.0, output = 25.0))),
        Provider.REPLICATE to mapOf(
          fakeModel to ModelInfo(
            fakeModel,
            ModelPricing(
              input = 0.0, output = 0.0,
              image = ImagePricing.PerImage(listOf(ImagePricing.PerImage.Entry(ImageSpec(), 0.04)))
            )
          )
        )
      )
    )

    val result = imageEngine(
      FakeImageOrchestrator(Provider.REPLICATE), impl, ModelCostService(catalog), chatOrchestrator = chat
    ).execute(readingThenImagePlan())

    assertIs<ExecutionResult.Success<ImageOutput>>(result)

    // StaticSegment 沒發出任何呼叫 → 不得出現（否則產品端會看到一堆 0 元的空段）
    assertEquals(setOf(READING, PAINT), result.metadata.segmentUsages.keys)

    val reading = result.metadata.segmentUsages.getValue(READING)
    assertEquals(1, reading.calls)
    assertEquals(0.032205, reading.costUsd!!, 1e-9)
    assertEquals(1821, reading.tokenUsage?.inputTokens)
    assertEquals(listOf(UsedModel(Provider.CLAUDE.name, FAKE_TEXT_MODEL)), reading.modelsUsed)

    val paint = result.metadata.segmentUsages.getValue(PAINT)
    assertEquals(1, paint.calls)
    assertEquals(0.04, paint.costUsd!!, 1e-9)
    assertEquals(listOf(UsedModel(Provider.REPLICATE.name, fakeModel)), paint.modelsUsed)
    // per-image 計價的段沒有 token 可報，不得被讀盤那段的數字污染
    assertNull(paint.tokenUsage?.inputTokens)

    assertSegmentUsagesSumToTotal(result.metadata)
  }

  /** `ParallelAiSegment` 的 [SegmentUsage.calls] 是該段所有 item 的合計 —— 一段不等於一次呼叫。 */
  @Test
  fun `segmentUsages —— ParallelAiSegment 的 calls 為 item 數合計`(): Unit = runBlocking {
    val chat = FakeChatOrchestrator(inputTokens = 10, outputTokens = 20)
    val ITEMS = SegmentId("items")
    val plan = GenerationPlan(
      planId = "parallel-usage", name = "Parallel Usage",
      segments = listOf(
        Segment.ParallelAiSegment<String, TextOutput>(
          id = ITEMS,
          itemsProvider = { listOf("a", "b", "c") },
          itemInputBuilder = { item, _ -> TextInput(item) },
          promptBuilder = { input, _ -> (input as TextInput).prompt },
          formatSpec = TEXT_FORMAT_SPEC
        )
      ),
      assembler = { ctx -> ctx.get<ParallelOutput<TextOutput>>(ITEMS).successful.size }
    )

    val result = imageEngine(null, null, chatOrchestrator = chat).execute(plan)

    assertIs<ExecutionResult.Success<Int>>(result)
    val usage = result.metadata.segmentUsages.getValue(ITEMS)
    assertEquals(3, usage.calls)
    assertEquals(30, usage.tokenUsage?.inputTokens)   // 三個 item 各 10
    assertSegmentUsagesSumToTotal(result.metadata)
  }

  /**
   * 分段加總 == 總額：本欄位存在的唯一理由就是「拆得開又對得上」，加不回去等於在報表上憑空生錢／漏錢。
   * calls 與 token 一起驗，避免只有金額對得上（例如某段被漏掉但剛好成本 null）。
   *
   * 只適用於**全成功**的 plan：失敗／被取消的呼叫沒有用量可記，[SegmentUsage.calls]（有回應者）
   * 與 `total*Calls`（已發起）本來就不同口徑。
   */
  private fun assertSegmentUsagesSumToTotal(metadata: ExecutionMetadata) {
    val usages = metadata.segmentUsages.values
    assertEquals(
      metadata.costUsd ?: 0.0, usages.sumOf { it.costUsd ?: 0.0 }, 1e-9,
      "分段成本加總 ≠ 總成本：${metadata.segmentUsages}"
    )
    assertEquals(
      metadata.tokenUsage?.inputTokens ?: 0, usages.sumOf { it.tokenUsage?.inputTokens ?: 0 },
      "分段 input token 加總 ≠ 總量"
    )
    assertEquals(
      metadata.tokenUsage?.outputTokens ?: 0, usages.sumOf { it.tokenUsage?.outputTokens ?: 0 },
      "分段 output token 加總 ≠ 總量"
    )
    assertEquals(
      metadata.totalAiCalls + metadata.totalImageCalls, usages.sumOf { it.calls },
      "分段呼叫數加總 ≠ 總呼叫數 —— 有段落漏掉或重複計入"
    )
  }

  // ────────────────────────── 多段 / 並行 ──────────────────────────

  /** 一段文字 → 同層兩個不同風格的 ImageSegment：兩張圖都要在，計數與成本都要對。 */
  @Test
  fun `一段文字接兩個並行 ImageSegment —— 兩張圖俱在且計數正確`() = runBlocking {
    val INK = SegmentId("ink-wash")
    val OIL = SegmentId("oil-painting")

    val plan = GenerationPlan(
      planId = "one-scene-two-styles",
      name = "One Scene, Two Styles",
      segments = listOf(
        Segment.StaticSegment(id = SCENE, content = StringOutput("moonlit bamboo grove")),
        Segment.ImageSegment(
          id = INK, dependsOn = setOf(SCENE),
          promptBuilder = { ctx -> "ink wash painting of ${ctx.get<StringOutput>(SCENE).value}" }
        ),
        Segment.ImageSegment(
          id = OIL, dependsOn = setOf(SCENE),
          promptBuilder = { ctx -> "oil painting of ${ctx.get<StringOutput>(SCENE).value}" }
        )
      ),
      assembler = { ctx -> ctx.get<ImageOutput>(INK).images + ctx.get<ImageOutput>(OIL).images }
    )

    val pricing = ModelPricing(
      input = 0.0, output = 0.0,
      image = ImagePricing.PerImage(listOf(ImagePricing.PerImage.Entry(ImageSpec(), 0.04)))
    )
    val impl = FakeImageGeneration(Provider.REPLICATE, fakeModel, stubPng)
    val result = imageEngine(
      FakeImageOrchestrator(Provider.REPLICATE), impl,
      costService(Provider.REPLICATE, fakeModel, pricing)
    ).execute(plan)

    assertIs<ExecutionResult.Success<List<GeneratedImage>>>(result)
    assertEquals(2, result.result.size)

    // 兩支 prompt 都送出去了（同層並行，順序不保證）
    assertEquals(
      setOf("ink wash painting of moonlit bamboo grove", "oil painting of moonlit bamboo grove"),
      impl.prompts.toSet()
    )

    assertEquals(2, result.metadata.totalImageCalls)
    assertEquals(0, result.metadata.totalAiCalls)
    assertEquals(1, result.metadata.parallelExecutions)          // 第二層兩個 segment 並行
    assertEquals(0.08, result.metadata.costUsd!!, 1e-9)          // 兩次呼叫各一張，成本加總
    assertEquals(3, result.metadata.segmentDurations.size)
  }

  /** 三層 pipeline：靜態 → 生圖 → 拿圖做後續計算（下游讀得到 ImageOutput）。 */
  @Test
  fun `ImageOutput 可被下游 ComputeSegment 取用`() = runBlocking {
    val REPORT = SegmentId("report")
    val plan = GenerationPlan(
      planId = "text-image-compute",
      name = "Text → Image → Compute",
      segments = listOf(
        Segment.StaticSegment(id = SCENE, content = StringOutput("misty river")),
        Segment.ImageSegment(
          id = PAINT, dependsOn = setOf(SCENE),
          promptBuilder = { ctx -> ctx.get<StringOutput>(SCENE).value },
          options = ImageOptions(n = 2)
        ),
        Segment.ComputeSegment(
          id = REPORT, dependsOn = setOf(PAINT),
          compute = { ctx -> StringOutput("generated ${ctx.get<ImageOutput>(PAINT).images.size} images") }
        )
      ),
      assembler = { ctx -> ctx.get<StringOutput>(REPORT).value }
    )

    val result = imageEngine(FakeImageOrchestrator(Provider.GEMINI), FakeImageGeneration(Provider.GEMINI, fakeModel, stubPng))
      .execute(plan)

    assertIs<ExecutionResult.Success<String>>(result)
    assertEquals("generated 2 images", result.result)
    assertEquals(3, plan.executionLayers().size)
  }

  /** 既有 text-only 行為不得被 image 參數影響：不給 image 依賴時，純文字 plan 照跑。 */
  @Test
  fun `未注入 image 依賴時 text-only plan 照常成功`() = runBlocking {
    val plan = GenerationPlan(
      planId = "text-only",
      name = "Text Only",
      segments = listOf(
        Segment.StaticSegment(id = SCENE, content = StringOutput("hello")),
        Segment.ComputeSegment(
          id = PAINT,
          dependsOn = setOf(SCENE),
          compute = { ctx -> StringOutput(ctx.get<StringOutput>(SCENE).value + " world") }
        )
      ),
      assembler = { ctx -> ctx.get<StringOutput>(PAINT).value }
    )

    val result = imageEngine(null, null).execute(plan)

    assertIs<ExecutionResult.Success<String>>(result)
    assertEquals("hello world", result.result)
    assertEquals(0, result.metadata.totalImageCalls)
    assertNull(result.metadata.tokenUsage)
    assertTrue(result.metadata.modelsUsed.isEmpty())
  }
}
