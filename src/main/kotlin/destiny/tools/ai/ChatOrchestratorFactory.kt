package destiny.tools.ai

import jakarta.inject.Named

@Named
class ChatOrchestratorFactory : IChatOrchestratorFactory, IImageOrchestratorFactory {

  override fun hedged(config: HedgeChatService.HedgeConfig): IChatOrchestrator {
    return HedgeChatService(config)
  }

  override fun resilient(config: ResilientChatService.ResilientConfig): IChatOrchestrator {
    return ResilientChatService(config)
  }

  override fun hedgedImage(config: HedgeChatService.HedgeConfig): IImageOrchestrator {
    return HedgeImageService(config)
  }

  override fun resilientImage(config: ResilientChatService.ResilientConfig): IImageOrchestrator {
    return ResilientImageService(config)
  }
}
