package destiny.tools.ai

import jakarta.inject.Named

@Named
class ChatOrchestratorFactory : IChatOrchestratorFactory, IImageOrchestratorFactory {

  override fun hedged(config: HedgeConfig): IChatOrchestrator {
    return HedgeChatService(config)
  }

  override fun resilient(config: ResilientConfig): IChatOrchestrator {
    return ResilientChatService(config)
  }

  override fun hedgedImage(config: HedgeConfig): IImageOrchestrator {
    return HedgeImageService(config)
  }

  override fun resilientImage(config: ResilientConfig): IImageOrchestrator {
    return ResilientImageService(config)
  }
}
