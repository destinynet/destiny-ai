/**
 * Created by smallufo on 2026-01-18.
 *
 * Unified Workflow Engine - Segment Output Types
 */
package destiny.tools.workflow

import destiny.tools.ai.GeneratedImage

/**
 * Segment 輸出基礎介面
 *
 * 所有 segment 的輸出都實作此介面，
 * 允許在 SegmentContext 中統一存儲和類型安全取得。
 */
interface SegmentOutput

/**
 * 並行執行的結果
 *
 * 用於 ParallelAiSegment 的輸出，包含成功和失敗的項目
 */
data class ParallelOutput<T : SegmentOutput>(
  /** 成功的結果 */
  val successful: List<T>,
  /** 失敗的項目資訊 */
  val failed: List<FailedItem> = emptyList()
) : SegmentOutput {

  /** 成功率 */
  val successRate: Double
    get() = if (successful.isEmpty() && failed.isEmpty()) 1.0
    else successful.size.toDouble() / (successful.size + failed.size)

  /** 總項目數 */
  val totalCount: Int get() = successful.size + failed.size

  /** 是否全部成功 */
  val isAllSuccessful: Boolean get() = failed.isEmpty()
}

/**
 * 圖片生成區段的輸出 —— 一或多張生成圖。
 *
 * 之所以包一層而非讓 [destiny.tools.ai.GeneratedImage] 直接實作 [SegmentOutput]：
 * 前者是純資料型別（也用於 vision 回灌），不該背上 workflow 的依賴。
 */
data class ImageOutput(val images: List<GeneratedImage>) : SegmentOutput

/**
 * 失敗的項目資訊
 *
 * 注意：Retry 邏輯由底層 IChatOrchestrator 處理，此處僅記錄最終失敗結果
 */
data class FailedItem(
  /** 項目索引 */
  val index: Int,
  /** 錯誤資訊 */
  val error: Throwable
)
