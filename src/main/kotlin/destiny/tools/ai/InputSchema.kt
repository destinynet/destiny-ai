package destiny.tools.ai

import kotlinx.serialization.Serializable

@Serializable
data class InputSchema(val type: String = "object", val properties: Map<String, Property>, val required: List<String>) {
  @Serializable
  data class Property(
    val type: String,
    val description: String,
    val enum: List<String>? = null,
    val minimum: Int? = null,
    val maximum: Int? = null,
  )
}

/** JSON Schema 的 `minimum` / `maximum` 只對數值型別有意義 */
internal fun String.isNumericJsonType(): Boolean = this == "integer" || this == "number"

/**
 * `List<Parameter>` → JSON Schema 的 `object`。
 *
 * ⚠️ **新增 provider 時請呼叫這個函式，不要再抄一份。**
 * 這段轉換原本在 `toOpenAi` / `toClaude` / `toXai` / `toCohere` 各抄一次，
 * 而 [IFunctionDeclaration.Parameter] 的 `enum` / `minimum` / `maximum` 在四份裡全部被漏掉
 * —— 那正是「同一段邏輯抄四份」必然的下場。
 *
 * 兩條刻意的規則：
 *
 * 1. **enum 為空時送 null 而非 `[]`。** JSON Schema 裡的空 enum 代表「沒有任何合法值」，
 *    語意與「不限制」相反。各 provider 的 Json 皆設 `explicitNulls = false`，null 會整個欄位消失。
 * 2. **`minimum` / `maximum` 只在數值型別上輸出。** 掛在 string / boolean 上的界限，
 *    validator 會忽略、模型則會被它誤導（`minLength` 才是字串的長度限制，語意不同）。
 *    宣告在非數值參數上時靜默丟棄 —— 那是宣告端的錯，不該汙染送出的 schema。
 */
fun List<IFunctionDeclaration.Parameter>.toInputSchema(): InputSchema = InputSchema(
  "object",
  associate { p ->
    val numeric = p.type.isNumericJsonType()
    p.name to InputSchema.Property(
      p.type,
      p.description,
      p.enum.ifEmpty { null },
      p.minimum.takeIf { numeric },
      p.maximum.takeIf { numeric },
    )
  },
  filter { it.required }.map { it.name },
)
