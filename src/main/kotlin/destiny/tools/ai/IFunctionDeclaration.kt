/**
 * Created by smallufo on 2023-12-30.
 */
package destiny.tools.ai

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.withNullability
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.valueParameters

interface IFunctionDeclaration {
  /**
   * @param enum    允許值的封閉清單；空 list 代表不限制。
   * @param minimum 數值下界；null 代表未設限。
   * @param maximum 數值上界；null 代表未設限。
   *
   * ⚠️ 2026-08-27 新增這三個欄位。此前 [Parameter] 只有前四個欄位，於是
   * `@Parameter(enum = [...], minimum = .., maximum = ..)` 讀出來就被丟棄，
   * **從未送達任何 provider** —— 各家的 schema 型別（`InputSchema.Property` 等）
   * 明明有對應欄位，卻永遠是 null。既有宣告（如 `["M", "F"]`）之所以看似有效，
   * 純粹是因為同一份清單在 `description` 裡又寫了一遍。
   *
   * [minimum] / [maximum] 在此已是**正規化後**的值：Kotlin annotation 的參數
   * 不能有 null 預設值，所以 [destiny.tools.ai.Parameter] 用
   * `Int.MIN_VALUE` / `Int.MAX_VALUE` 當「未設定」的 sentinel，
   * 由 [AnnotatedFunctionDeclaration.parameters] 轉成 null。
   * 代價是那兩個極值無法被表達成真正的界限 —— 可接受。
   */
  data class Parameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean,
    val enum: List<String> = emptyList(),
    val minimum: Int? = null,
    val maximum: Int? = null,
    /**
     * [type] == `"array"` 時的元素型別；其餘情況為 null。
     *
     * ⭐ **[enum] 的歸屬由型別決定**：陣列參數的 enum 是**元素**的值域（`items.enum`），
     * 純量參數的 enum 才是它自己的值域。同一個 `@Parameter(enum = [...])` 宣告，
     * 掛在 `List<String>` 上與掛在 `String` 上，落到 schema 的位置不同。
     * 這讓「OR 一組封閉詞彙」（`aspects = [OPPOSITION, TRINE]`）表達得出來，
     * 又不必新增第二個 annotation 欄位。
     */
    val itemType: String? = null,
  )

  val name: String
  val description: String
  val keywords: Array<String>
  val parameters: List<Parameter>
  fun applied(msgs: List<Msg>): Boolean
  fun invoke(parameters: Map<String, Any>): String
  val fullDescriptionForIndexing: String
    get() = "$description . keywords : ${keywords.joinToString(", ")}"
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class FunctionDeclaration(val name: String,
                                     val description: String,
                                     val keywords: Array<String>)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Parameter(
  val description: String,
  val required: Boolean = true,
  val enum: Array<String> = [],
  val minimum: Int = Int.MIN_VALUE,
  val maximum: Int = Int.MAX_VALUE
)


abstract class AnnotatedFunctionDeclaration : IFunctionDeclaration {
  override val name: String
    get() = this::class.annotations.filterIsInstance<FunctionDeclaration>().first().name

  override val description: String
    get() = this::class.annotations.filterIsInstance<FunctionDeclaration>().first().description

  override val keywords: Array<String>
    get() = this::class.annotations.filterIsInstance<FunctionDeclaration>().first().keywords

  abstract val callbackName: String

  override val parameters: List<IFunctionDeclaration.Parameter>
    get() {
      val method = this::class.memberFunctions.first { it.name == callbackName }
      return method.parameters.drop(1).map { param ->
        val annotation = param.findAnnotation<Parameter>()
        IFunctionDeclaration.Parameter(
          param.name ?: "",
          param.type.toJsonSchemaType(),
          annotation?.description ?: "",
          annotation?.required ?: true,
          annotation?.enum?.toList() ?: emptyList(),
          annotation?.minimum?.takeIf { it != Int.MIN_VALUE },
          annotation?.maximum?.takeIf { it != Int.MAX_VALUE },
          param.type.toJsonSchemaItemType(),
        )
      }
    }

  override fun invoke(parameters: Map<String, Any>): String {
    val method = this::class.memberFunctions.first { it.name == callbackName }
    val args = method.valueParameters.map { param ->
      parameters[param.name]?.let { coerce(it, param.type) }
    }.toTypedArray()
    return method.call(this, *args) as String
  }

  /**
   * JSON 值 → callback 參數型別的最小轉換。
   *
   * ⚠️ **模型會送 `3` 給一個 `Double` 參數**，而 JSON 的 `3` 解出來是 `Int`，
   * reflection 的 `call` 直接丟 `IllegalArgumentException: argument type mismatch` ——
   * 使用者只會看到工具無緣無故失敗。2026-08-28 在 `count_eclipses` 的 `maxOrb` 上實際踩到。
   *
   * 只處理數值與 enum：`List<T>` 靠泛型抹除本來就過得去（元素若是 String），
   * 而更完整的處置（`call` → `callBy`，讓缺席的參數落回 Kotlin 預設值）
   * 見 root `docs/plans/2026-08-27-funcall-typed-parameters.md` 的 §3。
   *
   * **非整數值要轉成 Int 時報錯，不默默截斷** —— 靜默的 3.7 → 3 比失敗更難查。
   */
  private fun coerce(value: Any, type: KType): Any {
    val t = type.withNullability(false)
    if (value is Number) {
      return when (t.classifier) {
        Double::class -> value.toDouble()
        Float::class  -> value.toFloat()
        Int::class, Long::class -> {
          val d = value.toDouble()
          require(d == Math.floor(d)) { "expected an integer but got $value" }
          if (t.classifier == Int::class) value.toInt() else value.toLong()
        }
        else -> value
      }
    }
    @Suppress("UNCHECKED_CAST")
    if (value is String) {
      val cls = t.classifier as? KClass<*>
      if (cls != null && cls.java.isEnum) {
        return cls.java.enumConstants.firstOrNull { (it as Enum<*>).name.equals(value, true) }
          ?: throw IllegalArgumentException(
            "\"$value\" is not one of ${cls.java.enumConstants.joinToString("/") { (it as Enum<*>).name }}"
          )
      }
    }
    return value
  }
}

fun Set<IFunctionDeclaration>.toMap(): Map<String, IFunctionDeclaration> {
  return this.associateBy { impl ->
    impl.name
  }
}
