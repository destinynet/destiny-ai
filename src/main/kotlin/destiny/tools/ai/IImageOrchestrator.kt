/**
 * Created by smallufo on 2026-07-24.
 */
package destiny.tools.ai

/**
 * Image generation 的 orchestrator 入口 —— 對應 chat 的 [IChatOrchestrator]。
 *
 * @param providerImpl 由 caller 解析 provider → impl；**回 null 代表該 provider 在我方 stack
 *        操作上無法生圖**（impl 未實作 [IImageGeneration]），orchestrator 視為該路失敗、換下一家。
 */
interface IImageOrchestrator {

  suspend fun generateImage(
    prompt: String,
    options: ImageOptions = ImageOptions(),
    providerImpl: (Provider) -> IImageGeneration?
  ): Reply.Normal<List<GeneratedImage>>?
}

/**
 * 方法名帶 `Image` 後綴：與 [IChatOrchestratorFactory] 的 `hedged` / `resilient`
 * 參數型別相同、僅回傳型別不同，同名會使同一個 concrete class 無法同時實作兩個 interface。
 */
interface IImageOrchestratorFactory {
  fun hedgedImage(config: HedgeChatService.HedgeConfig): IImageOrchestrator
  fun resilientImage(config: ResilientChatService.ResilientConfig): IImageOrchestrator
}

/**
 * Resilient image generation：政策同 [ResilientOrchestrator]（順序輪詢、backoff、provider 停用），
 * 本 class 只是把 [IImageGeneration.generateImage] 綁進 core 的 image adapter。
 */
class ResilientImageService(
  private val config: ResilientChatService.ResilientConfig
) : IImageOrchestrator {

  private val core = ResilientOrchestrator(config.providerModels, config.delayBetweenModelLoops, config.maxTotalAttempts)

  override suspend fun generateImage(
    prompt: String,
    options: ImageOptions,
    providerImpl: (Provider) -> IImageGeneration?
  ): Reply.Normal<List<GeneratedImage>>? {
    return core.execute { providerModel ->
      providerImpl.invoke(providerModel.provider)
        ?.generateImage(providerModel.model, prompt, options, config.modelTimeout)
    }
  }
}

/**
 * Hedged image generation：政策同 [HedgeOrchestrator]。
 *
 * **成本注意**：hedge 是 preferred + 所有 fallbacks 同時發出請求、靠 cancel 收尾；
 * image 按張計價且單價高，request 已送達 server 後即使 client cancel 仍可能計費。
 * 除非 caller 明確願意花錢換低延遲，一般 image 場景建議用 [ResilientImageService]。
 */
class HedgeImageService(
  private val config: HedgeChatService.HedgeConfig
) : IImageOrchestrator {

  private val core = HedgeOrchestrator(config.preferred, config.fallbacks, config.preferredWait)

  override suspend fun generateImage(
    prompt: String,
    options: ImageOptions,
    providerImpl: (Provider) -> IImageGeneration?
  ): Reply.Normal<List<GeneratedImage>>? {
    return core.execute { providerModel ->
      providerImpl.invoke(providerModel.provider)
        ?.generateImage(providerModel.model, prompt, options, config.modelTimeout)
    }
  }
}
