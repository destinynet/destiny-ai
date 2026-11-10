/**
 * Created by smallufo on 2026-07-24.
 */
package destiny.tools.ai

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Resilient orchestration 的配置，chat（[ResilientChatService]）與 image（[ResilientImageService]）共用。
 *
 * @param providerModels         輪詢的 (provider, model) 池；每輪隨機打亂後依序嘗試。
 * @param modelTimeout           單一 model 一次請求的 timeout。
 * @param delayBetweenModelLoops 在完整輪詢所有模型都失敗後，再次開始新一輪輪詢前的延遲。
 * @param maxTotalAttempts       最多進行多少輪完整的模型輪詢。
 * @param repairInvalidOutput    （只有 chat）回覆驗證不過（[Reply.Error.DeserializationFailure]）時，把那份回覆與錯誤訊息
 *                               （[ResilientChatService.repairPrompt]）接進對話，下一次嘗試請 model 照著改，而不是原樣重送。
 *                               預設關：既有呼叫端行為不變。
 */
data class ResilientConfig(
  val providerModels: Set<ProviderModel>,
  override val modelTimeout: Duration,
  val delayBetweenModelLoops: Duration = 2.seconds,
  val maxTotalAttempts: Int = 3,
  override val user: String? = null,
  val repairInvalidOutput: Boolean = false,
) : IChatConfig
