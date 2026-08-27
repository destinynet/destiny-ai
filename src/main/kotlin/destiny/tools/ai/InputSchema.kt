package destiny.tools.ai

import kotlinx.serialization.Serializable

@Serializable
data class InputSchema(val type: String = "object", val properties: Map<String, Property>, val required: List<String>) {
  @Serializable
  data class Property(val type: String, val description: String, val enum: List<String>? = null)
}

/**
 * `List<Parameter>` → JSON Schema 的 `object`。
 *
 * ⚠️ **新增 provider 時請呼叫這個函式，不要再抄一份。**
 * 這段轉換原本在 `toOpenAi` / `toClaude` / `toXai` / `toCohere` 各抄一次，
 * 而 [IFunctionDeclaration.Parameter.enum] 在四份裡全部被漏掉 ——
 * 那正是「同一段邏輯抄四份」必然的下場。
 *
 * enum 為空時送 null 而非 `[]`：JSON Schema 裡的空 enum 代表「沒有任何合法值」，
 * 語意與「不限制」相反。各 provider 的 Json 皆設 `explicitNulls = false`，null 會整個欄位消失。
 */
fun List<IFunctionDeclaration.Parameter>.toInputSchema(): InputSchema = InputSchema(
  "object",
  associate { p -> p.name to InputSchema.Property(p.type, p.description, p.enum.ifEmpty { null }) },
  filter { it.required }.map { it.name },
)
