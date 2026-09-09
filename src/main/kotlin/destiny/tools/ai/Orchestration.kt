/**
 * Created by smallufo on 2026-09-09.
 */
package destiny.tools.ai


/**
 * orchestrator 一次完整編排的結果 —— 成功就是那份 [Reply.Normal]，失敗則**帶著每一次嘗試的分類**。
 *
 * ## 為什麼不是 `Reply.Normal<T>?`
 *
 * `null` 把三種完全不同的情況壓成同一個值：
 *
 * | 每一次嘗試都是… | 意思 | 該做的事 |
 * |---|---|---|
 * | [Reply.Error.DeserializationFailure] | schema 或 serializer 的 bug，換誰都一樣 | 回報給人，別再重試 |
 * | [Reply.Error.Retryable]（RateLimited / Busy） | provider 暫時不行 | 稍後再試 |
 * | [Reply.Error.MaxTokensReached] | 輸出太長 | 換 model 或加大 maxTokens |
 *
 * [ResilientOrchestrator.execute] 內部本來就逐一 `when (reply)` 分類，只是分類完就丟掉。
 * [Exhausted.attempts] 把它們留下來，[Exhausted.describe] 給 log 與例外訊息用。
 *
 * 舊的 `Reply.Normal<T>?` API 仍在（[successOrNull]），呼叫端可以逐個換過去。
 */
sealed class Orchestration<out T : Any> {

  data class Success<T : Any>(val reply: Reply.Normal<T>) : Orchestration<T>()

  /**
   * @param attempts 每次呼叫一筆，含 loop 編號；空 list 代表根本沒打出去（見 [note]）
   * @param note     沒有任何嘗試、或提早放棄的原因（沒有 provider、全部 InvalidApiKey…）
   */
  data class Exhausted(val attempts: List<Attempt>, val note: String? = null) : Orchestration<Nothing>() {

    val errors: List<Reply.Error> get() = attempts.mapNotNull { it.error }

    /** 每一次都是 [Reply.Error.DeserializationFailure] —— 確定性的失敗，重試無用 */
    val allDeserializationFailures: Boolean
      get() = attempts.isNotEmpty() && attempts.all { it.error is Reply.Error.DeserializationFailure }

    /** 每一次都是 [Reply.Error.Retryable] —— provider 暫時不行，稍後再試有機會 */
    val allRetryable: Boolean
      get() = attempts.isNotEmpty() && attempts.all { it.error is Reply.Error.Retryable }

    fun describe(): String = buildString {
      append("exhausted after ${attempts.size} attempt(s)")
      note?.let { append(" ($it)") }
      attempts.forEach { a ->
        append("\n  loop ${a.loop} ${a.providerModel.provider}/${a.providerModel.model}: ")
        append(
          when {
            a.error != null  -> a.error::class.simpleName + a.error.detail()
            a.thrown != null -> "threw ${a.thrown::class.simpleName}: ${a.thrown.message}"
            else             -> a.note ?: "no reply"
          }
        )
      }
    }

    private fun Reply.Error.detail(): String = when (this) {
      is Reply.Error.DeserializationFailure -> " (${errorMessage.take(160)})"
      is Reply.Error.TooLong                -> " (${message.take(160)})"
      is Reply.Error.Unknown                -> " (${message.take(160)})"
      is Reply.Error.RateLimited            -> retryAfter?.let { " (retryAfter=$it)" } ?: ""
      else                                  -> ""
    }
  }

  /**
   * 一次對某個 (provider, model) 的嘗試。三個描述欄位最多一個非 null：
   * [error] 是 provider 回的分類結果、[thrown] 是 attempt 拋出的例外、[note] 是其他情況（超時、回 null）。
   */
  data class Attempt(
    val providerModel: ProviderModel,
    val error: Reply.Error? = null,
    val thrown: Throwable? = null,
    val note: String? = null,
    val loop: Int = 1,
  )

  fun successOrNull(): Reply.Normal<out T>? = (this as? Success<T>)?.reply
}
