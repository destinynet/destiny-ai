package destiny.tools.ai

import kotlinx.serialization.Serializable

@Serializable
data class InputSchema(val type: String = "object", val properties: Map<String, Property>, val required: List<String>) {
  @Serializable
  data class Property(
    val type: String,
    /** items schema 用不到說明文字（那屬於參數本身），留 null 讓欄位整個消失 */
    val description: String? = null,
    val enum: List<String>? = null,
    val minimum: Int? = null,
    val maximum: Int? = null,
    /** `type == "array"` 時的元素 schema。只支援一層 array-of-scalar */
    val items: Property? = null,
  )
}

/** JSON Schema 的 `minimum` / `maximum` 只對數值型別有意義 */
internal fun String.isNumericJsonType(): Boolean = this == "integer" || this == "number"

/**
 * 單一參數 → JSON Schema 的一個 property。
 *
 * 三條刻意的規則：
 *
 * 1. **enum 為空時送 null 而非 `[]`。** JSON Schema 裡的空 enum 代表「沒有任何合法值」，
 *    語意與「不限制」相反。各 provider 的 Json 皆設 `explicitNulls = false`，null 會整個欄位消失。
 * 2. **`minimum` / `maximum` 只在數值型別上輸出。** 掛在 string / boolean 上的界限，
 *    validator 會忽略、模型則會被它誤導（`minLength` 才是字串的長度限制，語意不同）。
 *    宣告在非數值參數上時靜默丟棄 —— 那是宣告端的錯，不該汙染送出的 schema。
 * 3. **陣列參數的約束落在 `items` 裡，不在自己身上。** 見
 *    [IFunctionDeclaration.Parameter.itemType]。`enum` 與 `minimum`／`maximum` 同理 ——
 *    掛在陣列自己身上時，`minimum` 的 JSON Schema 語意是「數值下限」，對陣列無意義
 *    （陣列長度是 `minItems`），validator 會忽略而模型會被誤導。
 *    `items` 的 description 留空 —— 說明文字屬於參數本身，複製到元素上只是灌 token。
 */
private fun IFunctionDeclaration.Parameter.toProperty(): InputSchema.Property {
  val closedValues = enum.ifEmpty { null }
  return if (type == "array" && itemType != null) {
    val itemNumeric = itemType.isNumericJsonType()
    InputSchema.Property(
      type, description,
      items = InputSchema.Property(
        itemType, enum = closedValues,
        minimum = minimum.takeIf { itemNumeric },
        maximum = maximum.takeIf { itemNumeric },
      ),
    )
  } else {
    val numeric = type.isNumericJsonType()
    InputSchema.Property(
      type, description, closedValues,
      minimum.takeIf { numeric },
      maximum.takeIf { numeric },
    )
  }
}

/**
 * `List<Parameter>` → JSON Schema 的 `object`。
 *
 * ⚠️ **新增 provider 時請呼叫這個函式，不要再抄一份。**
 * 這段轉換原本在 `toOpenAi` / `toClaude` / `toXai` / `toCohere` 各抄一次，
 * 而 [IFunctionDeclaration.Parameter] 的約束欄位在四份裡全部被漏掉
 * —— 那正是「同一段邏輯抄四份」必然的下場。
 */
fun List<IFunctionDeclaration.Parameter>.toInputSchema(): InputSchema = InputSchema(
  "object",
  associate { p -> p.name to p.toProperty() },
  filter { it.required }.map { it.name },
)
