/**
 * Created by smallufo on 2026-10-28.
 *
 * [Scheduling.DAG] 的契約 —— 全部走假件（見 WorkflowTestFakes.kt），不打真 API。
 *
 * 1. 下游在**它的**依賴完成後就開跑，不等同一層的慢段（這是它存在的理由）；
 * 2. 一段只看得到它（遞移）依賴的成果 —— 可決定性比分層更嚴；
 * 3. 兩種失敗政策：AWAIT ⇒ 失敗段的下游不跑、無關的段跑完並留在 partialResults；CANCEL ⇒ 其餘取消；
 * 4. 續跑：initialContext 裡的段不重跑，依賴它的段立刻開跑；
 * 5. 預設仍是分層（既有的 plan 不受影響）。
 */
package destiny.tools.workflow

import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExecutionEngineDagTest {

  private val A = SegmentId("a")
  private val B = SegmentId("b")
  private val SLOW = SegmentId("slow")
  private val C = SegmentId("c")

  /** prompt → 開跑與跑完的時間（相對於 [t0]，毫秒） */
  private val started = ConcurrentHashMap<String, Long>()
  private val ended = ConcurrentHashMap<String, Long>()
  private var t0 = 0L

  /** prompt 的第一個字決定行為：`slow…` 跑 [SLOW_MS]、`boom…` 炸、其餘跑 [FAST_MS] */
  private fun impl() = FakeImageGeneration(onPrompt = { p ->
    started[p] = System.currentTimeMillis() - t0
    when {
      p.startsWith("boom") -> { delay(FAST_MS); throw IllegalStateException("boom") }
      p.startsWith("slow") -> delay(SLOW_MS)
      else                 -> delay(FAST_MS)
    }
    ended[p] = System.currentTimeMillis() - t0
  })

  private fun engine(impl: FakeImageGeneration, policy: FailurePolicy = FailurePolicy.AWAIT_SIBLINGS, dag: Boolean = true) =
    DefaultExecutionEngine(
      orchestrator = mockk(),
      postProcessors = emptyList(),
      providerImpl = { mockk() },
      imageOrchestrator = FakeImageOrchestrator(impl.provider),
      imageProviderImpl = { impl },
      failurePolicy = policy,
    ).let { if (dag) it.scheduling(Scheduling.DAG) else it }

  private fun seg(id: SegmentId, deps: Set<SegmentId> = emptySet(), prompt: (SegmentContext) -> String) =
    Segment.ImageSegment(id, deps, promptBuilder = prompt)

  private fun plan(vararg s: Segment) = GenerationPlan(planId = "dag", name = "DAG", segments = s.toList(), assembler = { "done" })

  /** A（快）→ B；SLOW 與它們無關。分層時 B 與 SLOW 不同層：B 要等 SLOW 跑完 */
  private val shape = arrayOf(
    seg(A) { "a" }, seg(SLOW) { "slow" }, seg(B, setOf(A)) { "b" },
  )

  @Test
  fun `🔴 下游在它的依賴完成後就開跑，不等同層的慢段`(): Unit = runBlocking {
    val engine = engine(impl())
    t0 = System.currentTimeMillis()
    assertIs<ExecutionResult.Success<String>>(engine.execute(plan(*shape)))
    assertTrue(started.getValue("b") < SLOW_MS - FAST_MS, "B 應在 A 完成後就開跑（實際 ${started["b"]}ms，SLOW 要 ${SLOW_MS}ms）")
    assertTrue(ended.getValue("b") < ended.getValue("slow"), "B 比 SLOW 先跑完")
  }

  @Test
  fun `對照：分層時 B 要等 SLOW 跑完`(): Unit = runBlocking {
    val engine = engine(impl(), dag = false)
    t0 = System.currentTimeMillis()
    assertIs<ExecutionResult.Success<String>>(engine.execute(plan(*shape)))
    assertTrue(started.getValue("b") >= ended.getValue("slow"), "分層：B 開跑時 SLOW 已經跑完")
  }

  /**
   * 🔴 可決定性：B 沒宣告 C，就看不到 C —— 即使 C 早就跑完了。
   * 分層時看得到（C 在較早的層），所以 DAG 是選用、不是預設。
   */
  @Test
  fun `🔴 一段只看得到它宣告的依賴`(): Unit = runBlocking {
    val peek = seg(B, setOf(A)) { ctx -> "b-sees-c=${ctx.isCompleted(C)}" }
    val p = plan(seg(C) { "c" }, seg(A) { "slow-a" }, peek)

    t0 = System.currentTimeMillis()
    engine(impl()).execute(p)
    assertTrue("b-sees-c=false" in started.keys, "DAG：沒宣告就看不到（實際 ${started.keys}）")

    started.clear()
    t0 = System.currentTimeMillis()
    engine(impl(), dag = false).execute(p)
    assertTrue("b-sees-c=true" in started.keys, "分層：較早的層都看得到（實際 ${started.keys}）")
  }

  @Test
  fun `遞移的依賴也看得到`(): Unit = runBlocking {
    val third = seg(C, setOf(B)) { ctx -> "c-sees-a=${ctx.isCompleted(A)}" }
    engine(impl()).execute(plan(seg(A) { "a" }, seg(B, setOf(A)) { "b" }, third))
    assertTrue("c-sees-a=true" in started.keys, "C 依賴 B、B 依賴 A ⇒ C 看得到 A（實際 ${started.keys}）")
  }

  /** AWAIT：A 炸 ⇒ 依賴它的 B 不跑；與它無關的 SLOW 跑完、成果留在 partialResults（錢已經付了） */
  @Test
  fun `🔴 AWAIT：失敗段的下游不跑，無關的段跑完並留在 partialResults`(): Unit = runBlocking {
    val result = engine(impl()).execute(plan(seg(A) { "boom-a" }, seg(SLOW) { "slow" }, seg(B, setOf(A)) { "b" }))
    val failed = assertIs<ExecutionResult.Failed<String>>(result)
    assertEquals(A, failed.failedSegment, "失敗歸屬到 A，不是沒跑的 B")
    assertTrue(SLOW in failed.partialResults, "SLOW 跑完了，要能續跑")
    assertFalse("b" in started.keys, "B 的依賴失敗，不該開跑")
    assertTrue(failed.error.suppressed.isEmpty(), "B 沒跑不算一次失敗")
  }

  /** CANCEL：A 炸 ⇒ SLOW 被取消，不付錢買一個註定被丟棄的結果 */
  @Test
  fun `CANCEL：任一段失敗即取消其餘`(): Unit = runBlocking {
    val result = engine(impl(), FailurePolicy.CANCEL_SIBLINGS)
      .execute(plan(seg(A) { "boom-a" }, seg(SLOW) { "slow" }, seg(B, setOf(A)) { "b" }))
    assertEquals(A, assertIs<ExecutionResult.Failed<String>>(result).failedSegment)
    assertTrue("slow" in started.keys && "slow" !in ended.keys, "SLOW 開跑了、但被取消沒跑完")
  }

  /** 續跑：A 已在 initialContext ⇒ 不重跑，B 立刻開跑 */
  @Test
  fun `續跑：initialContext 裡的段不重跑，依賴它的段立刻開跑`(): Unit = runBlocking {
    val impl = impl()
    t0 = System.currentTimeMillis()
    val result = engine(impl).execute(plan(*shape), initialContext = mapOf(A to ImageOutput(emptyList())))
    assertIs<ExecutionResult.Success<String>>(result)
    assertFalse("a" in impl.prompts, "A 不重跑（不重付）")
    assertTrue(started.getValue("b") < FAST_MS, "B 立刻開跑（實際 ${started["b"]}ms）")
  }

  @Test
  fun `全部都在 initialContext 時直接組裝`(): Unit = runBlocking {
    val done = mapOf<SegmentId, SegmentOutput>(A to ImageOutput(emptyList()), SLOW to ImageOutput(emptyList()), B to ImageOutput(emptyList()))
    val impl = impl()
    assertIs<ExecutionResult.Success<String>>(engine(impl).execute(plan(*shape), initialContext = done))
    assertEquals(0, impl.callCount)
  }

  private companion object {
    const val FAST_MS = 50L
    const val SLOW_MS = 600L
  }
}
