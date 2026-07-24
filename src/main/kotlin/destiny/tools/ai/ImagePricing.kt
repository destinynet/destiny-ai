/**
 * Created by smallufo on 2026-07-24.
 */
package destiny.tools.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 圖片解析度級距 —— 計價維度的正規化表達（犧牲自由 width×height 換取跨供應商可比性）。
 *
 * @param approxMegapixels 約略 MP 數，供 [ImagePricing.PerMegapixel] 換算。
 */
@Serializable
enum class ImageResolution(val approxMegapixels: Int) {
  /** ≈ 1024² */
  R1K(1),
  /** ≈ 2048² */
  R2K(4),
  /** ≈ 4096² */
  R4K(16),
}

@Serializable
enum class ImageQuality {
  LOW,
  STANDARD,
  HIGH,
}

/** 圖片規格 —— 計價查表的 key，亦為 [ImageOptions] 正規化後的形態。 */
@Serializable
data class ImageSpec(
  val resolution: ImageResolution = ImageResolution.R1K,
  val quality: ImageQuality = ImageQuality.STANDARD,
)

/**
 * Text-to-image 計價 —— 業界（2026-07）三種 scheme 並存，各以一個 subtype 表達：
 *
 * 1. [PerOutputTokens]：token 制（Gemini、OpenAI gpt-image）。表面 per-token，實質
 *    「每張圖 = 依 (resolution, quality) 查表的固定 output token 數」。**事前預估**用此表；
 *    事後精算仍以 [Reply.Normal.outputTokens] × [ModelPricing.output] 為準（回應帶真實 token 數）。
 * 2. [PerImage]：flat per-image 分級（Imagen 4、grok-imagine、DALL·E 3）。
 * 3. [PerMegapixel]：按 MP 計價（BFL FLUX 系列）。
 *
 * 註：models.dev catalog 只有 token 欄位、表達不了 per-image / per-MP（該類 model `cost` 皆為
 * null），故本結構**只存於 code seed、不參與 drift 比對**——與「catalog 未涵蓋 → 純用 seed」
 * 的既有降級哲學一致。
 *
 * tier 用 List 而非 `Map<ImageSpec, *>`：JSON 的 map key 只能是字串，structured key 需開
 * `allowStructuredMapKeys` 且產出的格式醜——list of entries 直接可序列化。
 */
@Serializable
sealed interface ImagePricing {

  /** 單張預估成本（USD）；null = 該規格未登記。 */
  fun estimate(spec: ImageSpec = ImageSpec()): Double?

  @Serializable
  @SerialName("perOutputTokens")
  data class PerOutputTokens(
    /** USD / 1M output tokens（通常同 [ModelPricing.output]） */
    val usdPerMTokens: Double,
    val tokensPerImage: List<Entry>,
  ) : ImagePricing {

    @Serializable
    data class Entry(val spec: ImageSpec, val tokens: Int)

    override fun estimate(spec: ImageSpec): Double? =
      tokensPerImage.firstOrNull { it.spec == spec }?.let { it.tokens * usdPerMTokens / 1_000_000.0 }
  }

  @Serializable
  @SerialName("perImage")
  data class PerImage(
    val tiers: List<Entry>,
  ) : ImagePricing {

    @Serializable
    data class Entry(val spec: ImageSpec, val usd: Double)

    override fun estimate(spec: ImageSpec): Double? =
      tiers.firstOrNull { it.spec == spec }?.usd
  }

  @Serializable
  @SerialName("perMegapixel")
  data class PerMegapixel(
    /** 首 MP 價（USD） */
    val firstMp: Double,
    /** 後續每 MP 價（USD）；預設同 [firstMp]（線性計價） */
    val subsequentMp: Double = firstMp,
  ) : ImagePricing {

    override fun estimate(spec: ImageSpec): Double =
      firstMp + (spec.resolution.approxMegapixels - 1) * subsequentMp
  }
}
