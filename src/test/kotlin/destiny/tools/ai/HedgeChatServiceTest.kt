package destiny.tools.ai

import destiny.tools.ai.model.FormatSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Characterization tests：pin 住 [HedgeChatService] 對外行為，
 * 抽出 [HedgeOrchestrator] core 的 refactor 前後皆須通過。
 * （現行實作綁 Dispatchers.IO，故用 runBlocking + 即時回應，不依賴 virtual time。）
 */
class HedgeChatServiceTest {

  private val stringSpec: FormatSpec<String> = FormatSpec.of<String>("str", "plain string")

  private val preferred = ProviderModel(Provider.CLAUDE, "claude-x")
  private val fallback = ProviderModel(Provider.OPENAI, "gpt-x")

  private class FakeChat(
    override val provider: Provider,
    val handler: (model: String, chatOptions: ChatOptions) -> Reply<String>?
  ) : IChatCompletion {

    override suspend fun chatComplete(
      model: String, messages: List<Msg>, user: String?, funCalls: Set<IFunctionDeclaration>,
      timeout: Duration, chatOptions: ChatOptions, jsonSchema: JsonSchemaSpec?, maxFunctionCallDepth: Int
    ): Reply<String> = handler(model, chatOptions) ?: Reply.Error.Unknown("null", provider)

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> typedChatComplete(
      model: String, messages: List<Msg>, formatSpec: FormatSpec<T>, json: Json, locale: Locale,
      chatOptions: ChatOptions, postProcessors: List<IPostProcessor>, user: String?,
      funCalls: Set<IFunctionDeclaration>, timeout: Duration, maxFunctionCallDepth: Int
    ): Reply<T>? = handler(model, chatOptions) as Reply<T>?
  }

  private fun config() = HedgeChatService.HedgeConfig(
    preferred = preferred,
    preferredWait = 2.seconds,
    fallbacks = setOf(fallback),
    modelTimeout = 30.seconds,
    user = null,
  )

  @Test
  fun `preferred success is returned`() = runBlocking {
    val service = HedgeChatService(config())
    val result = service.chatComplete(stringSpec, listOf(Msg(Role.USER, "hi")), emptyList()) { provider ->
      FakeChat(provider) { model, _ -> Reply.Normal("from-$provider", null, provider, model) }
    }
    assertEquals("from-CLAUDE", result?.content)
  }

  @Test
  fun `preferred failure falls back`() = runBlocking {
    val service = HedgeChatService(config())
    val result = service.chatComplete(stringSpec, listOf(Msg(Role.USER, "hi")), emptyList()) { provider ->
      FakeChat(provider) { model, _ ->
        if (provider == Provider.CLAUDE) Reply.Error.Busy(provider)
        else Reply.Normal("from-fallback", null, provider, model)
      }
    }
    assertEquals("from-fallback", result?.content)
  }

  @Test
  fun `providerModel temperature and maxTokens override template chatOptions`() = runBlocking {
    val pmWithOverride = preferred.copy(temperature = Temperature(0.2), maxTokens = MaxTokens(777))
    val service = HedgeChatService(config().copy(preferred = pmWithOverride))
    var seenOptions: ChatOptions? = null
    service.chatComplete(
      stringSpec, listOf(Msg(Role.USER, "hi")), emptyList(),
      chatOptionsTemplate = ChatOptions(temperature = Temperature(0.9))
    ) { provider ->
      FakeChat(provider) { model, options ->
        if (provider == Provider.CLAUDE) seenOptions = options
        Reply.Normal("ok", null, provider, model)
      }
    }
    assertEquals(Temperature(0.2), seenOptions?.temperature)
    assertEquals(MaxTokens(777), seenOptions?.maxTokens)
  }

  @Test
  fun `returns null when everyone fails`() = runBlocking {
    val service = HedgeChatService(config())
    val result = service.chatComplete(stringSpec, listOf(Msg(Role.USER, "hi")), emptyList()) { provider ->
      FakeChat(provider) { _, _ -> Reply.Error.Busy(provider) }
    }
    assertNull(result)
  }
}
