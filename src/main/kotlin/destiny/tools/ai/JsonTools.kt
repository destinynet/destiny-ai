/**
 * Created by smallufo on 2025-04-04.
 */
package destiny.tools.ai

import mu.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability
import kotlin.reflect.typeOf

/**
 * 屬性／類別層級的 schema 說明 —— 反射進 JSON Schema 的 `description` 欄位。
 *
 * 動機：`toJsonSchema` 之前只有 enum 有自動 description（"Enum of X"），欄位語意
 * 只能住在提示詞散文裡 —— 離欄位越遠，LLM 遵循度越差。掛在欄位上隨 schema 走，
 * 語意與欄位不會分家（2026-08-26 的直接案例：`TriggerRule.baseRateTotal` 的
 * occasion-based 語意）。
 *
 * 用法：
 * ```kotlin
 * data class Rule(
 *   @Description("windows where the configuration was in effect (one pass = one occasion)")
 *   val baseRateHits: Int,
 * )
 * ```
 *
 * 掛在 class 上則作為巢狀物件的 description（頂層 class 的 description 仍以
 * [toJsonSchema] 的參數為準，參數為 null 時 fallback 到本 annotation）。
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Description(val value: String)

/**
 * 集合欄位的長度限制 —— 產出 JSON schema 的 `minItems` / `maxItems`。
 *
 * ## 為什麼需要它
 *
 * 「請寫 2~4 條」寫在 KDoc 或提示詞的散文裡，對模型只是**請求**，它可以忽略而且經常忽略。
 * 本專案已有一次實測：某個欄位的 KDoc 寫著「2~4 條」，而實際產出裡**七成的項目超過上限**，
 * 眾數是 5。散文限制與沒有限制的差別，只在人以為有。
 *
 * 掛上本 annotation，限制就進到 schema 本身 —— 與 required 欄位同一個機制：
 * **不照做就交不出合法輸出**，而不是「希望它照做」。
 *
 * ⚠️ 只對 `List` / `Array` 型別的屬性有效；掛在別處會被忽略（schema 不會多出東西）。
 * ⚠️ 它**不會**在反序列化時擋下超長的輸入 —— 那是另一件事，要就在 data class 的
 *    `init` 裡 `require`。本 annotation 管的是「模型知不知道限制」。
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Size(val min: Int = -1, val max: Int = -1)

fun KType.toJsonSchemaType(): String {
  // `String?` 並非 `String` 的 subtype —— 不先剝掉 nullability，所有可為 null 的
  // primitive 都會掉進最後的 `else -> "object"`，被當成巢狀物件反射展開
  // （`String?` → `{"length": int}`、`Int?` / `Boolean?` → `{}`）。
  val t = this.withNullability(false)
  return when {
    t.isSubtypeOf(typeOf<String>())                                       -> "string"
    t.isSubtypeOf(typeOf<Int>()) || t.isSubtypeOf(typeOf<Long>())         -> "integer"
    t.isSubtypeOf(typeOf<Float>()) || t.isSubtypeOf(typeOf<Double>())     -> "number"
    t.isSubtypeOf(typeOf<Boolean>())                                      -> "boolean"
    t.isSubtypeOf(typeOf<List<*>>()) || t.isSubtypeOf(typeOf<Array<*>>()) -> "array"
    t.isSubtypeOf(typeOf<Map<*, *>>())                                    -> "object"
    // Date/Time types
    t.isSubtypeOf(typeOf<java.time.LocalDate>())                          -> "string"
    t.isSubtypeOf(typeOf<java.time.LocalDateTime>())                      -> "string"
    t.isSubtypeOf(typeOf<java.time.ZonedDateTime>())                      -> "string"
    t.isSubtypeOf(typeOf<java.time.OffsetDateTime>())                     -> "string"
    t.isSubtypeOf(typeOf<java.time.Instant>())                            -> "string"
    t.isSubtypeOf(typeOf<java.util.Date>())                               -> "string"

    t.isSubtypeOf(typeOf<java.math.BigInteger>()) ||
      t.isSubtypeOf(typeOf<java.math.BigDecimal>())                       -> "string"

    t.isSubtypeOf(typeOf<Enum<*>>())                                      -> "string"
    else                                                                  -> "object"
  }
}

/**
 * 陣列參數的**元素**型別；非陣列（或取不到型別參數）回 null。
 *
 * 只解一層 —— `List<String>` / `Array<Int>` 這種 array-of-scalar 就是 funCall 參數的
 * 實際需求上限。巢狀陣列與 array-of-object 刻意不支援：真的需要時應該改用巢狀
 * 物件參數，而不是把 schema 產生器擴成通用的樹。
 *
 * ⚠️ 沒有這個函式的話，[toJsonSchemaType] 對 `List<String>` 只會回 `"array"`，
 * 送出去的 schema 就沒有 `items` —— 模型拿不到元素型別提示，也無法對元素下 enum 約束。
 */
fun KType.toJsonSchemaItemType(): String? {
  val t = this.withNullability(false)
  if (!t.isSubtypeOf(typeOf<List<*>>()) && !t.isSubtypeOf(typeOf<Array<*>>())) return null
  val arg = t.arguments.firstOrNull()?.type ?: return null
  return arg.toJsonSchemaType()
}

/**
 * 取得 JSON Schema 的 format 欄位值 (用於日期/時間類型)
 * @return format 字串，若非日期類型則回傳 null
 */
fun KType.toJsonSchemaFormat(): String? {
  val t = this.withNullability(false)
  return when {
    t.isSubtypeOf(typeOf<java.time.LocalDate>())      -> "date"
    t.isSubtypeOf(typeOf<java.time.LocalDateTime>())  -> "date-time"
    t.isSubtypeOf(typeOf<java.time.ZonedDateTime>())  -> "date-time"
    t.isSubtypeOf(typeOf<java.time.OffsetDateTime>()) -> "date-time"
    t.isSubtypeOf(typeOf<java.time.Instant>())        -> "date-time"
    t.isSubtypeOf(typeOf<java.util.Date>())           -> "date-time"
    else                                              -> null
  }
}

/**
 * 在 JsonObjectBuilder 中加入 type 和 format (如果有的話)
 */
private fun JsonObjectBuilder.putTypeAndFormat(kType: KType) {
  put("type", kType.toJsonSchemaType())
  kType.toJsonSchemaFormat()?.let { put("format", it) }
}

private val logger = KotlinLogging.logger { }

/**
 * 取得 enum 值的序列化名稱，優先使用 @SerialName 註解的值
 */
@PublishedApi
internal fun getEnumSerialName(enumClass: KClass<*>, enumValue: Any): String {
  return try {
    // 嘗試取得該 enum 常數對應的 field，檢查是否有 @SerialName 註解
    val field = enumClass.java.getField(enumValue.toString())
    field.getAnnotation(SerialName::class.java)?.value ?: enumValue.toString()
  } catch (e: Exception) {
    enumValue.toString()
  }
}

/**
 * 宣告序的屬性列表 —— primaryConstructor 參數順序優先，非建構子屬性（body 宣告）依字母序附於其後。
 *
 * [memberProperties] 本身是**字母序**。欄位順序影響 LLM 的生成順序 —— autoregressive
 * 模型依 schema 的 properties 順序產出欄位，字母序讓「先寫對照組、結論最後寫」這類
 * 順序要求只能靠欄位名碰巧排前面（`RetrospectiveReport.baseline` 曾經就是這樣活著的）。
 * 改依宣告序之後，**DTO 作者排的欄位順序就是 LLM 的生成順序**，順序成為可設計的東西。
 */
@PublishedApi
internal fun KClass<*>.orderedProperties(): List<KProperty1<out Any, *>> {
  val ctorOrder: Map<String, Int> = primaryConstructor?.parameters
    ?.mapIndexedNotNull { i, p -> p.name?.let { it to i } }?.toMap() ?: emptyMap()
  return memberProperties.sortedWith(compareBy({ ctorOrder[it.name] ?: Int.MAX_VALUE }, { it.name }))
}

fun <T : Any> KClass<T>.toJsonSchema(name: String, description: String? = null): JsonSchemaSpec {
  val kType = this.starProjectedType

  // 如果是 @JvmInline 的 value class，就取出裡面的唯一 property 的型別
  val effectiveType = if (this.isValue) {
    unwrapValueType(this)
  } else {
    kType
  }

  val primitiveType = effectiveType.toJsonSchemaType()

  // description 參數優先；未給時 fallback 到 class 上的 @Description
  val effectiveDescription = description ?: this.findAnnotation<Description>()?.value

  // 若是 primitive 型別（非 object），直接回傳 primitive schema
  if (primitiveType != "object") {
    val schema = buildJsonObject {
      put("type", primitiveType)
      effectiveDescription?.let { put("description", it) }
    }
    return JsonSchemaSpec(name, effectiveDescription, schema)
  }


  // 否則當作複合型別（如 data class）處理
  // 使用 visited set 追蹤遞迴路徑，偵測循環參照
  val visited = mutableSetOf<KClass<*>>()
  val schema = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
      processClassProperties(this@toJsonSchema, visited)
    }
    addRequiredFields(this@toJsonSchema)
    effectiveDescription?.let { put("description", it) }
  }

  return JsonSchemaSpec(name, effectiveDescription, schema)
}

fun unwrapValueType(kClass: KClass<*>): KType {
  val visited = mutableSetOf<KClass<*>>()
  var currentClass = kClass

  while (currentClass.isValue) {
    if (!visited.add(currentClass)) {
      logger.warn { "Detected value class cycle at ${currentClass.qualifiedName}, fallback to string" }
      return typeOf<String>()
    }

    val innerProp = currentClass.memberProperties.firstOrNull()
      ?: error("Value class ${currentClass.simpleName} has no properties")

    val nextType = innerProp.returnType
    val nextClassifier = nextType.classifier
    if (nextClassifier is KClass<*>) {
      currentClass = nextClassifier
    } else {
      return nextType
    }
  }
  return currentClass.starProjectedType
}


inline fun <reified K : Enum<K>, reified V> toEnumMapJsonSchema(
  name: String,
  description: String? = null
): JsonSchemaSpec {
  val keyClass = K::class
  val valueType = typeOf<V>()

  val schema = buildJsonObject {
    put("type", "object")
    put("description", description ?: "Map with keys from enum ${keyClass.simpleName}. Note : only return mentioned enum keys, Please ignore un-mentioned keys.")

    putJsonObject("properties") {
      keyClass.java.enumConstants.forEach { enumVal ->
        val serialName = getEnumSerialName(keyClass, enumVal)
        putJsonObject(serialName) {
          put("type", valueType.toJsonSchemaType())
          valueType.toJsonSchemaFormat()?.let { put("format", it) }
        }
      }
    }

    putJsonArray("required") {
      keyClass.java.enumConstants.forEach { enumVal ->
        add(JsonPrimitive(getEnumSerialName(keyClass, enumVal)))
      }
    }

    put("additionalProperties", JsonPrimitive(false))
  }

  return JsonSchemaSpec(name, description, schema)
}

// Extension function to process class properties recursively
private fun JsonObjectBuilder.processClassProperties(kClass: KClass<*>, visited: MutableSet<KClass<*>>) {
  // 加入 visited set，如果已存在表示循環參照
  if (!visited.add(kClass)) {
    logger.warn { "Detected circular reference at ${kClass.qualifiedName}, using \$ref placeholder" }
    return
  }

  try {
    kClass.orderedProperties().forEach { property ->
      val propName = property.findAnnotation<SerialName>()?.value ?: property.name
      putJsonObject(propName) {
        // 同 [toJsonSchemaType] 的理由：先剝掉 nullability，否則 `List<X>?` / `Map<K,V>?`
        // 會漏到 nested-object 分支被反射展開。是否 required 另由 [addRequiredFields] 依
        // `isMarkedNullable` 判定，不受此處影響。
        val propertyType = property.returnType.withNullability(false)

        val classifier = propertyType.classifier
        // 防禦性處理 generic / star-projection
        if (classifier !is KClass<*>) return@putJsonObject

        // Handle Map types
        if (propertyType.isSubtypeOf(typeOf<Map<*, *>>())) {
          handleMapType(propertyType, visited)
        }
        // Handle List/Array types
        else if (propertyType.isSubtypeOf(typeOf<List<*>>()) || propertyType.isSubtypeOf(typeOf<Array<*>>())) {
          handleCollectionType(propertyType, visited)
          // 長度限制只有掛在集合屬性上才有意義 —— 掛在別處靜默忽略（見 [Size] 的 KDoc）
          property.findAnnotation<Size>()?.also { sz ->
            if (sz.min >= 0) put("minItems", sz.min)
            if (sz.max >= 0) put("maxItems", sz.max)
          }
        }
        // Handle Enum types
        else if (propertyType.classifier is KClass<*> && (propertyType.classifier as KClass<*>).java.isEnum) {
          handleEnumType(propertyType.classifier as KClass<*>)
        }
        // Handle nested object types
        else if (propertyType.toJsonSchemaType() == "object" && propertyType.classifier is KClass<*>) {
          handleObjectType(propertyType.classifier as KClass<*>, visited)
        }
        // Handle primitive types (including date/time with format)
        else {
          putTypeAndFormat(propertyType)
        }

        // 屬性層級的 @Description 最後放 —— 蓋過 handleEnumType 的 "Enum of X" 與
        // handleObjectType 的 class 層級 description（就近者勝）
        property.findAnnotation<Description>()?.value?.also { put("description", it) }
      }
    }
  } finally {
    // 離開時從 visited 移除，允許同一類別在不同分支出現
    visited.remove(kClass)
  }
}

/**
 * required = **非 nullable 且無預設值**。
 *
 * 2026-08-26 前只看 nullability：`memberProperties` 看不到預設值，於是
 * `val tiedWith: List<String> = emptyList()` 這種欄位也被列為必填，逼 LLM 每次都吐空陣列。
 * `primaryConstructor.parameters` 的 [kotlin.reflect.KParameter.isOptional] 看得到 ——
 * 有預設值的欄位漏填時 kotlinx 反序列化會自動補預設，本來就不必逼。
 * 真正的閘門欄位（如 `TriggerRule` 的四個計數）沒有預設值，不受影響。
 * 非建構子屬性維持舊規則（非 nullable → required）。
 */
private fun JsonObjectBuilder.addRequiredFields(kClass: KClass<*>) {
  val optionalParams: Set<String> = kClass.primaryConstructor?.parameters
    ?.filter { it.isOptional }?.mapNotNull { it.name }?.toSet() ?: emptySet()

  val requiredProps = kClass.orderedProperties()
    .filter { !it.returnType.isMarkedNullable && it.name !in optionalParams }
    .map { it.findAnnotation<SerialName>()?.value ?: it.name }

  if (requiredProps.isNotEmpty()) {
    putJsonArray("required") {
      requiredProps.forEach { add(JsonPrimitive(it)) }
    }
  }
}

// Handle Map type properties, including nested collections and objects
private fun JsonObjectBuilder.handleMapType(mapType: KType, visited: MutableSet<KClass<*>>) {
  put("type", "object")
  val keyType = mapType.arguments.getOrNull(0)?.type
  val valueType = mapType.arguments.getOrNull(1)?.type

  if (keyType?.classifier is KClass<*> && (keyType.classifier as KClass<*>).java.isEnum) {
    val enumClass = keyType.classifier as KClass<*>
    val enumValues = enumClass.java.enumConstants

    put("description", "Map with keys from ${enumClass.simpleName} enum. Note : only return mentioned enum keys, Please ignore un-mentioned keys.")
    put("additionalProperties", JsonPrimitive(false))
    putJsonObject("properties") {
      enumValues.forEach { enumValue ->
        val serialName = getEnumSerialName(enumClass, enumValue)
        putJsonObject(serialName) {
          addValueTypeSchema(valueType, visited)
        }
      }
    }
  } else {
    putJsonObject("additionalProperties") {
      addValueTypeSchema(valueType, visited)
    }
  }
}

private fun JsonObjectBuilder.addValueTypeSchema(nullableValueType: KType?, visited: MutableSet<KClass<*>>) {
  val valueType = nullableValueType?.withNullability(false)
  if (valueType != null) {
    val valueClassifier = valueType.classifier
    when {
      valueType.isSubtypeOf(typeOf<List<*>>()) || valueType.isSubtypeOf(typeOf<Array<*>>()) ->
        handleCollectionType(valueType, visited)

      valueType.toJsonSchemaType() == "object" && valueClassifier is KClass<*>              ->
        handleObjectType(valueClassifier, visited)

      // 同 [handleCollectionType] 的理由：Map 的值若是 enum，不攔就會丟失值域約束
      valueClassifier is KClass<*> && valueClassifier.java.isEnum                           ->
        handleEnumType(valueClassifier)

      else                                                                                  ->
        putTypeAndFormat(valueType)
    }
  } else {
    put("type", "string")
  }
}

// Handle Collection type properties (List, Array)
private fun JsonObjectBuilder.handleCollectionType(collectionType: KType, visited: MutableSet<KClass<*>>) {
  put("type", "array")

  // Add items schema based on the collection element type
  val elementType = collectionType.arguments.firstOrNull()?.type?.withNullability(false)
  if (elementType != null) {
    val elementClassifier = elementType.classifier
    putJsonObject("items") {
      if (elementType.toJsonSchemaType() == "object" && elementClassifier is KClass<*>) {
        // 循環參照：不展開、也不發 $ref —— 整份 schema 從不產出 definitions 區，
        // `#/definitions/X` 是指向不存在位置的懸空指標（LLM 多半容忍，嚴格驗證器不會）。
        // 改發一個帶說明的裸 object，讓模型知道「結構同外層的同名物件」。
        if (elementClassifier in visited) {
          put("type", "object")
          put("description", "Recursive ${elementClassifier.simpleName}: same structure as the enclosing ${elementClassifier.simpleName} object")
        } else {
          // It's a list of objects, detail the object structure
          put("type", "object")
          putJsonObject("properties") {
            processClassProperties(elementClassifier, visited)
          }
          // Add required fields for the item class
          addRequiredFields(elementClassifier)
        }
      }
      // enum 元素：`toJsonSchemaType()` 對 enum 回 "string"，若不先攔就會掉到下面的
      // `putTypeAndFormat` 而**丟失值域約束** —— `List<EventType>` 會變成裸的
      // `{"type":"string"}`，模型可以寫出不存在的列舉值。純量欄位走
      // `processClassProperties` 的 enum 分支不受影響，只有集合元素與 Map 值有這個洞。
      else if (elementClassifier is KClass<*> && elementClassifier.java.isEnum) {
        handleEnumType(elementClassifier)
      } else {
        // Simple type (including date/time with format)
        putTypeAndFormat(elementType)
      }
    }
  }
}

// Handle Enum type properties
private fun JsonObjectBuilder.handleEnumType(enumClass: KClass<*>) {
  put("type", "string")
  put("description", "Enum of ${enumClass.simpleName}")
  putJsonArray("enum") {
    enumClass.java.enumConstants.forEach { enumValue ->
      add(JsonPrimitive(getEnumSerialName(enumClass, enumValue)))
    }
  }
}

// Handle Object type properties
private fun JsonObjectBuilder.handleObjectType(objectClass: KClass<*>, visited: MutableSet<KClass<*>>) {
  // 循環參照：同 handleCollectionType —— 不發懸空 $ref，發帶說明的裸 object
  if (objectClass in visited) {
    put("type", "object")
    put("description", "Recursive ${objectClass.simpleName}: same structure as the enclosing ${objectClass.simpleName} object")
    return
  }

  put("type", "object")
  // class 層級的 @Description 作為巢狀物件的說明；屬性層級的 @Description 在外層後放、就近者勝
  objectClass.findAnnotation<Description>()?.value?.also { put("description", it) }
  putJsonObject("properties") {
    processClassProperties(objectClass, visited)
  }
  // Add required fields for the nested object
  addRequiredFields(objectClass)
}
