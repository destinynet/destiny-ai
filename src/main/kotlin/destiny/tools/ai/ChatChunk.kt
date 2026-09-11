/**
 * Created by smallufo on 2026-09-11.
 */
package destiny.tools.ai

/**
 * 串流中的一個事件。終端**一定**是 [Completed] 或 [Failed] 其中之一（正常取消除外 —— 那會拋
 * `CancellationException` 而非 emit 任何東西）。
 *
 * ## 用法
 *
 * ```kotlin
 * val text = StringBuilder()
 * (impl as? IStreamingChatCompletion)?.streamChatComplete(model, messages)?.collect { chunk ->
 *   when (chunk) {
 *     is ChatChunk.Delta          -> { text.append(chunk.text); ui.append(chunk.text) }
 *     is ChatChunk.ReasoningDelta -> ui.showThinking(chunk.think)
 *     is ChatChunk.ToolStarted    -> ui.spinner(chunk.name)
 *     is ChatChunk.ToolFinished   -> ui.spinnerDone(chunk.name)
 *     is ChatChunk.Completed      -> recordUsage(chunk.reply)   // 全文在 text 裡，不在 reply.content
 *     is ChatChunk.Failed         -> ui.error(chunk.error)
 *   }
 * }
 * ```
 *
 * @see IStreamingChatCompletion
 */
sealed class ChatChunk {

  /** 一段文字增量。把所有 [Delta] 依序接起來 == 最終全文。 */
  data class Delta(val text: String) : ChatChunk()

  /**
   * reasoning／thinking 增量（Anthropic extended thinking、DeepSeek R1、`<think>` 區塊…）。
   *
   * **不**計入 [Delta] 的接龍 —— 它是模型的思考歷程，不是回答本身。要不要顯示由呼叫端決定。
   */
  data class ReasoningDelta(val think: String) : ChatChunk()

  /**
   * server 即將執行某個 tool。UI 可據此顯示「正在查詢…」。
   *
   * 執行權仍在 server（同非串流路徑：impl 收到 tool_use 就自己 invoke
   * [IFunctionDeclaration]），本 chunk 只是**通知**，不是要呼叫端去執行。
   */
  data class ToolStarted(val name: String, val arguments: Map<String, Any?>) : ChatChunk()

  /**
   * tool 執行完畢。
   *
   * 刻意**不帶** result payload —— tool 的回傳值是呼叫端自己那支 [IFunctionDeclaration]
   * 的產物，它比這裡更清楚該怎麼處理。[ok] 為 false 代表該 tool 拋了例外或不存在，
   * 錯誤訊息已經以 `is_error` 回給模型讓它自行修正（不會中斷串流）。
   */
  data class ToolFinished(val name: String, val ok: Boolean) : ChatChunk()

  /**
   * 串流正常結束（終端）。
   *
   * ⚠️ [reply] 的 `content` **一律為空字串**。全文已經由 [Delta] 逐塊送過，再重複一份
   * 只會讓呼叫端在「該用 Delta 接龍還是用 `reply.content`」之間猜錯 —— 兩種讀法看起來
   * 都合理，所以一定有人選錯，而且錯的那個在測試裡不會炸（只是內容重複或消失）。
   *
   * 本 chunk 的價值全在 metadata：`provider` / `model` / `inputTokens` / `outputTokens` /
   * `cacheReadTokens` / `cacheCreationTokens` / `duration` / `invokedFunCalls`。
   * 計費與用量統計請讀這裡。
   */
  data class Completed(val reply: Reply.Normal<String>) : ChatChunk()

  /**
   * 串流失敗（終端，其後不會再有任何 chunk）。
   *
   * 刻意保留 [Reply.Error] 原型而非攤平成字串：`Retryable` / `Terminal` 的分類是呼叫端
   * 決定「要不要重來」的依據。
   *
   * ⚠️ **但串流的重試規則與非串流不同**：一旦收過第一顆 [Delta]，就**不該**重試 ——
   * 使用者已經看到半個答案，重跑會讓畫面從頭再來一次。`Retryable` 在串流語境下只對
   * 「還沒吐出任何東西就失敗」有意義（連線失敗、401、429、400…）。impl 端也遵守同一條
   * 規則，不會在已經 emit 過內容之後自行重試。
   */
  data class Failed(val error: Reply.Error) : ChatChunk()
}
