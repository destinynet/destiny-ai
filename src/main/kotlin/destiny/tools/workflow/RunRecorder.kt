/**
 * Created by smallufo on 2026-07-25.
 *
 * Unified Workflow Engine - 一次執行（run）的記帳：分錄（CallUsage）、記帳器（RunRecorder）、
 * 聚合純函數（fold / aggregateUsage）。
 *
 * 這裡只管「帳」—— context 資料流（completedOutputs staging、單一寫入者不變式）與併發政策
 * （FailurePolicy / runLayer）不屬於本檔，留在引擎端。
 */
package destiny.tools.workflow

import destiny.tools.ai.TokenUsageAccumulator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 帳本的一筆分錄：單通 AI 呼叫擷取到的用量 + 當下算好的成本 + 使用的 provider/model + 是哪一段發的。
 *
 * 引擎在拿到 reply 的當下建構它；destiny-ai 只定義語彙與聚合，**計價的執行**（catalog 查表）在
 * impl 端（`ModelCostService`），與 [ExecutionMetadata.costUsd]、[SegmentUsage.costUsd] 的分工一致。
 */
data class CallUsage(
  val segmentId: SegmentId,
  val provider: String,
  val model: String,
  val inputTokens: Int?,
  val outputTokens: Int?,
  val cacheCreationTokens: Int?,
  val cacheReadTokens: Int?,
  /**
   * 此筆呼叫的成本（USD）—— 於**擷取當下**依該 reply 的 provider/model pricing 算好；
   * 事後才算會隨計價表變動而失真。查無計價或引擎未注入計價服務則 null。
   */
  val costUsd: Double?
)

/**
 * 一次 run 的記帳器：durations、分錄、呼叫計數、並行計數，最後以 [buildMetadata] 結算。
 *
 * **契約：全類 thread-safe** —— 同層 segment 會並行寫入，所有方法皆可從任意協程呼叫。
 * （引擎端 context 的「單一寫入者」不變式是另一回事，與本類無關。）
 *
 * 生命週期：一個 `execute(plan)` 一顆，建構當下即起錶（[buildMetadata] 的 totalDurationMs 基準）。
 */
class RunRecorder {

  private val startTime = System.currentTimeMillis()
  private val segmentDurations = ConcurrentHashMap<SegmentId, Long>()
  private val callUsages = ConcurrentLinkedQueue<CallUsage>()
  private val parallelExecutions = AtomicInteger(0)
  private val totalAiCalls = AtomicInteger(0)
  private val totalImageCalls = AtomicInteger(0)

  /** 記入一筆分錄（拿到回應才有分錄；發起與回應的口徑差異見 [onAiCallsLaunched]）。 */
  fun record(usage: CallUsage) {
    callUsages.add(usage)
  }

  /**
   * 記入「**已發起**」的 chat 呼叫數：含之後失敗與被取消者。請求一旦送出，對方就可能已經計費；
   * 事後把它從次數裡抹掉，會讓 `totalAiCalls` 與 `costUsd` 對同一次失敗說出兩種說法
   * （fail-fast 取消兄弟時最明顯：兩張圖都送出了，卻回報 0 次）。故呼叫端應在**發出請求之前**計入。
   *
   * @param count `ParallelAiSegment` 一次發起多筆，傳 item 數。
   */
  fun onAiCallsLaunched(count: Int) {
    totalAiCalls.addAndGet(count)
  }

  /** 記入「已發起」的生圖呼叫（口徑同 [onAiCallsLaunched]；與 chat 分開計，計價維度不同）。 */
  fun onImageCallLaunched() {
    totalImageCalls.incrementAndGet()
  }

  /** 記入一次**實際**並行（同層有兩支以上要跑才算；續跑時整層只剩一支不算）。 */
  fun onParallelLayer() {
    parallelExecutions.incrementAndGet()
  }

  /** 記下某 segment 的耗時（只有真的跑完才記；被跳過≠跑了 0 毫秒）。 */
  fun onSegmentFinished(id: SegmentId, durationMs: Long) {
    segmentDurations[id] = durationMs
  }

  /**
   * 結算成 [ExecutionMetadata]。
   *
   * 成功與失敗兩條路回報的 metadata 完全相同（失敗時也要回報「已花掉」的 token/成本/model），
   * 故收斂成這一處。可重複呼叫（每次以當下時間結算 totalDurationMs）。
   */
  fun buildMetadata(): ExecutionMetadata {
    val usage = aggregateUsage(callUsages)
    return ExecutionMetadata(
      totalDurationMs = System.currentTimeMillis() - startTime,
      segmentDurations = segmentDurations.toMap(),
      parallelExecutions = parallelExecutions.get(),
      totalAiCalls = totalAiCalls.get(),
      totalImageCalls = totalImageCalls.get(),
      tokenUsage = usage.tokenUsage,
      costUsd = usage.costUsd,
      modelsUsed = usage.modelsUsed,
      segmentUsages = usage.perSegment
    )
  }
}

/** 計畫級聚合結果（[aggregateUsage] 的回傳；只在 destiny-ai 內部與其單測使用）。 */
internal data class AggregatedUsage(
  val tokenUsage: TokenUsage?,
  val costUsd: Double?,
  val modelsUsed: List<UsedModel>,
  val perSegment: Map<SegmentId, SegmentUsage>
)

/**
 * 把一組分錄 fold 成一份用量快照 —— 全計畫總額與 per-segment 共用同一套語意
 * （分段加總必須等於總額，兩份實作遲早會漂移）。
 *
 * token 用 [TokenUsageAccumulator] 的 null 語意；成本只要有任一筆算得出即加總（全無則 null）；
 * model 去重並保留首次出現順序。
 */
internal fun fold(usages: Collection<CallUsage>): SegmentUsage {
  val acc = TokenUsageAccumulator()
  var costUsd: Double? = null
  val models = LinkedHashSet<UsedModel>()
  usages.forEach { u ->
    acc.add(u.inputTokens, u.outputTokens, u.cacheCreationTokens, u.cacheReadTokens)
    if (u.costUsd != null) costUsd = (costUsd ?: 0.0) + u.costUsd
    models.add(UsedModel(u.provider, u.model))
  }
  return SegmentUsage(
    calls = usages.size,
    tokenUsage = TokenUsage(
      inputTokens = acc.inputTokens,
      outputTokens = acc.outputTokens,
      cacheCreationTokens = acc.cacheCreationTokens,
      cacheReadTokens = acc.cacheReadTokens
    ),
    costUsd = costUsd,
    modelsUsed = models.toList()
  )
}

/**
 * 計畫級總量 + 各 segment 分量。
 * @return 無任何 AI 呼叫時 tokenUsage/costUsd 為 null、modelsUsed 與 perSegment 為空。
 */
internal fun aggregateUsage(callUsages: Collection<CallUsage>): AggregatedUsage {
  // 這道防線不可拿掉：fold(emptyList()) 給的是 TokenUsage(null,null,null,null) 而非 null，
  // 「無 AI 呼叫時 tokenUsage 為 null」的契約會壞掉（RunRecorderTest 與 GenerationPlanTest 都有斷言）。
  if (callUsages.isEmpty()) return AggregatedUsage(null, null, emptyList(), emptyMap())
  val total = fold(callUsages)
  val perSegment = callUsages.groupBy { it.segmentId }.mapValues { (_, us) -> fold(us) }
  return AggregatedUsage(total.tokenUsage, total.costUsd, total.modelsUsed, perSegment)
}
