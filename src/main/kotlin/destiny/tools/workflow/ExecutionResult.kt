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

  /** 每個 segment 的執行時間 */
  val segmentDurations: Map<SegmentId, Long>,

  /** 並行執行次數 */
  val parallelExecutions: Int,

  /** AI 呼叫總次數 */
  val totalAiCalls: Int,

  /** 全計畫累計 Token 使用量（跨所有 AI segment / 並行 item）；無 AI 呼叫則 null */
  val tokenUsage: TokenUsage? = null,

  /**
   * 全計畫累計成本 (USD)；需引擎注入 `ModelCostService` 才會算得出，否則 null。
   * 多模型計畫逐筆 reply 依各自 pricing 計價後加總（單一 model 無法涵蓋）。
   */
  val costUsd: Double? = null
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
