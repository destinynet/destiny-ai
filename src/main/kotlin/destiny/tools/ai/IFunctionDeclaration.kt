/**
 * Created by smallufo on 2023-12-30.
 */
package destiny.tools.ai

import kotlin.reflect.full.findAnnotation
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
        )
      }
    }

  override fun invoke(parameters: Map<String, Any>): String {
    val method = this::class.memberFunctions.first { it.name == callbackName }
    val args = method.valueParameters.map { param ->
      parameters[param.name]
    }.toTypedArray()
    return method.call(this, *args) as String
  }
}

fun Set<IFunctionDeclaration>.toMap(): Map<String, IFunctionDeclaration> {
  return this.associateBy { impl ->
    impl.name
  }
}
