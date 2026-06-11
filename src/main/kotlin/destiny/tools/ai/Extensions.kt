/**
 * Extension functions for the destiny-ai mini-framework.
 *
 * NOTE: [suspendFirstNotNullResult] is intentionally declared in package `destiny.tools.ai`
 * (FQN: destiny.tools.ai.suspendFirstNotNullResult) so that destiny-ai stays free of any
 * dependency on destiny-core. It is a copy of the original `destiny.tools.suspendFirstNotNullResult`
 * defined in destiny-core's Extensions.kt; the two have distinct FQNs and do not collide.
 */
package destiny.tools.ai

/**
 * Iterates through the Iterable, applies the given suspendable [transform] function to each element,
 * and returns the first non-null result. If no non-null result is found, returns null.
 */
suspend inline fun <T, R : Any> Iterable<T>.suspendFirstNotNullResult(crossinline transform: suspend (T) -> R?): R? {
  for (element in this) {
    val result = transform(element)
    if (result != null) {
      return result
    }
  }
  return null
}

suspend inline fun <T, R : Any> Sequence<T>.suspendFirstNotNullResult(crossinline transform: suspend (T) -> R?): R? {
  for (element in this) {
    val result = transform(element)
    if (result != null) {
      return result
    }
  }
  return null
}
