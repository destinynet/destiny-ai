/**
 * Created by smallufo on 2026-01-18.
 *
 * Unified Workflow Engine - Execution Results
 */
package destiny.tools.workflow

/**
 * 執行結果
 */
sealed class ExecutionResult<R> {
  /**
   * 執行成功
   */
  data class Success<R>(
    val result: R,
    val metadata: ExecutionMetadata
  ) : ExecutionResult<R>()

  /**
   * 執行失敗
   */
  data class Failed<R>(
    val failedSegment: SegmentId,
    val error: Throwable,
    val partialResults: Map<SegmentId, SegmentOutput>,
    val metadata: ExecutionMetadata
  ) : ExecutionResult<R>()

  fun isSuccess(): Boolean = this is Success
  fun isFailed(): Boolean = this is Failed

  fun getOrNull(): R? = (this as? Success)?.result

  fun getOrThrow(): R = when (this) {
    is Success -> result
    is Failed -> throw ExecutionException(
      "Execution failed at segment $failedSegment",
      error
    )
  }
}

/**
 * 執行例外
 */
class ExecutionException(
  message: String,
  cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * 執行元資料
 */
data class ExecutionMetadata(
  /** 總執行時間 (毫秒) */
  val totalDurationMs: Long,

  /** 每個 segment 的執行時間；續跑時被跳過的段不出現（跳過不是「跑了 0 毫秒」）。 */
  val segmentDurations: Map<SegmentId, Long>,

  /**
   * **實際**並行執行的次數 —— 不是 plan 宣告的同層寬度。
   *
   * 續跑時整層只剩一支要跑，實際並行度是 1，不計入；整層都已完成則完全不計。
   * 故看到 0 不代表引擎沒並行，只代表這一輪沒有兩支以上同時跑。
   */
  val parallelExecutions: Int,

  /**
   * AI 呼叫總次數（僅文字/chat 呼叫；生圖見 [totalImageCalls]）。
   *
   * 計的是**已發起**的呼叫 —— 含失敗與被取消者，與 [costUsd] 同一口徑：請求送出後對方就可能
   * 已計費，事後把它從次數裡抹掉，只會讓兩個欄位對同一次失敗給出矛盾的說法。
   * 代價是它為**上界**：連 orchestrator 都還沒碰到就失敗的 segment（例如引擎缺相依設定）也計入。
   *
   * 續跑（把上輪 partialResults 當 `initialContext`）時被跳過的段根本沒發起呼叫，故不計入 ——
   * 本欄位只反映**本輪**花掉的次數，要跨輪總計請自行累加各輪的 metadata。
   */
  val totalAiCalls: Int,

  /**
   * 生圖呼叫總次數（每個 ImageSegment 一次，不論該次要求幾張）。
   * 與 [totalAiCalls] 分開計：兩者計價維度不同（token vs per-image / per-MP），混計無意義；
   * 「已發起」與「只反映本輪」的口徑同 [totalAiCalls]。
   */
  val totalImageCalls: Int = 0,

  /**
   * 全計畫累計 Token 使用量（跨所有 AI segment / 並行 item）；無 AI 呼叫則 null。
   *
   * 含 **chat-native 生圖模型**（如 Gemini，回應帶真實 token）的 token —— 與文字 segment 混計、
   * 不區分來源；per-image / per-MP 計價的 provider（如 Replicate）無 token 可報，只反映在 [costUsd]。
   */
  val tokenUsage: TokenUsage? = null,

  /**
   * 全計畫累計成本 (USD)；需引擎注入 `ModelCostService` 才會算得出，否則 null。
   * 多模型計畫逐筆 reply 依各自 pricing 計價後加總（單一 model 無法涵蓋）。
   */
  val costUsd: Double? = null,

  /** 本次執行實際用到的 (provider, model)，去重、依首次出現順序；無 AI 呼叫則為空。 */
  val modelsUsed: List<UsedModel> = emptyList(),

  /**
   * 各 segment 各自的用量/成本；未發出呼叫的段（Static/Compute、或續跑時被跳過者）不會出現。
   * 加總即等於 [tokenUsage] / [costUsd]。
   */
  val segmentUsages: Map<SegmentId, SegmentUsage> = emptyMap()
) {
  companion object {
    fun empty() = ExecutionMetadata(
      totalDurationMs = 0,
      segmentDurations = emptyMap(),
      parallelExecutions = 0,
      totalAiCalls = 0
    )
  }
}

/** 一次執行用到的模型（provider + model 名稱）。 */
data class UsedModel(
  val provider: String,
  val model: String
)

/**
 * 單一 segment 的用量/成本快照。`ParallelAiSegment` 為該段所有 item 的合計。
 *
 * 之所以要分段：全計畫總額算不出「讀盤花多少、生圖花多少」，
 * 而這兩段的計價維度與單價都不同（token vs per-image），產品端常需分開呈現。
 */
data class SegmentUsage(
  /**
   * **有拿到回應**（因而算得出用量）的呼叫數。與 [ExecutionMetadata.totalAiCalls] 的「已發起」
   * 口徑不同：失敗／被取消的呼叫沒有用量可記，故全段加總 ≤ 那組總計數。
   */
  val calls: Int,
  val tokenUsage: TokenUsage?,
  val costUsd: Double?,
  val modelsUsed: List<UsedModel>
)

/**
 * Token 使用量（含 cache 維度）。全欄位 nullable：某維度從未收到非 null 值即維持 null（「未知」），
 * 與 [destiny.tools.ai.TokenUsageAccumulator] 的 null 語意一致。
 */
data class TokenUsage(
  val inputTokens: Int? = null,
  val outputTokens: Int? = null,
  val cacheCreationTokens: Int? = null,
  val cacheReadTokens: Int? = null
) {
  val totalTokens: Int?
    get() = if (inputTokens == null && outputTokens == null) null
    else (inputTokens ?: 0) + (outputTokens ?: 0)

  operator fun plus(other: TokenUsage) = TokenUsage(
    inputTokens = plus(inputTokens, other.inputTokens),
    outputTokens = plus(outputTokens, other.outputTokens),
    cacheCreationTokens = plus(cacheCreationTokens, other.cacheCreationTokens),
    cacheReadTokens = plus(cacheReadTokens, other.cacheReadTokens)
  )

  /** 兩者皆 null → null（維持未知）；否則 null 視為 0 後相加。 */
  private fun plus(a: Int?, b: Int?): Int? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)

  companion object {
    val ZERO = TokenUsage(0, 0, 0, 0)
  }
}
