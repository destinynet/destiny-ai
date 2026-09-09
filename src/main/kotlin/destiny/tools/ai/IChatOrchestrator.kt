/**
 * Created by smallufo on 2025-05-13.
 */
package destiny.tools.ai

import destiny.tools.ai.model.FormatSpec
import java.util.*
import kotlin.time.Duration


interface IChatOrchestrator {

  /**
   * 同 [chatComplete]，但失敗時回 [Orchestration.Exhausted] 而不是 `null` —— 每一次嘗試的分類都在，
   * 呼叫端分得出「schema 壞了」與「provider 暫時不行」。預設實作只是把 [chatComplete] 的 null
   * 包成沒有細節的 Exhausted，讓既有的 fake 不必改；真正的 orchestrator 覆寫它。
   */
  suspend fun <T : Any> chatCompleteOrExplain(
    formatSpec: FormatSpec<out T>,
    messages: List<Msg>,
    postProcessors: List<IPostProcessor>,
    locale: Locale = Locale.getDefault(),
    funCalls: Set<IFunctionDeclaration> = emptySet(),
    chatOptionsTemplate: ChatOptions = ChatOptions(),
    providerImpl: (Provider) -> IChatCompletion
  ): Orchestration<out T> {
    return chatComplete(formatSpec, messages, postProcessors, locale, funCalls, chatOptionsTemplate, providerImpl)
      ?.let { Orchestration.Success(it) }
      ?: Orchestration.Exhausted(emptyList(), note = "no attempt details from ${this::class.simpleName}")
  }

  suspend fun <T : Any> chatCompleteOrExplain(
    formatSpec: FormatSpec<out T>,
    message: String,
    postProcessors: List<IPostProcessor>,
    locale: Locale = Locale.getDefault(),
    funCalls: Set<IFunctionDeclaration> = emptySet(),
    chatOptionsTemplate: ChatOptions = ChatOptions(),
    providerImpl: (Provider) -> IChatCompletion
  ): Orchestration<out T> {
    return chatCompleteOrExplain(formatSpec, listOf(Msg(Role.USER, message)), postProcessors, locale, funCalls, chatOptionsTemplate, providerImpl)
  }


  suspend fun <T: Any> chatComplete(
    formatSpec: FormatSpec<out T>,
    messages: List<Msg>,
    postProcessors: List<IPostProcessor>,
    locale: Locale = Locale.getDefault(),
    funCalls: Set<IFunctionDeclaration> = emptySet(),
    chatOptionsTemplate: ChatOptions = ChatOptions(),
    providerImpl : (Provider) -> IChatCompletion
  ): Reply.Normal<out T>?

  suspend fun <T : Any> chatComplete(
    formatSpec: FormatSpec<out T>,
    message: String,
    postProcessors: List<IPostProcessor>,
    locale: Locale = Locale.getDefault(),
    funCalls: Set<IFunctionDeclaration> = emptySet(),
    chatOptionsTemplate: ChatOptions = ChatOptions(),
    providerImpl: (Provider) -> IChatCompletion
  ): Reply.Normal<out T>? {
    return chatComplete(formatSpec, listOf(Msg(Role.USER, message)), postProcessors, locale, funCalls, chatOptionsTemplate, providerImpl)
  }

}

interface IChatOrchestratorFactory {
  fun hedged(config: HedgeConfig): IChatOrchestrator
  fun resilient(config: ResilientConfig): IChatOrchestrator
}

interface IChatConfig {
  val user: String?
  val modelTimeout: Duration
}

