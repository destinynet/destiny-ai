package destiny.tools.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [AspectRatio] 為 provider-agnostic 詞彙，[AspectRatio.ratio] 採 "W:H" 通用記法
 * （flux / ideogram 皆直接吃此字串，非某家專屬），供各 image impl 自行 mapping。
 */
class AspectRatioTest {

  @Test
  fun `ratio strings use canonical W to H notation`() {
    assertEquals("1:1", AspectRatio.SQUARE.ratio)
    assertEquals("16:9", AspectRatio.LANDSCAPE_16_9.ratio)
    assertEquals("9:16", AspectRatio.PORTRAIT_9_16.ratio)
    assertEquals("4:3", AspectRatio.LANDSCAPE_4_3.ratio)
    assertEquals("3:4", AspectRatio.PORTRAIT_3_4.ratio)
    assertEquals("3:2", AspectRatio.LANDSCAPE_3_2.ratio)
    assertEquals("2:3", AspectRatio.PORTRAIT_2_3.ratio)
  }

  @Test
  fun `imageOptions aspectRatio defaults to null - provider decides`() {
    assertNull(ImageOptions().aspectRatio)
    assertEquals(AspectRatio.LANDSCAPE_16_9, ImageOptions(aspectRatio = AspectRatio.LANDSCAPE_16_9).aspectRatio)
  }
}
