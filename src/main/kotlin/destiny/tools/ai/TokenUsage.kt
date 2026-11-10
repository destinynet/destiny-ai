/**
 * Created by smallufo on 2026-11-10.
 */
package destiny.tools.ai

/**
 * Token 使用量（含 cache 維度）。全欄位 nullable：某維度從未收到非 null 值即維持 null（「未知」），
 * 與 [TokenUsageAccumulator] 的 null 語意一致。
 *
 * 一次呼叫的用量（[Orchestration.Attempt.usage]）與一次 workflow run 的總量（`ExecutionMetadata.tokenUsage`）共用這個型別。
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

/**
 * 這次回覆 provider 回報的用量：成功的回覆與**驗證不過**的回覆都有（後者照樣計費）；其他錯誤沒有 token 計數，回 null。
 * 四個維度都沒有值時也回 null（provider 根本沒報）。
 */
val Reply<*>.tokenUsage: TokenUsage?
  get() {
    val u = when (this) {
      is Reply.Normal                       -> TokenUsage(inputTokens, outputTokens, cacheCreationTokens, cacheReadTokens)
      is Reply.Error.DeserializationFailure -> TokenUsage(inputTokens, outputTokens)
      else                                  -> return null
    }
    return u.takeUnless { it.inputTokens == null && it.outputTokens == null && it.cacheCreationTokens == null && it.cacheReadTokens == null }
  }
