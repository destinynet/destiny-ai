package destiny.tools.ai.serializers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * [YearMonth] ⇄ `"yyyy-MM"` 字串。
 *
 * **destiny-ai 專用**：destiny-ai 不依賴 destiny-core（維持 core ⊥ ai / Architecture A），
 * 故不能重用 destiny-core 的 `destiny.tools.serializers.YearMonthSerializer`。此處另放一份，
 * 且**刻意用不同 package**（`destiny.tools.ai.serializers`）——因為 destiny-core-impl 同時依賴
 * core 與 ai，若兩份同 FQN 會在 classpath 撞名。
 *
 * 本序列化器只處理我方 canonical 的 `"yyyy-MM"` 格式（ModelInfo 自身 JSON round-trip）。
 * models.dev 的 `knowledge` 可能是日粒度（`"2025-03-31"`），該轉換 truncate 成 YearMonth 的邏輯
 * 在抓取端（ModelCatalogService）處理，不在此。
 */
object YearMonthSerializer : KSerializer<YearMonth> {
  private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("YearMonth", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: YearMonth) {
    encoder.encodeString(value.format(formatter))
  }

  override fun deserialize(decoder: Decoder): YearMonth =
    YearMonth.parse(decoder.decodeString(), formatter)
}
