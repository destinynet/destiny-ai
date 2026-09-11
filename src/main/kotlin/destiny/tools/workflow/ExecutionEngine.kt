/**
 * Created by smallufo on 2026-01-18.
 *
 * Unified Workflow Engine - Execution Engine
 */
package destiny.tools.workflow

import mu.KotlinLogging
import destiny.tools.ai.*
import kotlinx.coroutines.*
import java.util.*
import kotlin.time.measureTimedValue

/**
 * 執行引擎介面
 */
interface ExecutionEngine {
  /**
   * 執行生成計畫
   *
   * @param plan 要執行的計畫
   * @param locale 語言環境
   * @param initialContext 初始上下文 —— **兼具續跑語意**：id 已在此的 segment 不會被執行，
   *   直接沿用其值。故 [ExecutionResult.Failed.partialResults] 可原封不動當作下次的
   *   initialContext，已完成的昂貴段落（LLM 讀盤／生圖）不會重付一次錢。
   * @return 執行結果。segment 失敗（含底層元件丟出的**外來** CancellationException，例如某個 impl
   *   內部逾時）一律回 [ExecutionResult.Failed]，並帶著 partialResults（含同層已跑完的兄弟成果）
   *   與已花掉的 token／成本。
   * @throws kotlinx.coroutines.CancellationException 呼叫端取消時原樣拋出（不會轉成 [ExecutionResult.Failed]）。
   *   代價是**回傳值整個消失** —— partialResults 與已經花掉的 USD 一起不見。故**不要**用
   *   `withTimeout(…) { engine.execute(plan) }` 限制 plan 總時長（那是最直覺的寫法，也正是這個陷阱）：
   *   逾時等於把本輪付掉的錢連同可續跑的中間成果一起丟掉。要設上限請用 orchestrator 的
   *   `modelTimeout`（逐次呼叫逾時，走的是 Failed 路徑，帳與成果都保得住）。
   */
  suspend fun <R> execute(
    plan: GenerationPlan<R>,
    locale: Locale = Locale.getDefault(),
    initialContext: Map<SegmentId, SegmentOutput> = emptyMap()
  ): ExecutionResult<R>
}

/**
 * 執行進度監聽器
 */
interface ExecutionProgressListener {
  fun onSegmentStarted(id: SegmentId)
  fun onSegmentCompleted(id: SegmentId, durationMs: Long)
  fun onSegmentFailed(id: SegmentId, error: Throwable)
}

/**
 * 預設執行引擎實作
 *
 * 直接使用 IChatOrchestrator 進行 AI 呼叫，整合現有的：
 * - JSON Schema 指導 AI 輸出格式
 * - PostProcessor 處理（JSON 提取、中文處理等）
 * - 自動反序列化為目標類型
 *
 * 注意：Retry 機制由底層 IChatOrchestrator (如 ResilientChatService) 處理，
 * 此引擎專注於 DAG 執行、並行控制與進度追蹤。
 *
 * @param orchestrator AI 聊天編排器（負責 retry 與 failover）
 * @param postProcessors 後處理器列表（JSON 提取、中文處理等）
 * @param providerImpl 提供者實作函數，用於取得特定 Provider 的 IChatCompletion
 * @param chatOptionsTemplate 預設 ChatOptions（如 maxTokens）
 * @param modelCostService 成本計算服務（可選）；提供時 [ExecutionMetadata.costUsd] 才算得出，
 *   否則僅累計 token、成本為 null
 * @param progressListener 執行進度監聽器（可選）
 * @param imageOrchestrator 生圖編排器（可選）；計畫含 [Segment.ImageSegment] 時**必須**提供，
 *   否則該 segment 執行時拋錯。`IImageGeneration` 與 `IChatCompletion` 是正交 capability
 *   （Replicate 只有前者），故不能共用上面那組 chat 依賴。
 * @param imageProviderImpl provider → 生圖實作（可選，同上）；回 null 代表該 provider 在我方 stack
 *   無法生圖，orchestrator 會換下一家。
 * @param failurePolicy 同層有 segment 失敗時如何處置尚在跑的兄弟；預設 [FailurePolicy.CANCEL_SIBLINGS]。
 */
class DefaultExecutionEngine(
  private val orchestrator: IChatOrchestrator,
  private val postProcessors: List<IPostProcessor>,
  private val providerImpl: (Provider) -> IChatCompletion,
  private val chatOptionsTemplate: ChatOptions = ChatOptions(),
  private val modelCostService: ModelCostService? = null,
  private val progressListener: ExecutionProgressListener? = null,
  private val imageOrchestrator: IImageOrchestrator? = null,
  private val imageProviderImpl: ((Provider) -> IImageGeneration?)? = null,
  private val failurePolicy: FailurePolicy = FailurePolicy.CANCEL_SIBLINGS
) : ExecutionEngine {

  private val logger = KotlinLogging.logger { }

  override suspend fun <R> execute(
    plan: GenerationPlan<R>,
    locale: Locale,
    initialContext: Map<SegmentId, SegmentOutput>
  ): ExecutionResult<R> {
    // 帳（durations / 分錄 / 計數器）全數交給 RunRecorder（全類 thread-safe，
    // 同層 sibling 可並行記入）。本函式剩下的區域狀態只有 context 資料流那兩樣。
    val recorder = RunRecorder()
    // context 刻意不是 thread-safe（MutableSegmentContext 內部是普通 map）：寫入只在層與層之間、
    // 由協調協程單獨進行，同層 sibling 只讀。勿在 layer 內 put —— 那是 data race。
    val context = MutableSegmentContext()
    // 已跑完的 segment 成果暫存區：同層兄弟直接往這裡寫（thread-safe），再由協調協程併入 context。
    // 動機是「跑完的成果不作廢」—— 兄弟失敗時這些產出**錢已經付了**，必須出現在
    // Failed.partialResults 供呼叫端當 initialContext 續跑，不能因為同層有人炸掉就一起蒸發。
    //
    // 這個暫存區**不能**用「把 context 換成 ConcurrentHashMap」取代。除了 thread-safety，分層寫入
    // 還買到**可決定性**：同層 sibling 看不見彼此的產出，故一個誤寫 dependsOn 的 plan 會穩定拋
    // NoSuchElementException；若讓 sibling 中途看得見，同一份 plan 會變成「時而隨機成功」的競態，
    // 那種 bug 幾乎不可能在測試裡抓到。
    val completedOutputs = java.util.concurrent.ConcurrentHashMap<SegmentId, SegmentOutput>()

    // 初始化 context
    initialContext.forEach { (id, output) ->
      context.put(id, output)
    }
    // 打錯的 id 不會有任何 segment 因它被跳過，卻會隨 context.toMap() 流進 partialResults ——
    // 呼叫端看得到那筆值，以為續跑生效，其實整段從頭重跑（重付一次錢）。不拒絕執行，但要留線索。
    (initialContext.keys - plan.segments.map { it.id }.toSet()).takeIf { it.isNotEmpty() }?.also { unknown ->
      logger.warn { "initialContext contains ids not in plan ${plan.planId}: ${unknown.joinToString { it.value }}" }
    }

    /**
     * 把暫存區的成果併入 context。
     *
     * 要守的不變式只有一條：**只能由協調協程呼叫**（context 單一寫入者；暫存區本身 thread-safe）。
     * 至於失敗路徑（catch 站點）為何讀得到完整的暫存區，依據是 `coroutineScope` **即使是在傳播
     * 子協程的例外，也已經 join 完全部子協程** —— 例外浮出來時沒有人還在跑，不會有「兄弟稍後才寫入、
     * flush 卻已經做完」的漏記。
     */
    fun flushCompletedOutputs() = completedOutputs.forEach { (id, output) -> context.put(id, output) }

    /**
     * segment 型別 → 記帳器的「已發起」計數分派（口徑語意見 [RunRecorder.onAiCallsLaunched]）。
     * 本函式交給 [executeSegment] 在**發出請求之前**執行。
     *
     * 注意 `itemsProvider` 因此**每輪被求值兩次**（這裡一次、[executeParallelAiSegment] 內一次）。
     * 現有用法都是純函數（`ctx.get<X>(id).xxx`）故無害；若哪天有人塞進非確定性的 provider，
     * totalAiCalls 會與實際呼叫數對不上。要修得動 [executeParallelAiSegment] 的簽名（items 從外面
     * 傳進去、型別得配 star projection），代價大於現況風險，暫記於此。
     */
    fun countLaunched(segment: Segment) {
      when (segment) {
        is Segment.AiSegment<*> -> recorder.onAiCallsLaunched(1)
        is Segment.ParallelAiSegment<*, *> -> recorder.onAiCallsLaunched(segment.itemsProvider(context).size)
        is Segment.ImageSegment -> recorder.onImageCallLaunched()
        else -> {}
      }
    }

    /** 跑一個 segment，順帶記下耗時與已發起的呼叫次數。 */
    suspend fun runSegment(segment: Segment): SegmentOutput {
      val (result, duration) = measureTimedValue {
        executeSegment(segment, context, locale, recorder, ::countLaunched)
      }
      recorder.onSegmentFinished(segment.id, duration.inWholeMilliseconds)
      completedOutputs[segment.id] = result
      return result
    }

    return try {
      // 按層級執行
      for (layer in plan.executionLayers()) {
        // 有 segment 要跑的層不需要這行 —— 下面的 coroutineScope/async 本身就是取消檢查點
        // （已取消時子協程根本不會開始跑）。這行守的是**整層都被跳過**的續跑路徑：`continue`
        // 一路到底、組裝、回 Success，全程沒有任何 suspension point，呼叫端早已取消也看不出來。
        currentCoroutineContext().ensureActive()

        // 已在 context 內者（initialContext 或上次執行回傳的 partialResults）直接沿用，不重跑 ——
        // 這就是續跑機制：昂貴的前段（LLM 讀盤／生圖）不會因為後段失敗而重付一次錢。
        // 跳過的 segment 自然不會進 segmentDurations、也不計入 *Calls（runSegment 根本沒被呼叫），
        // 與「已發起」的計數口徑一致。
        val pending = layer.filter { !context.isCompleted(it.id) }
        // 純短路（空的 pending 跑下去也是空迴圈），正確性不依賴它
        if (pending.isEmpty()) continue
        if (pending.size > 1) {
          // 以 pending 而非 layer 判斷：續跑時整層只剩一支要跑，實際並行度是 1，不該記成一次並行。
          recorder.onParallelLayer()
        }

        runLayer(pending) { runSegment(it) }

        // 本層（含先前各層）跑完的成果併入 context，供後續層讀取
        flushCompletedOutputs()
      }

      // 組裝最終結果
      ExecutionResult.Success(plan.assembler(context), recorder.buildMetadata())

    } catch (e: Exception) {
      // 只有「我們自己被取消」才原樣傳播（ensureActive 會拋）——
      // 偽裝成 ExecutionResult.Failed 回給呼叫端，會讓結構化併發語意壞掉。
      currentCoroutineContext().ensureActive()
      logger.error(e) { "Execution failed" }

      // 嘗試找出失敗的 segment
      val failedSegmentId = when (e) {
        is SegmentExecutionException -> e.segmentId
        else -> SegmentId("unknown")
      }

      // 失敗那層可能有兄弟已經跑完（且已付費）；那些成果同樣要進 partialResults
      flushCompletedOutputs()

      ExecutionResult.Failed(
        failedSegment = failedSegmentId,
        error = e,
        partialResults = context.toMap(),
        metadata = recorder.buildMetadata()
      )
    }
  }

  /**
   * 依 [failurePolicy] 跑完一層的 pending segments —— 這裡只管「同層併發政策」；
   * 續跑過濾、取消檢查、成果 flush 都是 [execute] 主流程的事。
   *
   * 每層一個 coroutineScope（而非整段一個 supervisorScope），關鍵在於 scope 回來時子協程
   * **必定全部結束**，故之後結算 usage 不會漏記。舊版 supervisorScope + awaitAll 在失敗當下
   * 就結算，兄弟可能還沒把用量記進 recorder（實測：兄弟的 $0.04 已付款，metadata.costUsd
   * 卻是 null）—— 那是結構性的漏帳，不是靠補 sleep 能修的。詳見 ExecutionEngineFailurePolicyTest。
   *
   * @param runSegment 由 [execute] 傳入的 closure（捕捉了 context / recorder / completedOutputs）。
   */
  private suspend fun runLayer(
    pending: List<Segment>,
    runSegment: suspend (Segment) -> SegmentOutput
  ) {
    when (failurePolicy) {
      // 子協程一失敗，scope 即取消其餘兄弟（並 join 它們）。
      FailurePolicy.CANCEL_SIBLINGS -> coroutineScope {
        pending.map { segment -> async { runSegment(segment) } }.awaitAll()
      }

      // 每支各自包成 Result ⇒ 沒有子協程真的「失敗」⇒ scope 不會取消任何人，必然全部跑完；
      // 全部落地後才挑出失敗往外拋。（把 coroutineScope 換成 supervisorScope 是無效的
      // 做法：awaitAll 在 block 內 rethrow 一樣會取消兄弟。）
      FailurePolicy.AWAIT_SIBLINGS -> {
        val settled = coroutineScope {
          pending.map { segment -> async { runCatchingCancellable { runSegment(segment) } } }.awaitAll()
        }
        // 其餘失敗掛成 suppressed —— 本政策的精神就是「等兄弟跑完好把資訊收齊」，丟掉就自相矛盾。
        // 且它們沒有別的出口：progressListener 在四個 production 建構點都沒傳（預設 null），
        // logger.error 與 Failed.error 只帶被 rethrow 的那一支。同層三張圖，一支被 content policy
        // 擋、一支 provider 5xx，維運只看到一支會誤判成單一 provider 問題。
        val failures = settled.mapNotNull { it.exceptionOrNull() }
        failures.firstOrNull()?.also { first ->
          failures.drop(1).forEach { first.addSuppressed(it) }
          throw first
        }
      }
    }
  }

  /**
   * @param countLaunched 計入「已發起」呼叫數；刻意在 try **之內**呼叫 —— 它會求值使用者提供的
   *   `itemsProvider`（典型內容 `ctx.get<X>(id)`，dependsOn 寫錯或續跑餵進型別不符的值就會拋），
   *   那支例外必須跟其他 segment 例外走同一條路，否則 `failedSegment` 落成 `unknown`、
   *   progressListener 也收不到失敗事件。刻意**不給**預設值：日後多一個呼叫端時，計數要不要記
   *   得由那個人明講，不能像上一版那樣悄悄漏掉。
   */
  private suspend fun executeSegment(
    segment: Segment,
    context: SegmentContext,
    locale: Locale,
    recorder: RunRecorder,
    countLaunched: (Segment) -> Unit
  ): SegmentOutput {
    progressListener?.onSegmentStarted(segment.id)

    return try {
      countLaunched(segment)
      when (segment) {
        is Segment.AiSegment<*> -> executeAiSegment(segment, context, locale, recorder)
        is Segment.ParallelAiSegment<*, *> -> executeParallelAiSegment(segment, context, locale, recorder)
        is Segment.ImageSegment -> executeImageSegment(segment, context, recorder)
        is Segment.ComputeSegment -> executeComputeSegment(segment, context)
        is Segment.StaticSegment -> segment.content
      }.also { result ->
        progressListener?.onSegmentCompleted(segment.id, 0)
        logger.debug { "Segment ${segment.id} completed" }
      }
    } catch (e: Exception) {
      // 只有「我們自己被取消」才原樣傳播（ensureActive 會拋）。取消不是失敗：包成
      // SegmentExecutionException 之外，onSegmentFailed 還會噴出一筆假的失敗事件，污染監控／告警。
      //
      // 反過來說，這裡刻意**不**無條件 rethrow CancellationException。外來的取消
      // ——例如某個 impl 內部 withTimeout 逾時、或呼叫到已取消的 Future——代表我們自己還活著，
      // 若跟著往外拋，整個 ExecutionResult 會消失，連同 partialResults 與**已經付過錢的
      // token／USD 成本**。那等於在這裡開一個新的漏帳出口，與本輪修正的主題背道而馳。
      // 故：外來取消一律當成一般失敗處理。修改前請先看 ExecutionEngineCancellationTest。
      currentCoroutineContext().ensureActive()
      progressListener?.onSegmentFailed(segment.id, e)
      throw SegmentExecutionException(segment.id, e)
    }
  }

  @Suppress("UNCHECKED_CAST")
  private suspend fun <O : SegmentOutput> executeAiSegment(
    segment: Segment.AiSegment<O>,
    context: SegmentContext,
    locale: Locale,
    recorder: RunRecorder
  ): SegmentOutput {
    val input = segment.inputBuilder(context)
    val prompt = segment.promptBuilder(input, context)

    val formatSpec = segment.formatSpec
      ?: throw IllegalStateException("Segment ${segment.id} must have formatSpec for DefaultExecutionEngine")

    val reply = orchestrator.chatComplete(
      formatSpec = formatSpec,
      message = prompt,
      postProcessors = postProcessors,
      locale = locale,
      funCalls = segment.funCalls,
      chatOptionsTemplate = chatOptionsTemplate,
      providerImpl = providerImpl
    ) ?: throw IllegalStateException("Orchestrator returned null for segment ${segment.id}")

    recorder.record(reply.toCallUsage(segment.id))
    return reply.content as SegmentOutput
  }

  @Suppress("UNCHECKED_CAST")
  private suspend fun <T, O : SegmentOutput> executeParallelAiSegment(
    segment: Segment.ParallelAiSegment<T, O>,
    context: SegmentContext,
    locale: Locale,
    recorder: RunRecorder
  ): SegmentOutput = coroutineScope {
    val items = segment.itemsProvider(context)
    val expectedCount = items.size

    val formatSpec = segment.formatSpec
      ?: throw IllegalStateException("ParallelAiSegment ${segment.id} must have formatSpec for DefaultExecutionEngine")

    val results = items.mapIndexed { index, item ->
      async {
        try {
          val input = segment.itemInputBuilder(item, context)
          val prompt = segment.promptBuilder(input, context)

          val reply = orchestrator.chatComplete(
            formatSpec = formatSpec,
            message = prompt,
            postProcessors = postProcessors,
            locale = locale,
            funCalls = emptySet(),
            chatOptionsTemplate = chatOptionsTemplate,
            providerImpl = providerImpl
          ) ?: throw IllegalStateException("Orchestrator returned null for parallel item $index")

          recorder.record(reply.toCallUsage(segment.id))
          Result.success(reply.content as SegmentOutput)
        } catch (e: Exception) {
          // 同上：我們自己被取消時不得降級成 Result.failure —— 那會混進 FailedItem，
          // validator 若寬鬆，整段甚至會「成功」而少了幾項，沒有人發現。
          // 這一道目前是 defence in depth：外層的 coroutineScope 在自身被取消時本來就會丟棄
          // block 的回傳值改拋取消，故拿掉它現有測試仍全綠（實測過）。留著是為了擋住日後改動
          // ——例如有人把 coroutineScope 換成 supervisorScope——讓取消重新被悄悄吞掉。
          currentCoroutineContext().ensureActive()
          Result.failure<SegmentOutput>(e)
        }
      }
    }.awaitAll()

    val successful = results.filter { it.isSuccess }.map { it.getOrThrow() }
    val failed = results.mapIndexedNotNull { index, result ->
      if (result.isFailure) {
        val cause = result.exceptionOrNull()
        FailedItem(index, cause ?: Exception("Unknown error"))
      } else null
    }

    // 驗證結果
    val validator = segment.resultValidator
    if (validator != null) {
      val validationResult = validator.validate(expectedCount, successful.size, successful)
      if (validationResult is ValidationResult.Rejected) {
        throw ValidationException(segment.id, validationResult.reason)
      }
    }

    ParallelOutput(successful as List<Nothing>, failed)
  }

  /**
   * 生圖區段：prompt 由 context 組成 → 交給 image orchestrator（自帶 retry / failover）。
   * 無 formatSpec、無反序列化，回傳即 [ImageOutput]。
   */
  private suspend fun executeImageSegment(
    segment: Segment.ImageSegment,
    context: SegmentContext,
    recorder: RunRecorder
  ): SegmentOutput {
    val orch = imageOrchestrator
      ?: throw IllegalStateException("Segment ${segment.id} is an ImageSegment but engine has no imageOrchestrator")
    val resolve = imageProviderImpl
      ?: throw IllegalStateException("Segment ${segment.id} is an ImageSegment but engine has no imageProviderImpl")

    val prompt = segment.promptBuilder(context)
    logger.debug { "ImageSegment ${segment.id} prompt: $prompt" }

    val reply = orch.generateImage(prompt, segment.options, resolve)
      ?: throw IllegalStateException("Image orchestrator returned null for segment ${segment.id}")

    recorder.record(reply.toImageCallUsage(segment.id, segment.options.toSpec()))
    return ImageOutput(reply.content)
  }

  private fun executeComputeSegment(
    segment: Segment.ComputeSegment,
    context: SegmentContext
  ): SegmentOutput {
    return segment.compute(context)
  }

  /** 單通 AI 呼叫的用量快照；成本於擷取當下依該 reply 的 provider/model pricing 算好（事後會失真）。 */
  private fun Reply.Normal<*>.toCallUsage(segmentId: SegmentId): CallUsage = CallUsage(
    segmentId = segmentId,
    provider = provider.name,
    model = model,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cacheCreationTokens = cacheCreationTokens,
    cacheReadTokens = cacheReadTokens,
    costUsd = modelCostService?.cost(this)
  )

  /**
   * 生圖呼叫的用量快照 —— 成本依 provider 的計價模型分兩條路：
   *
   * 1. **回應帶 token**（chat-native 生圖，如 Gemini `generateContent`）：走 [toCallUsage] 的 token 精算。
   *    回應帶的是真實 token 數，比 [ImagePricing.estimate] 的事前預估準（後者也只是查表得同一組數字）。
   * 2. **回應無 token**（per-image / per-MP 計價，如 Replicate）：token 制算出來會是誤導性的 0，
   *    故改以 [ImagePricing.estimate] × 實際張數；查不到計價就留 null（不阻斷出圖）。
   *
   * @param spec 該次請求正規化後的規格（計價查表的 key）
   */
  private fun Reply.Normal<List<GeneratedImage>>.toImageCallUsage(segmentId: SegmentId, spec: ImageSpec): CallUsage {
    val reportsTokens = inputTokens != null || outputTokens != null
    if (reportsTokens) return toCallUsage(segmentId)

    val cost = modelCostService?.imagePricing(provider, model)?.estimate(spec)?.times(content.size)
    return CallUsage(
      segmentId = segmentId,
      provider = provider.name,
      model = model,
      inputTokens = null,
      outputTokens = null,
      cacheCreationTokens = null,
      cacheReadTokens = null,
      costUsd = cost
    )
  }

}

/**
 * 同層有 segment 失敗時，如何處置**還在跑**的兄弟。
 *
 * 這是一組取捨，沒有普世正解：
 * - **省錢**：整個 plan 已註定失敗，兄弟的產出多半會被丟棄，讓它跑完等於白付 API 費用
 *   （生圖尤其痛 —— 一張 $0.04 起跳）。
 * - **保住成果**：反過來說，兄弟若已快完成，取消它就丟掉了「下次重跑可以直接沿用」的中間結果；
 *   對可續跑（把 partialResults 餵回 `initialContext`）的長流程而言，省下的重算可能遠比那筆 API 費貴。
 *
 * 兩者共通的保證：已寫入的用量一定會記進 [ExecutionMetadata]，已跑完的 segment 成果一定會出現在
 * [ExecutionResult.Failed.partialResults] —— 政策管的是「還在跑的兄弟要不要取消」，
 * 不是「跑完的東西要不要作廢」。（前提是 `execute` 有回傳：呼叫端取消或 [Error] 逃逸時
 * 兩者都拿不到，見 [ExecutionEngine.execute] 的 @throws。）
 */
enum class FailurePolicy {
  /**
   * fail-fast：立刻取消同層兄弟，盡快回報 [ExecutionResult.Failed]。
   *
   * 注意「取消」不等於「沒付錢」—— 已經送出的 API 請求，對方照樣可能計費。
   */
  CANCEL_SIBLINGS,

  /**
   * 等同層全部結束才回報失敗。錢一樣會花掉（甚至更多），換到的是兄弟的產出**完整落地**：
   * 成本記入 metadata、成果留在 partialResults 可直接續跑，同層其他失敗也一併掛成 suppressed。
   *
   * 適用於單價高、重算比重跑貴的流程（生圖一張 $0.04 起跳，讀盤一次 $0.03 起跳）。
   *
   * 代價除了錢還有**延遲**：失敗回報從「立即」變成「等該層最慢的兄弟」，且無上限 ——
   * 生圖 orchestrator 自帶 retry / failover（`EwImageryService` 是 120s／模型），最慢的兄弟可能
   * 拖上好幾分鐘。使用者在前面等結果的流程，選這個政策前要先確認這段延遲吞得下。
   *
   * 另注意 [ExecutionResult.Failed.failedSegment] 的挑法隨政策而異：本政策取**宣告順序**第一支失敗，
   * [CANCEL_SIBLINGS] 取**時間上**第一支。同一個 plan、同一組失敗，兩者回報的 segment 可能不同。
   */
  AWAIT_SIBLINGS
}

/**
 * 同 [runCatching]，但兩種東西不吞：
 *
 * - [Error]（故只 catch [Exception]，不是 Throwable）：OOM 這類 JVM 級致命錯誤若被包成 Result，
 *   兄弟會繼續在已經 OOM 的 JVM 上生圖；更糟的是若另一支先丟了普通 Exception，那個 Error 會被
 *   直接丟棄，`execute` 回一個看起來正常的 [ExecutionResult.Failed]。讓它穿出去中止整個 plan 才對。
 * - [CancellationException]：取消不是「失敗」，不可降級成 Result。這一道是 **defence in depth**：
 *   拿掉它現有測試仍全綠（實測過），因為 (a) segment 自己丟的取消早被 `executeSegment` 包成
 *   [SegmentExecutionException]，到不了這裡；(b) 真的是我們被取消時，子協程的 job 已取消，
 *   `await()` 無論 block 回傳什麼都會拋取消。留著是為了擋住日後改動讓取消重新被悄悄吞掉。
 */
private suspend inline fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> = try {
  Result.success(block())
} catch (e: CancellationException) {
  throw e
} catch (e: Exception) {
  Result.failure(e)
}

/**
 * Segment 執行例外
 */
class SegmentExecutionException(
  val segmentId: SegmentId,
  cause: Throwable
) : RuntimeException("Segment $segmentId execution failed", cause)

/**
 * 驗證例外
 */
class ValidationException(
  val segmentId: SegmentId,
  val reason: String
) : RuntimeException("Segment $segmentId validation failed: $reason")
