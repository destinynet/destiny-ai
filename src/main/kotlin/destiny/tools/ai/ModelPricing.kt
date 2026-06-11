package destiny.tools.ai

import kotlinx.serialization.Serializable

/**
 * 單一 model 的計價資訊，單位皆為 **USD per 1,000,000 tokens**。
 *
 * thinking / reasoning token 不另計價 —— 各家一律以 [output] 計費，且 impl 已把 thinking-token
 * 數折進 outputTokens（見 AbstractGeminiImpl）。故 input/output 兩個數字即足夠。
 */
@Serializable
data class ModelPricing(
  /** USD / 1M input tokens */
  val input: Double,
  /** USD / 1M output tokens（含 thinking/reasoning） */
  val output: Double,
  /** USD / 1M cache-read input tokens；null → 以 [input] 計（多數 provider 不回報 cache token） */
  val cacheRead: Double? = null,
  /** USD / 1M cache-write input tokens；null → 以 [input] 計 */
  val cacheWrite: Double? = null,
) {
  /**
   * 依各維度 token 數算出此次呼叫的 USD 成本。token 參數直接對應 Reply.Normal 的
   * inputTokens / outputTokens / cacheReadTokens / cacheCreationTokens。
   */
  fun cost(input: Int, output: Int, cacheRead: Int = 0, cacheWrite: Int = 0): Double =
    (input * this.input +
      output * this.output +
      cacheRead * (this.cacheRead ?: this.input) +
      cacheWrite * (this.cacheWrite ?: this.input)) / 1_000_000.0
}
