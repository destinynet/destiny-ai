/**
 * Created by smallufo on 2026-09-11.
 */
package destiny.tools.ai

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 串流版 chat completion —— 逐塊吐出模型的輸出，而不是等整份回覆生成完才回傳。
 *
 * ## 這是 opt-in 的能力，不是每家 provider 都有
 *
 * 刻意做成**獨立介面**，而不是在 [IChatCompletion] 上加一個帶預設實作的方法。帶預設的話
 * 每個 impl 都會「聲稱」會串流（預設實作只是把一次性回覆包成單顆 chunk），呼叫端無從分辨
 * 真假，UI 會在沒有人察覺的情況下退化成「等 40 秒吐出一大塊」—— 正好壞在串流本來要解決
 * 的那個點上。同 [IChatCompletion.supportsVisionInput] 的 fail-closed 慣例。
 *
 * 所以要這樣問：
 *
 * ```kotlin
 * val streaming = impl as? IStreamingChatCompletion
 *   ?: return nonStreamingFallback()   // 這家不支援，呼叫端自己決定怎麼辦
 * ```
 *
 * ## ⚠️ 本介面**不經過** [IChatOrchestrator]，沒有 failover
 *
 * 串流與 orchestrator 的兩個政策在語意上互斥：
 *
 * - [ResilientChatService] 的「失敗就換下一家」要等整份回覆收完（post-process → JSON 抽取
 *   → 反序列化 → 驗證）才判得出成敗。那時 token 早就送到使用者眼前了，收不回來；
 *   換一家重跑只會讓畫面出現兩份不同的答案。
 * - [HedgeChatService] 同時發給多家、取最快成功的那份。串流下「要 emit 哪一條」沒有答案 ——
 *   勝負揭曉前只能緩衝，而緩衝就不是串流了。
 *
 * 所以呼叫本介面 = **你自己挑定一個 (provider, model)，失敗就是失敗**。這不是尚未實作的
 * 限制，是串流的本質。需要 failover 請改用 [IChatOrchestrator]（非串流）。
 *
 * ## 與 [IChatCompletion.typedChatComplete] 的差異
 *
 * 沒有 `formatSpec`、沒有 `postProcessors`：
 *
 * - **結構化輸出**要完整的 JSON 才解得開，逐塊送出去的半截 JSON（`{"title": "命`）
 *   對使用者比轉圈圈更沒用。要 structured output 請用 [IChatCompletion.typedChatComplete]。
 * - **[IPostProcessor] 吃全文**。以簡繁轉換為例，那是詞組級的轉換，逐塊套用會在 chunk
 *   邊界切斷詞組而產生與整段轉換不同的結果。與其收一個會被靜默忽略或錯誤套用的參數，
 *   不如不收 —— 需要後處理請在 collect 完之後對全文跑一次。
 *
 * @see ChatChunk
 */
interface IStreamingChatCompletion : IChatCompletion {

  /**
   * @param idleTimeout **兩個 chunk 之間**的最長間隔，不是整場的時間上限。
   *
   *   這個區別是刻意的：串流的正常型態就是「連線開很久」，用 request timeout（涵蓋讀完
   *   整個 body）會把長回覆從中間砍斷。idle timeout 問的是「對方是不是死了」，那才是
   *   串流真正需要判斷的事。
   *
   * @param maxFunctionCallDepth tool 往返的深度上限。串流下一輪 tool 往返 = 一條新的 HTTP
   *   連線，但對呼叫端仍是同一個 [Flow]。超限時 emit [ChatChunk.Failed]
   *   （`Reply.Error.FunctionCallLoopExceeded`）。
   *
   * @return **冷** [Flow]：每次 collect 都會真的發出一次新請求（collect 兩次 = 付兩次錢）。
   *   collect 被取消時底層 HTTP 連線一併關閉。終端一定是 [ChatChunk.Completed] 或
   *   [ChatChunk.Failed]。
   */
  fun streamChatComplete(
    model: String,
    messages: List<Msg>,
    chatOptions: ChatOptions = ChatOptions(),
    funCalls: Set<IFunctionDeclaration> = emptySet(),
    user: String? = null,
    idleTimeout: Duration = 60.seconds,
    maxFunctionCallDepth: Int = IChatCompletion.DEFAULT_MAX_FUNCTION_CALL_DEPTH,
  ): Flow<ChatChunk>

  /** 單則 user 訊息的便捷版。 */
  fun streamChatComplete(
    model: String,
    message: String,
    chatOptions: ChatOptions = ChatOptions(),
  ): Flow<ChatChunk> = streamChatComplete(model, listOf(Msg(Role.USER, message)), chatOptions)
}
