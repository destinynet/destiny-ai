/**
 * Created by smallufo on 2026-07-25.
 */
package destiny.tools.workflow

import destiny.tools.ai.GeneratedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SegmentOutputTest {

  /** 1×1 透明 png 的 base64（測試用最小圖，不打任何 API）。 */
  private val stubImage = GeneratedImage(
    "image/png",
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
  )

  @Test
  fun `ImageOutput 是 SegmentOutput，可取回圖片`() {
    val output = ImageOutput(listOf(stubImage))

    assertIs<SegmentOutput>(output)
    assertEquals(1, output.images.size)
    assertEquals("image/png", output.images.first().mimeType)
  }

  @Test
  fun `ImageOutput 可承載多張圖`() {
    val output = ImageOutput(listOf(stubImage, stubImage))
    assertEquals(2, output.images.size)
  }
}
