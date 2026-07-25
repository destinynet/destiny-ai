/**
 * Created by smallufo on 2026-07-25.
 */
package destiny.tools.ai.llm

import destiny.tools.ai.llm.Gemini.ResponseContainer.SuccessResponse.UsageMetadata
import kotlin.test.Test
import kotlin.test.assertEquals

class GeminiUsageMetadataTest {

  /**
   * Gemini 的 candidatesTokenCount **不含** thinking tokens，但 thinking 一樣按 output 計費 ——
   * 計費 output = candidates + thoughts。漏加 thoughts 就是系統性低報
   * （AbstractGeminiImpl 生圖路曾因此漏帳，chat 路則一直是對的）。
   */
  @Test
  fun `billedOutputTokens - thoughts 計入計費 output`() {
    val meta = UsageMetadata(promptTokenCount = 287, candidatesTokenCount = 1290, totalTokenCount = 1732, thoughtsTokenCount = 155)
    assertEquals(1445, meta.billedOutputTokens)
  }

  @Test
  fun `billedOutputTokens - 無 thinking 時即 candidatesTokenCount`() {
    val meta = UsageMetadata(promptTokenCount = 287, candidatesTokenCount = 1290, totalTokenCount = 1577, thoughtsTokenCount = null)
    assertEquals(1290, meta.billedOutputTokens)
  }
}
