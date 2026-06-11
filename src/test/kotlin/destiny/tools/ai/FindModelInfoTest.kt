package destiny.tools.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FindModelInfoTest {

  /** Minimal fake impl exposing a modelInfos map; only the lookup defaults matter here. */
  private val fake = object : IChatCompletion {
    override val provider = Provider.CLAUDE
    override val modelInfos = mapOf(
      "m1" to ModelInfo("m1", ModelPricing(1.0, 2.0))
    )
    override suspend fun chatComplete(
      model: String, messages: List<Msg>, user: String?, funCalls: Set<IFunctionDeclaration>,
      timeout: kotlin.time.Duration, chatOptions: ChatOptions, jsonSchema: JsonSchemaSpec?, maxFunctionCallDepth: Int
    ): Reply<String> = error("not used")
    override suspend fun <T : Any> typedChatComplete(
      model: String, messages: List<Msg>, formatSpec: destiny.tools.ai.model.FormatSpec<T>,
      json: kotlinx.serialization.json.Json, locale: java.util.Locale, chatOptions: ChatOptions,
      postProcessors: List<IPostProcessor>, user: String?, funCalls: Set<IFunctionDeclaration>,
      timeout: kotlin.time.Duration, maxFunctionCallDepth: Int
    ): Reply<T>? = error("not used")
  }

  @Test
  fun `findModelInfo — hit returns the ModelInfo`() {
    assertEquals(2.0, fake.findModelInfo("m1")?.pricing?.output)
  }

  @Test
  fun `findModelInfo — miss returns null`() {
    assertNull(fake.findModelInfo("nope"))
  }

  @Test
  fun `requireModelInfo — miss throws NoSuchModelException`() {
    val ex = assertFailsWith<NoSuchModelException> { fake.requireModelInfo("nope") }
    assertEquals(Provider.CLAUDE, ex.provider)
    assertEquals("nope", ex.modelKey)
  }
}
