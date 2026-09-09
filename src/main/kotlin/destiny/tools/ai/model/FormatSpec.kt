/**
 * Created by smallufo on 2025-04-19.
 */
package destiny.tools.ai.model

import destiny.tools.ai.JsonSchemaSpec
import destiny.tools.ai.ListContainer
import destiny.tools.ai.ListContainerSerializer
import destiny.tools.ai.toJsonSchema
import destiny.tools.ai.getEnumSerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.typeOf


interface FormatSpec<T : Any> {
  val serializer: KSerializer<T>
  val jsonSchema: JsonSchemaSpec
  val kClass: KClass<T>

  /**
   * 內容層的驗證：decode 成功之後再問一次「這份回覆完整嗎」。回 null 表示通過，
   * 回字串就是失敗原因，`typedChatComplete` 會把它變成 [destiny.tools.ai.Reply.Error.DeserializationFailure]。
   *
   * schema 的 `required` 守的是「欄位在不在」，這裡守的是 schema 說不出來的事：
   * map 少了一半的 key、列表比 `@Size` 說的還短、數字加起來不等於總數。
   * 2026-09-09 實跑：沒有這一層時，Gemini 交 4/12 個 domain 一樣是「成功」。
   * 用 [validated] 掛上；預設不驗。
   */
  val validator: (T) -> String? get() = { null }

  companion object {
    /**
     * title pattern '^[a-zA-Z0-9_-]+$'
     */
    inline fun <reified T : Any> of(
      title: String,
      description: String
    ): FormatSpec<T> {
      require(T::class != Unit::class) { "Unit type is not allowed" }
      require(title.matches(Regex("^[a-zA-Z0-9_-]+$"))) { $$"title must match pattern '^[a-zA-Z0-9_-]+$', but was: '$$title'" }

      val kType = typeOf<T>()
      // 頂層集合產得出 {"type":"array"}，但 OpenAI / Claude / Gemini 的 structured output 都要求 root 是 object。
      // 要回陣列請用 ListContainer（root 是帶一個 `array` 欄位的 object，serializer 也接受裸陣列）。
      require(!kType.isSubtypeOf(typeOf<Collection<*>>()) && !kType.isSubtypeOf(typeOf<Array<*>>())) {
        "top-level ${kType} is not accepted by structured-output APIs (root must be an object); wrap it in ListContainer<T>"
      }

      // 關鍵修改：偵測 T 是否為 ListContainer
      val ser: KSerializer<T> = if (kType.classifier == ListContainer::class) {
        // 是 ListContainer，手動建構我們的自訂序列化器
        // 1. 取得 ListContainer<T> 中，泛型 T 的 KSerializer (例如 SimpleUser.serializer())
        val innerType = kType.arguments.first().type
          ?: error("Cannot get generic type argument from ListContainer.")
        val innerTypeSerializer = serializer(innerType)

        // 2. 用它來建立 ListContainerSerializer
        @Suppress("UNCHECKED_CAST")
        ListContainerSerializer(innerTypeSerializer) as KSerializer<T>
      } else {
        // 不是 ListContainer，使用預設的尋找方式
        serializer<T>()
      }

      // ⚠️ 用 kType 而非 T::class —— `KClass` 一拿到手型別參數就沒了，
      // 頂層是 Map / List / ListContainer 時會產出無用甚至誤導的 schema。見 [KType.toJsonSchema]。
      val schema = kType.toJsonSchema(title, description)

      return Impl(ser, schema, T::class)
    }

    @PublishedApi
    internal class Impl<T : Any>(
      override val serializer: KSerializer<T>,
      override val jsonSchema: JsonSchemaSpec,
      override val kClass: KClass<T>,
      override val validator: (T) -> String? = { null },
    ) : FormatSpec<T> {
      /**
       * 三參數版保留給**已內嵌**的呼叫端：`FormatSpec.of` 是 inline，下游模組沒 clean 重編時，
       * 位元組碼裡還是舊的建構子簽名 —— 少了這個就是 NoSuchMethodError（2026-09-09 實際踩到）。
       */
      constructor(serializer: KSerializer<T>, jsonSchema: JsonSchemaSpec, kClass: KClass<T>) :
        this(serializer, jsonSchema, kClass, { null })

      override fun toString() = "FormatSpec(serializer=$serializer, jsonSchema=$jsonSchema)"
    }
  }
}


/**
 * 把 [property] 這個 enum-keyed map 的 schema 收窄到 [keys]：properties 只留這幾個、required 就是這幾個。
 *
 * ## 為什麼收窄的是 properties 而不只是 required
 *
 * 產生器對 `Map<Enum, V>` 的預設是 fail-closed —— 每個 enum key 都 required（見 `JsonTools.handleMapType`
 * 的說明：沒有 required 時，原生 structured output 會讓模型合法地少交一半）。
 * 只想要子集的呼叫端（例如月運只談 12 個 domain 裡的 9 個）就用本函式。
 * 收窄 properties 之後，配上 map 本來就有的 `additionalProperties:false`，
 * 未列入的 key 從「請忽略」變成「填了就不合法」—— 型別是閘門，description 只是請求。
 *
 * ⚠️ 想讓「要哪些 key」只有一個來源：把 key 清單抽成常數，`fieldGuidance` 與本函式共用它。
 *
 * @param property 頂層 properties 裡那個 enum-keyed map 的名字（`@SerialName` 之後的名字）
 * @param keys     要保留的 enum 常數；順序以原 schema 的宣告序為準，不是 [keys] 的順序
 * @throws IllegalArgumentException [property] 不存在、不是 enum-keyed map、[keys] 為空、或含原 schema 沒有的 key
 */
fun <T : Any> FormatSpec<T>.narrowEnumKeys(property: String, keys: Iterable<Enum<*>>): FormatSpec<T> {
  val schema = jsonSchema.schema
  val props = schema["properties"] as? JsonObject
    ?: throw IllegalArgumentException("schema '${jsonSchema.name}' has no properties")
  val node = props[property] as? JsonObject
    ?: throw IllegalArgumentException("property '$property' not found in schema '${jsonSchema.name}'")
  val nodeProps = node["properties"] as? JsonObject
  require(nodeProps != null && node["additionalProperties"] == JsonPrimitive(false)) {
    "property '$property' is not an enum-keyed map (no properties / additionalProperties:false)"
  }

  val wanted: Set<String> = keys.map { getEnumSerialName(it::class, it) }.toSet()
  require(wanted.isNotEmpty()) { "keys must not be empty" }
  val unknown = wanted - nodeProps.keys
  require(unknown.isEmpty()) { "keys $unknown are not keys of '$property' (known: ${nodeProps.keys})" }

  val kept: List<String> = nodeProps.keys.filter { it in wanted }
  val narrowedNode = JsonObject(
    node + mapOf(
      "properties" to JsonObject(nodeProps.filterKeys { it in wanted }),
      "required" to JsonArray(kept.map { JsonPrimitive(it) }),
    )
  )
  val narrowedSchema = JsonObject(schema + ("properties" to JsonObject(props + (property to narrowedNode))))
  return FormatSpec.Companion.Impl(serializer, jsonSchema.copy(schema = narrowedSchema), kClass, validator)
}

/**
 * 掛上內容層的驗證（見 [FormatSpec.validator]）。已有驗證時兩者串接：先跑舊的，通過再跑 [check]。
 *
 * ```kotlin
 * FormatSpec.of<BirthDataReply>("birth_data_reply", "…")
 *   .validated { r -> BirthDataDomain.entries.filterNot { it in r.domains }.takeIf { it.isNotEmpty() }?.let { "missing domains $it" } }
 * ```
 */
fun <T : Any> FormatSpec<T>.validated(check: (T) -> String?): FormatSpec<T> {
  val previous = validator
  return FormatSpec.Companion.Impl(serializer, jsonSchema, kClass) { value -> previous(value) ?: check(value) }
}
