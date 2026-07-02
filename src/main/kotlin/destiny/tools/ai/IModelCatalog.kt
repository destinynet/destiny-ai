package destiny.tools.ai

/**
 * 跨 provider 的 model metadata 查詢入口（**讀取 facade**）。
 *
 * 與 [IChatCompletion.findModelInfo]（provider 本地捷徑）的分工：
 * - [IChatCompletion.findModelInfo]：已握有某 provider 的 impl 時，就地查該 provider 的 model。
 * - [IModelCatalog]：只有 `(provider, model)` 一對值、不想自己去找對的 impl bean 時的中央入口。
 *
 * **它同時是未來 ADOPT 的接縫**：Step 1（WARN）一律回 seed；日後若要以 models.dev catalog 覆蓋
 * 部分欄位（Step 3 / ADOPT），只需改 concrete 的實作，所有 caller 不動。
 *
 * 註：key 用 `(Provider, model)` 而非單一 model 字串——代管商（Groq/Cerebras/Together）可能有同名 model。
 */
interface IModelCatalog {

  /** 全部已知 model：provider → (model → info)。concrete 決定其內容（Step 1 = 各 impl 的 seed 聚合）。 */
  fun allModels(): Map<Provider, Map<String, ModelInfo>>

  /** 查 `(provider, model)` 的 metadata；查無回 null。 */
  fun getModel(provider: Provider, model: String): ModelInfo? = allModels()[provider]?.get(model)

  /** 同 [getModel]，查無即丟 [NoSuchModelException]（fail-fast）。 */
  fun requireModel(provider: Provider, model: String): ModelInfo =
    getModel(provider, model) ?: throw NoSuchModelException(provider, model)
}
