package destiny.tools.ai

import jakarta.inject.Named

/**
 * 把一筆 LLM 回覆換算成 USD 成本。透過 [IModelCatalog] 以 `(provider, model)` 取得計價；
 * 查無 provider 或 model 一律回 null（呼叫端自行決定如何處理）。
 *
 * 註：改用 [IModelCatalog] 後，provider→model 的查詢集中在 catalog（不再自建 provider→impl 索引）；
 * 未來 catalog 走 ADOPT（以線上值覆蓋 seed）時，此處計價會自動跟上，無需改動。
 *
 * chatbots v3 可用此累加每位使用者的累計成本以做門檻控管。
 */
@Named
class ModelCostService(private val catalog: IModelCatalog) {

  /** 直接吃 [Reply.Normal]，回傳此次呼叫 USD 成本；無法解析則 null。 */
  fun cost(reply: Reply.Normal<*>): Double? =
    cost(reply.provider, reply.model, reply.tokenUsage ?: TokenUsage.ZERO)

  /**
   * 任何一次呼叫的成本：成功的、**驗證不過**的（照樣計費）都用這個算。查無計價回 null；缺的維度當 0。
   * 用量來自 [Orchestration.Attempt.usage] 或 [tokenUsage]。
   */
  fun cost(provider: Provider, model: String, usage: TokenUsage): Double? {
    val pricing = catalog.getModel(provider, model)?.pricing ?: return null
    return pricing.cost(
      input = usage.inputTokens ?: 0,
      output = usage.outputTokens ?: 0,
      cacheRead = usage.cacheReadTokens ?: 0,
      cacheWrite = usage.cacheCreationTokens ?: 0,
    )
  }

  /**
   * 一次編排（成功或失敗）的總帳：**每一次送出的嘗試**都算，含驗證不過、hedge 輸掉的那幾家。
   * 用量或價格不明的那幾次不猜，只算進 [OrchestrationUsage.unpricedAttempts]。
   */
  fun usage(orchestration: Orchestration<*>): OrchestrationUsage {
    val attempts = orchestration.allAttempts
    var tokens: TokenUsage? = null
    var cost: Double? = null
    var unpriced = 0
    attempts.forEach { a ->
      val u = a.usage
      val c = u?.let { cost(a.providerModel.provider, a.providerModel.model, it) }
      if (u != null) tokens = (tokens ?: TokenUsage()) + u
      if (c == null) unpriced++ else cost = (cost ?: 0.0) + c
    }
    return OrchestrationUsage(attempts.size, tokens, cost, unpriced)
  }

  /**
   * 取某 model 的**圖片**計價（per-image / per-MP / per-output-token）；查無回 null。
   *
   * 僅用於**回應不帶 token** 的生圖 provider（如 Replicate）—— 那類 [cost] 會算出誤導性的 0。
   * chat-native 生圖（Gemini）回應帶真實 token，請直接用 [cost] 精算。
   */
  fun imagePricing(provider: Provider, model: String): ImagePricing? =
    catalog.getModel(provider, model)?.pricing?.image
}

/**
 * 一次編排的用量與成本（[ModelCostService.usage]）。
 *
 * @param attempts 送出的呼叫數（含用量不明的）
 * @param tokens 有回報用量的那幾次相加；一次都沒有就是 null
 * @param costUsd 有用量、也查得到價的那幾次相加；一次都沒有就是 null
 * @param unpricedAttempts 送出了、但用量或價格不明的次數（例外、逾時、被取消、catalog 沒登記）—— 這幾次可能已計費，金額未知
 */
data class OrchestrationUsage(
  val attempts: Int,
  val tokens: TokenUsage?,
  val costUsd: Double?,
  val unpricedAttempts: Int,
)
