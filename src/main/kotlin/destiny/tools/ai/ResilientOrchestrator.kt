/**
 * Created by smallufo on 2026-07-24.
 */
package destiny.tools.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

/**
 * Resilient retry 的 **generic 核心引擎** —— 與 content 型別無關，只依賴 [Reply] 的錯誤分類。
 *
 * 政策（自 [ResilientChatService] 抽出，語意不變）：
 * - 每輪把 [providerModels] 隨機打亂後依序嘗試，取第一個 [Reply.Normal]。
 * - [Reply.Error.InvalidApiKey] —— 該 provider 於本次 execute 剩餘的 loop 中永久停用。
 * - [Reply.Error.RateLimited] —— 收集 `retryAfter` hint；下一輪至少等
 *   max([delayBetweenModelLoops], 本輪最長 retryAfter) 才開始。
 * - 其他 [Reply.Error]（Retryable / Terminal）與 exception —— 紀錄後換下一個 model，該 model 下輪可再試。
 * - 全部失敗 → 最多重複 [maxTotalAttempts] 輪。
 *
 * Chat 走 [ResilientChatService]（綁 typedChatComplete）；image 等其他 modality
 * 只要 attempt 回傳 [Reply] 即可共用本引擎。
 */
class ResilientOrchestrator(
  private val providerModels: Set<ProviderModel>,
  private val delayBetweenModelLoops: Duration = 2.seconds,
  private val maxTotalAttempts: Int = 3,
) {

  suspend fun <T : Any> execute(attempt: suspend (ProviderModel) -> Reply<T>?): Reply.Normal<T>? {
    if (providerModels.isEmpty()) {
      logger.warn { "No provider models specified for resilient execution." }
      return null
    }

    // 跨 loop 共享的狀態：API key 無效的 provider 整個 execute 都不再嘗試
    val disabledProviders = mutableSetOf<Provider>()

    var attemptsLeft = maxTotalAttempts
    while (attemptsLeft > 0) {
      val candidates: List<ProviderModel> = providerModels
        .filter { it.provider !in disabledProviders }
        .shuffled()

      if (candidates.isEmpty()) {
        logger.error { "All providers disabled (invalid API keys or similar); aborting after ${maxTotalAttempts - attemptsLeft} loops." }
        return null
      }

      logger.info { "Starting attempt loop (attemptsLeft=$attemptsLeft), candidates: ${candidates.map { "${it.provider}/${it.model}" }}" }

      // 收集本 loop 內看到的 RateLimited.retryAfter，用來計算下一 loop 的最小等待時間
      var maxRetryAfter: Duration = ZERO

      val result: Reply.Normal<T>? = candidates.suspendFirstNotNullResult { providerModel ->
        logger.debug { "Attempting ${providerModel.provider}/${providerModel.model}" }
        try {
          when (val reply = attempt(providerModel)) {
            is Reply.Normal -> reply

            is Reply.Error.InvalidApiKey -> {
              // session-permanent：剩下的 loop 都不要再試這個 provider
              logger.warn { "InvalidApiKey on ${reply.provider}; disabling provider for the rest of this call" }
              disabledProviders += reply.provider
              null
            }

            is Reply.Error.RateLimited -> {
              reply.retryAfter?.let { hint ->
                if (hint > maxRetryAfter) maxRetryAfter = hint
              }
              logger.warn { "Retryable[RateLimited] on ${providerModel.provider}/${providerModel.model}, retryAfter=${reply.retryAfter}, msg=${reply.message}" }
              null
            }

            is Reply.Error.Retryable -> {
              logger.warn { "Retryable on ${providerModel.provider}/${providerModel.model}: $reply" }
              null
            }

            is Reply.Error.Terminal -> {
              logger.warn { "Terminal on ${providerModel.provider}/${providerModel.model}: $reply" }
              null
            }

            is Reply.Error -> {
              // 安全網：未來新增的 leaf 沒實作 Retryable/Terminal marker 時也能 fall through
              logger.warn { "Unclassified error on ${providerModel.provider}/${providerModel.model}: $reply" }
              null
            }

            null -> {
              logger.warn { "attempt returned null for ${providerModel.provider}/${providerModel.model}" }
              null
            }
          }
        } catch (e: CancellationException) {
          // coroutine 取消必須往上拋，不可吞成「換下一家」的信號
          throw e
        } catch (e: Exception) {
          logger.error(e) { "Exception during attempt with ${providerModel.provider}/${providerModel.model}" }
          null
        }
      }

      if (result != null) {
        logger.info { "Success on ${result.provider}/${result.model} (attemptsLeft=$attemptsLeft)" }
        return result
      }

      attemptsLeft--
      if (attemptsLeft > 0) {
        // 若本 loop 有 model 給了 retryAfter hint，至少等到那麼久後再開新一輪
        val backoff = maxOf(delayBetweenModelLoops, maxRetryAfter)
        logger.info { "All candidates failed this loop. Sleeping $backoff before next loop (attemptsLeft=$attemptsLeft, maxRetryAfter=$maxRetryAfter)" }
        delay(backoff)
      }
    }

    logger.error { "All $maxTotalAttempts attempt loops exhausted." }
    return null
  }

  companion object {
    val logger = KotlinLogging.logger {}
  }
}
