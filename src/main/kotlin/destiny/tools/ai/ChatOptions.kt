/**
 * Created by smallufo on 2025-05-12.
 */
package destiny.tools.ai


@JvmInline
value class Temperature(val value: Double) {
  init {
    require(value in 0.0..1.0) { "Temperature must be in 0.0..1.0" }
  }
}

@JvmInline
value class TopP(val value: Double) {
  init {
    require(value in 0.0..1.0) { "topP must be in 0.0..1.0" }
  }
}

@JvmInline
value class TopK(val value: Int) {
  init {
    require(value >= 1) { "topK must be >= 1" }
  }
}

@JvmInline
value class FrequencyPenalty(val value: Double) {
  init {
    require(value in -1.0..1.0) { "FrequencyPenalty must be in -1.0..1.0" }
  }
}

@JvmInline
value class MaxTokens(val value: Int) {
  init {
    require(value >= 1) { "maxTokens must be >= 1" }
  }
}

data class ChatOptions(
  val temperature: Temperature? = null,
  /**
   * 更一致： topP = 0.8 ~ 0.9
   * 更發散 : topP = 0.95 ~ 1.0
   */
  val topP: TopP? = null,
  /**
   * 更一致 : topK = 20 ~ 50
   * 更發散 : topK = 100+
   */
  val topK: TopK? = null,

  val frequencyPenalty: FrequencyPenalty? = null,

  /**
   * 最大輸出 tokens 數
   * 預設 null 表示使用各 provider 的預設值
   */
  val maxTokens: MaxTokens? = null,

  /**
   * 思考模式。null → **不送**這個參數，沿用該 model 的預設。
   *
   * 之所以需要明講，是因為「預設」會隨模型世代改變：Claude 4.6 世代
   * （`claude-sonnet-5`、`claude-opus-5` …）**省略 thinking 參數等於 adaptive 開啟**，
   * 而更早的模型（`claude-haiku-4-5` …）省略等於不思考。2026-08-26 dev 環境的
   * `Serializer for subclass 'thinking' is not found` 就是這麼來的 —— 一份沒改過的 request
   * 只因為 model 換代就開始回 thinking block。
   *
   * ⚠️ 不是每個 model 都吃每個值，送錯會 400：[ThinkingMode.ADAPTIVE] 系列只有 4.6 世代以上
   * 支援；部分最新模型（如 Fable 5）**不接受** [ThinkingMode.DISABLED]。設定前先確認目標 model。
   */
  val thinking: ThinkingMode? = null,
  /**
   * 推理深度／輸出預算的粗刻度。null → 不送，沿用該 model 的預設（Anthropic 預設 `high`）。
   *
   * 與 [thinking] 搭配用：對 Claude Sonnet 5／Opus 5 這一代，官方建議用 `adaptive` ＋ 低 effort
   * 來壓思考量，而不是 `disabled` —— 實測 `disabled` 對 sonnet-5 並不可靠（同一設定下八次有兩次
   * 仍回 thinking block 並吃滿 max_tokens），且 disabled 還會把工具呼叫寫進正文、洩漏 thinking 標籤。
   * 尚未支援的 impl 直接忽略。
   */
  val effort: Effort? = null,
)

/**
 * 跨 provider 的推理深度刻度。各 impl 自行映射（Anthropic：`output_config.effort`；
 * OpenAI：`reasoning_effort` 只有 low/medium/high，XHIGH/MAX 由 impl 收斂）。
 */
enum class Effort {
  LOW, MEDIUM, HIGH, XHIGH, MAX,
}

/**
 * 跨 provider 的思考模式。各 impl 自行映射到自家的 wire 格式
 * （Anthropic 的 `thinking`、Gemini 的 `thinkingConfig`、OpenAI 的 `reasoning_effort`）；
 * 尚未支援的 impl 直接忽略，行為與 null 相同。
 */
enum class ThinkingMode {
  /** 明確關閉 */
  DISABLED,

  /** 開啟，但不要求回傳思考內容（Anthropic: `display = omitted`，也是它的預設） */
  ADAPTIVE,

  /** 開啟且回傳思考摘要（Anthropic: `display = summarized`）—— 想在 log 看見推理過程時用 */
  ADAPTIVE_SUMMARIZED,
}
