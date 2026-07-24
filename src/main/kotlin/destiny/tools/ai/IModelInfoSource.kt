/**
 * Created by smallufo on 2026-07-24.
 */
package destiny.tools.ai

/**
 * Model metadata 的**宣告來源** —— 自 [IChatCompletion] 剝離的正交面向。
 *
 * 任何「持有一組 model 及其 metadata」的 impl 皆實作此介面：chat（[IChatCompletion]）、
 * image（[IImageGeneration]）、未來的 audio/video。如此 image-only provider
 * （BFL FLUX、Imagen 專用 endpoint 等，根本沒有 chat API）也能登記 pricing/capabilities，
 * 不必被迫實作 chatComplete。
 *
 * 與 [IModelCatalog]（跨 provider 讀取 facade）的分工不變：
 * 已握有某 impl 時就地查 → 用本介面；只有 (provider, model) 一對值 → 走 [IModelCatalog]。
 */
interface IModelInfoSource {

  val provider: Provider

  /**
   * 此 impl 提供的所有 model metadata。key = model string（即 API 呼叫收的 model 參數）。
   * 預設空 map —— 各 impl 在 companion 宣告 MODEL_INFOS 後，以 `override val modelInfos = MODEL_INFOS` 填入。
   */
  val modelInfos: Map<String, ModelInfo> get() = emptyMap()

  /**
   * 查 model 的計價/metadata；查無回 null。
   * （modelKey 即 model string，等同 [ModelInfo.model]。）
   */
  fun findModelInfo(modelKey: String): ModelInfo? = modelInfos[modelKey]

  /** 同 [findModelInfo]，但查無即丟 [NoSuchModelException]（fail-fast）。 */
  fun requireModelInfo(modelKey: String): ModelInfo =
    findModelInfo(modelKey) ?: throw NoSuchModelException(provider, modelKey)
}
