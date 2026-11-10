package destiny.tools.ai

import destiny.tools.ai.model.FormatSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Characterization tests：pin 住 [ResilientChatService] 對外行為，
 * 抽出 [ResilientOrchestrator] core 的 refactor 前後皆須通過。
 */
class ResilientChatServiceTest {

  private val stringSpec: FormatSpec<String> = FormatSpec.of<String>("str", "plain string")

  /** 以 (model, chatOptions) -> Reply<String> 決定回應的 fake impl */
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

  private fun config(vararg pms: ProviderModel, maxTotalAttempts: Int = 2) =
    ResilientConfig(
      providerModels = pms.toSet(),
      modelTimeout = 30.seconds,
      delayBetweenModelLoops = 1.seconds,
      maxTotalAttempts = maxTotalAttempts,
    )

  @Test
  fun `delegates to typedChatComplete and returns Normal`() = runTest {
    val pm = ProviderModel(Provider.OPENAI, "gpt-x")
    val service = ResilientChatService(config(pm))
    val result = service.chatComplete(stringSpec, listOf(Msg(Role.USER, "hi")), emptyList()) { provider ->
      FakeChat(provider) { model, _ -> Reply.Normal("hello", null, provider, model) }
    }
    assertEquals("hello", result?.content)
    assertEquals(Provider.OPENAI, result?.provider)
    assertEquals("gpt-x", result?.model)
  }

  @Test
  fun `providerModel temperature and maxTokens override template chatOptions`() = runTest {
    val pm = ProviderModel(Provider.OPENAI, "gpt-x", temperature = Temperature(0.3), maxTokens = MaxTokens(1234))
    val service = ResilientChatService(config(pm))
    var seenOptions: ChatOptions? = null
    service.chatComplete(
      stringSpec, listOf(Msg(Role.USER, "hi")), emptyList(),
      chatOptionsTemplate = ChatOptions(temperature = Temperature(0.9), topP = TopP(0.8))
    ) { provider ->
      FakeChat(provider) { model, options ->
        seenOptions = options
        Reply.Normal("ok", null, provider, model)
      }
    }
    assertEquals(Temperature(0.3), seenOptions?.temperature)
    assertEquals(MaxTokens(1234), seenOptions?.maxTokens)
    assertEquals(TopP(0.8), seenOptions?.topP)   // 未被 PM 覆蓋的欄位保留 template 值
  }

  @Test
  fun `falls back to healthy provider when one keeps failing`() = runTest {
    val bad = ProviderModel(Provider.XAI, "grok-x")
    val good = ProviderModel(Provider.GEMINI, "gemini-x")
    val service = ResilientChatService(config(bad, good))
    val result = service.chatComplete(stringSpec, listOf(Msg(Role.USER, "hi")), emptyList()) { provider ->
      FakeChat(provider) { model, _ ->
        if (provider == Provider.XAI) Reply.Error.Busy(provider)
        else Reply.Normal("from-gemini", null, provider, model)
      }
    }
    assertEquals("from-gemini", result?.content)
  }

  @Test
  fun `returns null when all models keep failing`() = runTest {
    val pm = ProviderModel(Provider.OPENAI, "gpt-x")
    val service = ResilientChatService(config(pm, maxTotalAttempts = 2))
    val result = service.chatComplete(stringSpec, listOf(Msg(Role.USER, "hi")), emptyList()) { provider ->
      FakeChat(provider) { _, _ -> Reply.Error.Busy(provider) }
    }
    assertNull(result)
  }

  // ─── repairInvalidOutput：驗證不過時帶著錯誤重寫 ───

  /** 記下每一次收到的對話；第一次回驗證不過，之後成功 */
  private class RecordingChat(override val provider: Provider, val firstOriginal: String) : IChatCompletion {
    val seen = mutableListOf<List<Msg>>()
    override suspend fun chatComplete(
      model: String, messages: List<Msg>, user: String?, funCalls: Set<IFunctionDeclaration>,
      timeout: Duration, chatOptions: ChatOptions, jsonSchema: JsonSchemaSpec?, maxFunctionCallDepth: Int
    ): Reply<String> = error("unused")

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> typedChatComplete(
      model: String, messages: List<Msg>, formatSpec: FormatSpec<T>, json: Json, locale: Locale,
      chatOptions: ChatOptions, postProcessors: List<IPostProcessor>, user: String?,
      funCalls: Set<IFunctionDeclaration>, timeout: Duration, maxFunctionCallDepth: Int
    ): Reply<T>? {
      seen += messages
      return (if (seen.size == 1) Reply.Error.DeserializationFailure("validation failed: g1 cites f9", firstOriginal, provider, model, 100, 20)
      else Reply.Normal("fixed", null, provider, model)) as Reply<T>
    }
  }

  private val pm = ProviderModel(Provider.CLAUDE, "c")
  private val ask = listOf(Msg(Role.USER, "write it"))

  @Test
  fun `repair appends the bad reply and the error before the next attempt`() = runTest {
    val chat = RecordingChat(Provider.CLAUDE, "{\"bad\":true}")
    val o = ResilientChatService(config(pm).copy(repairInvalidOutput = true))
      .chatCompleteOrExplain(stringSpec, ask, emptyList()) { chat } as Orchestration.Success
    assertEquals("fixed", o.reply.content)
    assertEquals(ask, chat.seen[0])
    val second = chat.seen[1]
    assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), second.map { it.role })
    assertEquals(Msg(Role.ASSISTANT, "{\"bad\":true}"), second[1])
    val repair = ResilientChatService.repairPrompt("validation failed: g1 cites f9")
    assertEquals(Msg(Role.USER, repair), second[2])
    assertTrue("g1 cites f9" in repair && "validation failed" !in repair)
    // 驗證不過那一次的用量也在
    assertEquals(100, o.attempts.first().usage?.inputTokens)
  }

  @Test
  fun `without repair the same messages are resent`() = runTest {
    val chat = RecordingChat(Provider.CLAUDE, "{}")
    ResilientChatService(config(pm)).chatCompleteOrExplain(stringSpec, ask, emptyList()) { chat }
    assertEquals(listOf(ask, ask), chat.seen)
  }

  @Test
  fun `a blank bad reply is not appended`() = runTest {
    val chat = RecordingChat(Provider.CLAUDE, "  ")
    ResilientChatService(config(pm).copy(repairInvalidOutput = true)).chatCompleteOrExplain(stringSpec, ask, emptyList()) { chat }
    assertEquals(listOf(ask, ask), chat.seen)
  }
}
