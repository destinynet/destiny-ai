/**
 * Created by smallufo on 2026-07-24.
 */
package destiny.tools.ai

import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 一張生成的圖片。
 *
 * @param mimeType   e.g. "image/png" / "image/jpeg" / "image/webp"
 * @param base64Data base64 編碼的圖片內容。採 base64（而非 ByteArray）：
 *                   可直接回灌 [Content.MimeContent] 作為 vision 輸入（image editing loop），
 *                   也避免 data class 的 equals/hashCode 對 ByteArray 失效問題。
 */
data class GeneratedImage(
  val mimeType: String,
  val base64Data: String,
) {
  fun bytes(): ByteArray = Base64.getDecoder().decode(base64Data)

  /** 回灌為 chat 的 vision 輸入 */
  fun toMimeContent(): Content.MimeContent = Content.MimeContent(mimeType, base64Data)

  override fun toString(): String {
    return "GeneratedImage(mimeType='$mimeType', base64Data=${base64Data.length} chars)"
  }
}

/**
 * 圖片生成參數 —— provider-agnostic；各 impl 自行 mapping 到具體 API 欄位，不支援的欄位忽略。
 *
 * @param n       要生成幾張；預設 1。
 * @param size    尺寸 / 長寬比 hint（e.g. "1024x1024"、"16:9"）；null = provider 預設。
 * @param quality 品質 hint（e.g. "standard" / "hd"）；null = provider 預設。
 */
data class ImageOptions(
  val n: Int = 1,
  val size: String? = null,
  val quality: String? = null,
) {
  init {
    require(n >= 1) { "n must be >= 1" }
  }
}

/**
 * Text-to-image 的 **capability interface** —— 與 [IChatCompletion] 分離，
 * 只有操作上真能生圖的 impl 才實作（fail-closed，比照 [IChatCompletion.supportsVisionInput] 的哲學）。
 *
 * Caller 的能力判別為兩層：
 * 1. model 理論能力：[ModelInfo.capabilities] 含 [Capability.IMAGE_GEN]
 * 2. 我方 stack 結構性前提：該 provider 的 impl `is IImageGeneration`
 *
 * 各 impl 自行決定打哪個 endpoint：
 * - 獨立 endpoint 型（OpenAI `/v1/images/generations`、xAI）→ 專屬 request/response
 * - chat-native 型（Gemini `generateContent` + responseModalities=["TEXT","IMAGE"]）→ 解析 inlineData parts
 *
 * 回傳沿用 [Reply]：錯誤分類（Retryable / Terminal）與 orchestrator
 * （[ResilientOrchestrator] / [HedgeOrchestrator]）直接複用。
 */
interface IImageGeneration {

  val provider: Provider

  suspend fun generateImage(
    model: String,
    prompt: String,
    options: ImageOptions = ImageOptions(),
    timeout: Duration = 120.seconds,
  ): Reply<List<GeneratedImage>>
}
