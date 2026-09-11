/**
 * Created by smallufo on 2026-07-25.
 *
 * workflow 引擎測試共用的假件 —— 全部離線，不打真 API。
 * 抽出來的動機：`ExecutionEngineImageTest` / `ExecutionEngineCancellationTest` 原本各自複製了
 * 一份幾乎逐字相同的假 impl 與 engine builder，後續測試檔只會再複製更多份。
 */
package destiny.tools.workflow

import destiny.tools.ai.*
import destiny.tools.ai.model.FormatSpec
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import java.util.*

/** 1×1 透明 png 的 base64。 */
const val STUB_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

const val FAKE_IMAGE_MODEL = "fake-image-model"

const val FAKE_TEXT_MODEL = "fake-text-model"

/**
 * 可設定的假生圖 impl：回 [ImageOptions.n] 張 stub 圖，並記下收到的每一支 prompt。
 *
 * @param inputTokens/[outputTokens] 模擬 chat-native 生圖（回應帶 token）；預設 null 模擬 per-image 計價。
 * @param error 設定後直接回這個錯誤回應（而非拋例外）。
 * @param delayMs 回應前的延遲，讓測試有機會在中途取消。
 * @param entered 一進入生圖就 complete —— 測試據此確認「真的開跑了」才下取消，
 *   否則取消可能早於 segment 啟動（engine 建構時的首次 `mockk()` 就要花上百毫秒），測試會變成空測。
 * @param onPrompt 依 prompt 決定要不要搞事的鉤子（丟例外、自帶 withTimeout 逾時…）；在延遲之前呼叫。
 */
class FakeImageGeneration(
  override val provider: Provider = Provider.GEMINI,
  private val model: String = FAKE_IMAGE_MODEL,
  private val png: String = STUB_PNG,
  private val inputTokens: Int? = null,
  private val outputTokens: Int? = null,
  private val error: Reply.Error? = null,
  private val delayMs: Long = 0,
  private val entered: CompletableDeferred<Unit>? = null,
  private val onPrompt: (suspend (String) -> Unit)? = null,
) : IImageGeneration {

  /** **開跑**的 prompt（一進入就記）。同層並行的 ImageSegment 會併發寫入，故用 thread-safe list（否則測試偶發 flaky）。 */
  val prompts: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

  /**
   * **跑完**（真的產出回應）的 prompt。與 [prompts] 的差別正是 fail-fast 測試的觀測點：
   * 兄弟被取消時「開跑了但沒跑完」——只看 [prompts] 分不出來，會誤判成沒省到錢。
   */
  val finished: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

  /** 呼叫次數 = 收到的 prompt 數。 */
  val callCount: Int get() = prompts.size

  override suspend fun generateImage(
    model: String,
    prompt: String,
    options: ImageOptions,
    timeout: kotlin.time.Duration
  ): Reply<List<GeneratedImage>> {
    prompts.add(prompt)
    entered?.complete(Unit)
    onPrompt?.invoke(prompt)
    if (delayMs > 0) delay(delayMs)
    finished.add(prompt)
    error?.also { return it }
    val images = (1..options.n).map { GeneratedImage("image/png", png) }
    return Reply.Normal(images, null, provider, this.model, inputTokens = inputTokens, outputTokens = outputTokens)
  }
}

/**
 * 假生圖 orchestrator：直接呼叫解析出的 impl，不做 retry / failover
 * （那是 orchestrator 自己的測試範圍）。解析不到 impl 就回 null。
 */
class FakeImageOrchestrator(private val provider: Provider) : IImageOrchestrator {
  override suspend fun generateImage(
    prompt: String,
    options: ImageOptions,
    providerImpl: (Provider) -> IImageGeneration?
  ): Reply.Normal<List<GeneratedImage>>? {
    val impl = providerImpl.invoke(provider) ?: return null
    @Suppress("UNCHECKED_CAST")
    return impl.generateImage("ignored", prompt, options) as? Reply.Normal<List<GeneratedImage>>
  }
}

/** AI 文字段的假輸出。`@Serializable` 只為滿足 [FormatSpec.of]；假 orchestrator 直接回物件，不經反序列化。 */
@Serializable
data class TextOutput(val text: String) : SegmentOutput

/** 文字段用的 input（AiSegment 的 inputBuilder 必須回一個 [SegmentInput]）。 */
data class TextInput(val prompt: String) : SegmentInput

val TEXT_FORMAT_SPEC: FormatSpec<TextOutput> = FormatSpec.of<TextOutput>("TextOutput", "text output")

/**
 * 假 chat orchestrator：回固定 [TextOutput] 與可設定的 token 數（供成本斷言用），並記下收到的每一支 prompt。
 * 不做 retry / failover，那是 orchestrator 自己的測試範圍。
 */
class FakeChatOrchestrator(
  private val provider: Provider = Provider.CLAUDE,
  private val model: String = FAKE_TEXT_MODEL,
  private val inputTokens: Int? = null,
  private val outputTokens: Int? = null,
  private val text: String = "fake reply"
) : IChatOrchestrator {

  /** 同層並行的 AiSegment / 並行 item 會併發寫入，故用 thread-safe list。 */
  val prompts: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

  val callCount: Int get() = prompts.size

  @Suppress("UNCHECKED_CAST")
  override suspend fun <T : Any> chatComplete(
    formatSpec: FormatSpec<out T>,
    messages: List<Msg>,
    postProcessors: List<IPostProcessor>,
    locale: Locale,
    funCalls: Set<IFunctionDeclaration>,
    chatOptionsTemplate: ChatOptions,
    providerImpl: (Provider) -> IChatCompletion
  ): Reply.Normal<out T> {
    prompts.add(messages.joinToString("\n") { it.stringContents })
    return Reply.Normal(
      TextOutput(text) as T, null, provider, model,
      inputTokens = inputTokens, outputTokens = outputTokens
    )
  }
}

/** 記錄引擎回報的進度事件，供「不該回報假失敗／假完成」這類斷言使用。 */
class RecordingListener : ExecutionProgressListener {
  val started: MutableList<SegmentId> = java.util.concurrent.CopyOnWriteArrayList()
  val completed: MutableList<SegmentId> = java.util.concurrent.CopyOnWriteArrayList()
  val failures: MutableList<Throwable> = java.util.concurrent.CopyOnWriteArrayList()

  override fun onSegmentStarted(id: SegmentId) {
    started.add(id)
  }

  override fun onSegmentCompleted(id: SegmentId, durationMs: Long) {
    completed.add(id)
  }

  override fun onSegmentFailed(id: SegmentId, error: Throwable) {
    failures.add(error)
  }
}

/**
 * 生圖測試用的引擎：[imageImpl] 只在 provider 相符時回傳，藉此模擬「該 provider 在我方 stack 無法生圖」。
 * [chatOrchestrator] 不給時給 mock（純 image plan 碰不到它）；文字段與生圖段混編的計畫才需要傳。
 *
 * 刻意**不**提供 `failurePolicy` 參數 —— 一律走 [DefaultExecutionEngine] 自己的預設。假件若代為
 * 轉傳，「預設政策是什麼」就變成假件說了算；要明示政策的測試請就地建構引擎，讓那個選擇看得見。
 */
fun imageEngine(
  imageOrchestrator: IImageOrchestrator?,
  imageImpl: IImageGeneration?,
  modelCostService: ModelCostService? = null,
  progressListener: ExecutionProgressListener? = null,
  chatOrchestrator: IChatOrchestrator? = null
) = DefaultExecutionEngine(
  orchestrator = chatOrchestrator ?: mockk(),
  postProcessors = emptyList(),
  providerImpl = { mockk() },
  modelCostService = modelCostService,
  progressListener = progressListener,
  imageOrchestrator = imageOrchestrator,
  imageProviderImpl = imageImpl?.let { impl -> { p: Provider -> impl.takeIf { p == impl.provider } } }
)
