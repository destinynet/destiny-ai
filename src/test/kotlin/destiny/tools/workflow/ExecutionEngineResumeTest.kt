/**
 * Created by smallufo on 2026-07-25.
 *
 * 續跑語意測試 —— 全部走假件（見 WorkflowTestFakes.kt），不打真 API。
 *
 * 鎖住的契約：`initialContext` 內已存在的 segment **不執行**，直接沿用其值。
 * 這條與「已跑完的成果一律留在 [ExecutionResult.Failed.partialResults]」是一組的：
 * 前者讓成果**看得到**，這裡讓它**用得到** —— 否則把 partialResults 餵回去續跑，
 * 那些已付過錢的前段（一次 Opus 讀盤 $0.03、一張圖 $0.04）會再被重打一次。
 */
package destiny.tools.workflow

import destiny.tools.ai.*
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExecutionEngineResumeTest {

  data class StringOutput(val value: String) : SegmentOutput

  private val SCENE = SegmentId("scene")
  private val INK = SegmentId("ink")
  private val OIL = SegmentId("oil")

  /** 種進 initialContext 的既有成果；base64 帶記號，用來確認 assembler 拿到的就是它（而非重跑產物）。 */
  private val SEEDED = ImageOutput(listOf(GeneratedImage("image/png", "SEEDED-$STUB_PNG")))

  // SegmentId 是 value class（不得當 vararg 型別），故以 List 傳遞
  private fun seeded(ids: List<SegmentId>): Map<SegmentId, SegmentOutput> = ids.associateWith { SEEDED }

  /** 靜態上游 → 同層數個 ImageSegment（prompt 前綴即 segment id，方便辨認是誰開跑）。 */
  private fun plan(imageIds: List<SegmentId>) = GenerationPlan(
    planId = "resume", name = "Resume",
    segments = listOf(Segment.StaticSegment(id = SCENE, content = StringOutput("misty river"))) +
      imageIds.map { id ->
        Segment.ImageSegment(
          id = id, dependsOn = setOf(SCENE),
          promptBuilder = { ctx -> "${id.value} of ${ctx.get<StringOutput>(SCENE).value}" }
        )
      },
    assembler = { ctx -> imageIds.map { ctx.get<ImageOutput>(it) } }
  )

  private fun engine(impl: FakeImageGeneration) = imageEngine(FakeImageOrchestrator(impl.provider), impl)

  // ────────────────────────── 1. 已在 context 內 ⇒ 不重打 API ──────────────────────────

  /**
   * 最基本的一條：`initialContext` 已有該 segment ⇒ 那次生圖呼叫**根本不該發生**。
   *
   * 四個面向都要對，缺一即代表「跳過」只做了一半：沒打 API、沿用的值真的傳給 assembler、
   * 沒計入呼叫次數、也沒混進 segmentDurations（跳過不是「跑了 0 毫秒」）。
   */
  @Test
  fun `initialContext 已有該 segment —— 不重打 API 且直接沿用其值`(): Unit = runBlocking {
    val impl = FakeImageGeneration()

    val result = engine(impl).execute(plan(listOf(INK)), initialContext = seeded(listOf(INK)))

    assertIs<ExecutionResult.Success<List<ImageOutput>>>(result)
    assertEquals(0, impl.callCount, "已完成的 segment 又打了一次 API —— 續跑的錢白付了：${impl.prompts}")
    assertEquals(listOf(SEEDED), result.result, "assembler 拿到的不是 initialContext 沿用的那個值")
    assertEquals(0, result.metadata.totalImageCalls, "跳過就是沒發起呼叫，不得計入")
    assertFalse(INK in result.metadata.segmentDurations, "被跳過的 segment 不該有耗時紀錄")
    // 反面對照：沒被種入的 SCENE 照跑。少了這條，「整個 plan 其實沒執行」也會讓上面全綠。
    assertTrue(SCENE in result.metadata.segmentDurations, "未種入的 segment 竟也沒跑 —— 本條測到的不是「跳過」")
  }

  // ────────────────────────── 2. 整層被跳過 ⇒ 不算並行 ──────────────────────────

  /**
   * 同層兩支都已完成：一支都沒開跑，[ExecutionMetadata.parallelExecutions] 不得計入 ——
   * 那個數字的語意是「真的並行執行過幾次」，不是「plan 裡有幾個寬層」。
   */
  @Test
  fun `整層都已完成 —— parallelExecutions 不計入`(): Unit = runBlocking {
    val impl = FakeImageGeneration()

    val result = engine(impl).execute(plan(listOf(INK, OIL)), initialContext = seeded(listOf(INK, OIL)))

    assertIs<ExecutionResult.Success<List<ImageOutput>>>(result)
    assertEquals(0, impl.callCount, "整層都已完成卻仍開跑：${impl.prompts}")
    assertEquals(0, result.metadata.parallelExecutions, "整層被跳過，沒有任何並行發生過")
    assertEquals(setOf(SCENE), result.metadata.segmentDurations.keys)
  }

  // ────────────────────────── 3. 部分完成的層 ⇒ 只跑缺的那支 ──────────────────────────

  /**
   * 這條也是 `parallelExecutions` 唯一測得到「以 pending 計數」的地方：整層被跳過時（上一條）
   * 迴圈早在計數之前就 `continue` 了，故那裡改用 `layer.size` 判斷照樣是 0 —— 缺陷只在
   * 「兩支裡跳過一支」時才顯形（實際並行度 1，卻會被記成一次並行）。
   */
  @Test
  fun `同層只有一支已完成 —— 另一支照跑`(): Unit = runBlocking {
    val impl = FakeImageGeneration()

    val result = engine(impl).execute(plan(listOf(INK, OIL)), initialContext = seeded(listOf(INK)))

    assertIs<ExecutionResult.Success<List<ImageOutput>>>(result)
    assertEquals(listOf("oil of misty river"), impl.prompts, "該跑的是缺的那支（oil），且只有它")
    assertEquals(1, result.metadata.totalImageCalls)
    assertEquals(SEEDED, result.result[0], "已完成那支要沿用舊值")
    // 釘住內容而非張數：`SEEDED` 的 base64 帶前綴，故「其實兩支都沿用舊值」在這裡才分得出來。
    assertEquals(listOf(GeneratedImage("image/png", STUB_PNG)), result.result[1].images, "新跑那支要有真正的產出")
    assertEquals(setOf(SCENE, OIL), result.metadata.segmentDurations.keys)
    assertEquals(0, result.metadata.parallelExecutions, "只剩一支要跑，並沒有並行發生")
  }

  // ────────────────────────── 4. 真實續跑：Failed.partialResults 直接餵回 ──────────────────────────

  /**
   * 本 task 的目的本身：**先失敗、再續跑，昂貴的前段不重付錢**。
   *
   * 三層 plan（靜態 → 上游生圖 → 下游生圖），第一輪讓下游炸掉；把 `Failed.partialResults`
   * 原封不動當第二輪的 `initialContext`。兩輪共用同一個假 impl 實例，故「上游有沒有被重打」
   * 直接看它收到幾次上游 prompt 即可 —— 這正是舊碼漏掉的錢（實測 PROBE2 calls=1）。
   */
  private val UPSTREAM = SegmentId("upstream")   // 昂貴的前段（想像成 Opus 讀盤 / 首張圖）
  private val DOWNSTREAM = SegmentId("downstream")

  /** 下游 segment 會送出的 prompt —— 假 impl 據此決定要不要炸。 */
  private val downstreamPrompt = "downstream after 1 image"

  /** 三層 plan：靜態 → 上游生圖 → 下游生圖。 */
  private fun chainPlan() = GenerationPlan(
    planId = "resume-real", name = "Resume Real",
    segments = listOf(
      Segment.StaticSegment(id = SCENE, content = StringOutput("misty river")),
      Segment.ImageSegment(
        id = UPSTREAM, dependsOn = setOf(SCENE),
        promptBuilder = { ctx -> "upstream of ${ctx.get<StringOutput>(SCENE).value}" }
      ),
      Segment.ImageSegment(
        id = DOWNSTREAM, dependsOn = setOf(UPSTREAM),
        promptBuilder = { ctx -> "downstream after ${ctx.get<ImageOutput>(UPSTREAM).images.size} image" }
      )
    ),
    assembler = { ctx -> ctx.get<ImageOutput>(DOWNSTREAM) }
  )

  @Test
  fun `拿 Failed 的 partialResults 續跑 —— 上游不重打，第二輪成功`(): Unit = runBlocking {
    val failDownstream = AtomicBoolean(true)

    val impl = FakeImageGeneration(onPrompt = { prompt ->
      if (prompt == downstreamPrompt && failDownstream.get()) throw IllegalStateException("boom")
    })
    val plan = chainPlan()
    val engine = engine(impl)

    val first = engine.execute(plan)

    assertIs<ExecutionResult.Failed<ImageOutput>>(first)
    assertEquals(DOWNSTREAM, first.failedSegment)
    // 前提檢查：上游真的跑完並留在 partialResults，否則第二輪跳過什麼都無從談起。
    assertIs<ImageOutput>(first.partialResults[UPSTREAM], "上游成果沒留在 partialResults —— 續跑無本錢可用")
    assertEquals(1, impl.prompts.count { it.startsWith("upstream") })

    // ── 第二輪：這次下游不炸，partialResults 原封不動餵回去 ──
    failDownstream.set(false)
    val second = engine.execute(plan, initialContext = first.partialResults)

    assertIs<ExecutionResult.Success<ImageOutput>>(second)
    assertEquals(1, second.result.images.size, "第二輪要拿到完整結果")
    assertEquals(
      1, impl.prompts.count { it.startsWith("upstream") },
      "上游被重打了 —— 續跑卻重付前段的錢，這正是本 task 要修的缺陷。全部 prompt：${impl.prompts}"
    )
    assertEquals(1, second.metadata.totalImageCalls, "第二輪只該發起下游那一次生圖")
    assertFalse(UPSTREAM in second.metadata.segmentDurations, "被跳過的上游不該有耗時紀錄")
  }

  /**
   * 續跑鏈的第二段：第二輪**又**失敗時，第一輪沿用的成果仍要留在 partialResults ——
   * 否則第三輪就得重付上游的錢，續跑只能接一次。
   *
   * 它依賴一個很細的耦合：`initialContext` 先寫進 context，而 `partialResults` 取自 `context.toMap()`。
   * 若哪天改成從 `completedOutputs`（只裝「本輪真的跑出來的東西」）建，其餘測試全綠而這條會紅。
   */
  @Test
  fun `第二輪又失敗 —— 第一輪沿用的成果仍留在 partialResults`(): Unit = runBlocking {
    val impl = FakeImageGeneration(onPrompt = { prompt ->
      if (prompt == downstreamPrompt) throw IllegalStateException("boom")
    })
    val plan = chainPlan()
    val engine = engine(impl)

    val first = engine.execute(plan)
    assertIs<ExecutionResult.Failed<ImageOutput>>(first)
    val upstreamOutput = assertIs<ImageOutput>(first.partialResults[UPSTREAM], "前提不成立：第一輪上游沒留下成果")

    val second = engine.execute(plan, initialContext = first.partialResults)

    assertIs<ExecutionResult.Failed<ImageOutput>>(second)
    assertEquals(DOWNSTREAM, second.failedSegment)
    assertEquals(
      upstreamOutput, second.partialResults[UPSTREAM],
      "沿用的上游成果沒進第二輪的 partialResults —— 第三輪會重付它的錢，續跑就只能接一次"
    )
    assertEquals(
      1, impl.prompts.count { it.startsWith("upstream") },
      "上游被重打了。全部 prompt：${impl.prompts}"
    )
  }

  // ────────────────────────── 5. 文字段：續跑的動機那筆錢其實在這裡 ──────────────────────────

  /**
   * 前四條全是 `ImageSegment`，但動機故事裡那 $0.032 是 Opus 讀盤（`AiSegment`）。
   *
   * `ParallelAiSegment` 另有一條隱含契約值得一併釘住：跳過時連 `itemsProvider` 都不該被呼叫 ——
   * 它是「已發起」計數的來源，被叫到就代表那一段其實走進了 runSegment。
   */
  @Test
  fun `已完成的 AI 段 —— 不重打，且不計入 totalAiCalls`(): Unit = runBlocking {
    val READING = SegmentId("reading")
    val ITEMS = SegmentId("items")
    val itemsAsked = AtomicBoolean(false)
    val chat = FakeChatOrchestrator(inputTokens = 1821, outputTokens = 924)

    val plan = GenerationPlan(
      planId = "resume-ai", name = "Resume Ai",
      segments = listOf(
        Segment.AiSegment(
          id = READING,
          inputBuilder = { TextInput("甲子") },
          promptBuilder = { input, _ -> "讀盤：${(input as TextInput).prompt}" },
          formatSpec = TEXT_FORMAT_SPEC
        ),
        Segment.ParallelAiSegment<String, TextOutput>(
          id = ITEMS,
          itemsProvider = { itemsAsked.set(true); listOf("a", "b") },
          itemInputBuilder = { item, _ -> TextInput(item) },
          promptBuilder = { input, _ -> (input as TextInput).prompt },
          formatSpec = TEXT_FORMAT_SPEC
        )
      ),
      assembler = { ctx -> ctx.get<TextOutput>(READING).text to ctx.get<ParallelOutput<TextOutput>>(ITEMS).successful.size }
    )

    val result = imageEngine(null, null, chatOrchestrator = chat).execute(
      plan,
      initialContext = mapOf(READING to TextOutput("SEEDED reading"), ITEMS to ParallelOutput(listOf(TextOutput("SEEDED item"))))
    )

    assertIs<ExecutionResult.Success<Pair<String, Int>>>(result)
    assertEquals("SEEDED reading" to 1, result.result, "assembler 拿到的不是沿用的舊值")
    assertEquals(0, chat.callCount, "已完成的 AI 段又打了一次 —— 這就是那 \$0.032：${chat.prompts}")
    assertEquals(0, result.metadata.totalAiCalls, "跳過就是沒發起呼叫，不得計入")
    assertFalse(itemsAsked.get(), "itemsProvider 被呼叫了 —— 那一段其實走進了 runSegment")
    assertTrue(result.metadata.segmentUsages.isEmpty(), "本輪沒發出任何呼叫，不該有 per-segment 用量")
  }
}
