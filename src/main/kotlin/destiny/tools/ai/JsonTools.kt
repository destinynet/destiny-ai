/**
 * Created by smallufo on 2025-04-04.
 */
package destiny.tools.ai

import mu.KotlinLogging
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.full.withNullability
import kotlin.reflect.typeOf

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

fun <T : Any> KClass<T>.toJsonSchema(name: String, description: String? = null): JsonSchemaSpec {
  val kType = this.starProjectedType

  // 如果是 @JvmInline 的 value class，就取出裡面的唯一 property 的型別
  val effectiveType = if (this.isValue) {
    unwrapValueType(this)
  } else {
    kType
  }

  val primitiveType = effectiveType.toJsonSchemaType()

  // 若是 primitive 型別（非 object），直接回傳 primitive schema
  if (primitiveType != "object") {
    val schema = buildJsonObject {
      put("type", primitiveType)
      description?.let { put("description", it) }
    }
    return JsonSchemaSpec(name, description, schema)
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
    description?.let { put("description", it) }
  }

  return JsonSchemaSpec(name, description, schema)
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
    kClass.memberProperties.forEach { property ->
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
      }
    }
  } finally {
    // 離開時從 visited 移除，允許同一類別在不同分支出現
    visited.remove(kClass)
  }
}

// Extension function to add required fields to a JsonObjectBuilder
private fun JsonObjectBuilder.addRequiredFields(kClass: KClass<*>) {
  val requiredProps = kClass.memberProperties
    .filter { !it.returnType.isMarkedNullable }
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
        // 檢查是否循環參照
        if (elementClassifier in visited) {
          put("\$ref", "#/definitions/${elementClassifier.simpleName}")
          put("description", "Circular reference to ${elementClassifier.simpleName}")
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
  // 檢查是否循環參照
  if (objectClass in visited) {
    put("\$ref", "#/definitions/${objectClass.simpleName}")
    put("description", "Circular reference to ${objectClass.simpleName}")
    return
  }

  put("type", "object")
  putJsonObject("properties") {
    processClassProperties(objectClass, visited)
  }
  // Add required fields for the nested object
  addRequiredFields(objectClass)
}
