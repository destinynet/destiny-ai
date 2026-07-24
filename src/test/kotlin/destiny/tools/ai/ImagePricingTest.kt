package destiny.tools.ai

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImagePricingTest {

  /** Gemini 2.5 Flash Image：1290 tokens @ $30/1M ≈ $0.0387 */
  @Test
  fun `perOutputTokens - registered spec estimates from token table`() {
    val pricing = ImagePricing.PerOutputTokens(
      usdPerMTokens = 30.0,
      tokensPerImage = listOf(ImagePricing.PerOutputTokens.Entry(ImageSpec(ImageResolution.R1K), 1290))
    )
    assertEquals(0.0387, pricing.estimate(ImageSpec(ImageResolution.R1K))!!, 1e-9)
    // 預設 spec 即 R1K + STANDARD
    assertEquals(0.0387, pricing.estimate()!!, 1e-9)
  }

  @Test
  fun `perOutputTokens - unregistered spec returns null`() {
    val pricing = ImagePricing.PerOutputTokens(
      usdPerMTokens = 30.0,
      tokensPerImage = listOf(ImagePricing.PerOutputTokens.Entry(ImageSpec(ImageResolution.R1K), 1290))
    )
    assertNull(pricing.estimate(ImageSpec(ImageResolution.R4K)))
    assertNull(pricing.estimate(ImageSpec(ImageResolution.R1K, ImageQuality.HIGH)))
  }

  /** Imagen 4 型：flat per-image 分級 */
  @Test
  fun `perImage - tier lookup`() {
    val pricing = ImagePricing.PerImage(
      tiers = listOf(
        ImagePricing.PerImage.Entry(ImageSpec(ImageResolution.R1K), 0.04),
        ImagePricing.PerImage.Entry(ImageSpec(ImageResolution.R2K), 0.06),
      )
    )
    assertEquals(0.04, pricing.estimate(ImageSpec(ImageResolution.R1K)))
    assertEquals(0.06, pricing.estimate(ImageSpec(ImageResolution.R2K)))
    assertNull(pricing.estimate(ImageSpec(ImageResolution.R4K)))
  }

  /** FLUX 型：首 MP 與後續 MP 不同價 */
  @Test
  fun `perMegapixel - resolution maps to approx megapixels`() {
    val pricing = ImagePricing.PerMegapixel(firstMp = 0.03, subsequentMp = 0.015)
    assertEquals(0.03, pricing.estimate(ImageSpec(ImageResolution.R1K))!!, 1e-9)          // 1 MP
    assertEquals(0.03 + 3 * 0.015, pricing.estimate(ImageSpec(ImageResolution.R2K))!!, 1e-9)   // ≈4 MP
    assertEquals(0.03 + 15 * 0.015, pricing.estimate(ImageSpec(ImageResolution.R4K))!!, 1e-9)  // ≈16 MP
  }

  @Test
  fun `modelPricing with image pricing survives json round-trip`() {
    val json = Json { encodeDefaults = false }
    val original = ModelPricing(
      0.30, 30.0,
      image = ImagePricing.PerOutputTokens(
        usdPerMTokens = 30.0,
        tokensPerImage = listOf(ImagePricing.PerOutputTokens.Entry(ImageSpec(ImageResolution.R1K), 1290))
      )
    )
    val decoded: ModelPricing = json.decodeFromString(json.encodeToString(original))
    assertEquals(original, decoded)
  }

  @Test
  fun `modelPricing without image pricing stays backward compatible`() {
    val json = Json { encodeDefaults = false }
    // 舊格式（無 image 欄位）仍可反序列化
    val decoded: ModelPricing = json.decodeFromString("""{"input":0.3,"output":2.5}""")
    assertEquals(ModelPricing(0.3, 2.5), decoded)
    assertNull(decoded.image)
  }

  @Test
  fun `imageOptions toSpec falls back to defaults`() {
    assertEquals(ImageSpec(ImageResolution.R1K, ImageQuality.STANDARD), ImageOptions().toSpec())
    assertEquals(
      ImageSpec(ImageResolution.R4K, ImageQuality.STANDARD),
      ImageOptions(resolution = ImageResolution.R4K).toSpec()
    )
    assertEquals(
      ImageSpec(ImageResolution.R1K, ImageQuality.HIGH),
      ImageOptions(quality = ImageQuality.HIGH).toSpec()
    )
  }
}
