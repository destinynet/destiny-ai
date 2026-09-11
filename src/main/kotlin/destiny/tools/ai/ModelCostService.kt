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

  /**
   * 直接吃 [Reply.Normal]，回傳此次呼叫 USD 成本；無法解析則 null。
   * （[Reply.Error] 無 token 計數，呼叫端請先 narrow 成 Normal 再計價）
   */
  fun cost(reply: Reply.Normal<*>): Double? {
    val pricing = catalog.getModel(reply.provider, reply.model)?.pricing ?: return null
    return pricing.cost(
      input = reply.inputTokens ?: 0,
      output = reply.outputTokens ?: 0,
      cacheRead = reply.cacheReadTokens ?: 0,
      cacheWrite = reply.cacheCreationTokens ?: 0,
    )
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
