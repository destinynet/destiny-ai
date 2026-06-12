package destiny.tools.ai

/**
 * 累加跨多輪 function-call round-trips 的 token usage。每通 API response 呼叫一次 [add]。
 *
 * 計費上每通 API call 各自被計入（其 input 涵蓋當下完整 prompt），故 input/output/cache 跨輪相加
 * 正是該次 request 的真實總量，不是重複計算。
 *
 * null 語意保留：某維度只要從未收到非 null 值就維持 null（「未知」），一旦收過值即視缺漏輪為 0 累加。
 */
class TokenUsageAccumulator {
  private var input: Int? = null
  private var output: Int? = null
  private var cacheCreation: Int? = null
  private var cacheRead: Int? = null

  fun add(input: Int? = null, output: Int? = null, cacheCreation: Int? = null, cacheRead: Int? = null) {
    this.input = plus(this.input, input)
    this.output = plus(this.output, output)
    this.cacheCreation = plus(this.cacheCreation, cacheCreation)
    this.cacheRead = plus(this.cacheRead, cacheRead)
  }

  val inputTokens: Int? get() = input
  val outputTokens: Int? get() = output
  val cacheCreationTokens: Int? get() = cacheCreation
  val cacheReadTokens: Int? get() = cacheRead

  /** 兩者皆 null → null（維持未知）；否則 null 視為 0 後相加。 */
  private fun plus(a: Int?, b: Int?): Int? = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
}
