/**
 * Created by smallufo on 2026-07-24.
 */
package destiny.tools.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import mu.KotlinLogging
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Hedged request 的 **generic 核心引擎** —— 與 content 型別無關，只依賴 [Reply] 的成功/失敗判定。
 *
 * 政策（自 [HedgeChatService] 抽出）：
 * - [preferred] 與所有 [fallbacks] **同時**發出請求。
 * - [preferred] 若在 [preferredWait] 內成功（[Reply.Normal]）→ 回傳它、取消其他。
 * - 否則（超時 / Error / null）→ 取消 preferred，改等 fallbacks 中**第一個成功**者；
 *   全部失敗才回 null。
 *   （較快完成但失敗的 fallback 不會 mask 掉較慢成功的 fallback —— 這是對舊實作
 *   select 一次即定生死的修正，語意向文件化意圖看齊。）
 * - 單一 attempt 拋出 exception 視為該路失敗，不會炸掉整場 race。
 *
 * @param context attempts 的 coroutine context；production 預設 [Dispatchers.IO]，
 *        測試可注入 [kotlin.coroutines.EmptyCoroutineContext] 以繼承 virtual time。
 */
class HedgeOrchestrator(
  private val preferred: ProviderModel,
  private val fallbacks: Set<ProviderModel>,
  private val preferredWait: Duration,
  private val context: CoroutineContext = Dispatchers.IO,
) {
  init {
    require(!fallbacks.contains(preferred))
  }

  suspend fun <T : Any> execute(attempt: suspend (ProviderModel) -> Reply<T>?): Reply.Normal<T>? = coroutineScope {

    val allModels = setOf(preferred) + fallbacks

    val deferredMap: Map<ProviderModel, Deferred<Reply<T>?>> = allModels.associateWith { providerModel ->
      async(context + CoroutineName("Hedge-${providerModel.provider}/${providerModel.model}")) {
        try {
          attempt(providerModel)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          logger.error(e) { "Exception during attempt with ${providerModel.provider}/${providerModel.model}" }
          null
        }
      }
    }

    val preferredResult: Reply<T>? = withTimeoutOrNull(preferredWait) {
      deferredMap[preferred]?.await()
    }

    if (preferredResult is Reply.Normal) {
      // preferred 在時限內成功完成 -> 回傳 preferred，並取消其他
      logger.info { "Preferred model $preferred succeeded within the time limit." }
      deferredMap.filterKeys { it != preferred }.values.forEach { it.cancel() }
      preferredResult
    } else {
      // preferred 超時、失敗(Error)或回傳null -> 從 fallbacks 中選擇第一個成功的
      if (preferredResult != null) {
        logger.warn { "Preferred model $preferred failed or returned an error: $preferredResult. Looking for a fallback..." }
      } else {
        logger.warn { "Preferred model $preferred timed out. Looking for a fallback..." }
      }
      // preferred 已出局，立即取消（若還在跑），不再燒 token；也避免 coroutineScope 等它收尾
      deferredMap[preferred]?.cancel()

      val remaining: MutableMap<ProviderModel, Deferred<Reply<T>?>> =
        deferredMap.filterKeys { it in fallbacks }.toMutableMap()

      var winner: Pair<ProviderModel, Reply.Normal<T>>? = null
      while (winner == null && remaining.isNotEmpty()) {
        val (completedModel, reply) = select<Pair<ProviderModel, Reply<T>?>> {
          remaining.forEach { (model, deferred) ->
            deferred.onAwait { res -> model to res }
          }
        }
        remaining.remove(completedModel)
        if (reply is Reply.Normal) {
          winner = completedModel to reply
        } else {
          logger.warn { "Fallback $completedModel failed: $reply" }
        }
      }

      winner?.also { (winnerModel, _) ->
        logger.info { "Using fallback result from $winnerModel" }
        // 取消所有其他仍在執行的任務
        deferredMap.filterKeys { it != winnerModel }.values.forEach { if (it.isActive) it.cancel() }
      }?.second
    }
  }

  companion object {
    val logger = KotlinLogging.logger {}
  }
}
