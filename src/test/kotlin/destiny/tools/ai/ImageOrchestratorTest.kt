package destiny.tools.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ImageOrchestratorTest {

  private val gemini = ProviderModel(Provider.GEMINI, "gemini-image-x")
  private val openai = ProviderModel(Provider.OPENAI, "gpt-image-x")

  private val png = GeneratedImage("image/png", "aWFtYXBuZw==")

  private class FakeImageGen(
    override val provider: Provider,
    val handler: (model: String, prompt: String, options: ImageOptions) -> Reply<List<GeneratedImage>>
  ) : IImageGeneration {
    override suspend fun generateImage(
      model: String, prompt: String, options: ImageOptions, timeout: Duration
    ): Reply<List<GeneratedImage>> = handler(model, prompt, options)
  }

  private fun resilientConfig(vararg pms: ProviderModel) = ResilientConfig(
    providerModels = pms.toSet(),
    modelTimeout = 30.seconds,
    delayBetweenModelLoops = 1.seconds,
    maxTotalAttempts = 2,
  )

  @Test
  fun `resilient - success returns generated images`() = runTest {
    val service = ResilientImageService(resilientConfig(gemini))
    val result = service.generateImage("a black cat") { provider ->
      FakeImageGen(provider) { _, _, _ -> Reply.Normal(listOf(png), null, provider, "gemini-image-x") }
    }
    assertEquals(listOf(png), result?.content)
    assertEquals(Provider.GEMINI, result?.provider)
  }

  @Test
  fun `resilient - provider without image capability is skipped`() = runTest {
    // OPENAI 的 providerImpl 回 null（該 provider 不支援生圖）→ 跳過，GEMINI 勝出
    val service = ResilientImageService(resilientConfig(gemini, openai))
    val result = service.generateImage("a black cat") { provider ->
      if (provider == Provider.GEMINI) {
        FakeImageGen(provider) { _, _, _ -> Reply.Normal(listOf(png), null, provider, "gemini-image-x") }
      } else {
        null
      }
    }
    assertEquals(Provider.GEMINI, result?.provider)
  }

  @Test
  fun `resilient - options are passed through to impl`() = runTest {
    val service = ResilientImageService(resilientConfig(gemini))
    var seenOptions: ImageOptions? = null
    var seenPrompt: String? = null
    service.generateImage("a black cat", ImageOptions(n = 2, resolution = ImageResolution.R2K)) { provider ->
      FakeImageGen(provider) { _, prompt, options ->
        seenPrompt = prompt
        seenOptions = options
        Reply.Normal(listOf(png), null, provider, "gemini-image-x")
      }
    }
    assertEquals("a black cat", seenPrompt)
    assertEquals(ImageOptions(n = 2, resolution = ImageResolution.R2K), seenOptions)
  }

  @Test
  fun `resilient - returns null when all fail`() = runTest {
    val service = ResilientImageService(resilientConfig(gemini))
    val result = service.generateImage("a black cat") { provider ->
      FakeImageGen(provider) { _, _, _ -> Reply.Error.Busy(provider) }
    }
    assertNull(result)
  }

  @Test
  fun `hedge - preferred success wins`() = runBlocking {
    val config = HedgeConfig(
      preferred = gemini, preferredWait = 2.seconds, fallbacks = setOf(openai),
      modelTimeout = 30.seconds, user = null,
    )
    val service = HedgeImageService(config)
    val result = service.generateImage("a black cat") { provider ->
      FakeImageGen(provider) { model, _, _ -> Reply.Normal(listOf(png), null, provider, model) }
    }
    assertEquals(Provider.GEMINI, result?.provider)
  }

  @Test
  fun `hedge - preferred failure falls back`() = runBlocking {
    val config = HedgeConfig(
      preferred = gemini, preferredWait = 2.seconds, fallbacks = setOf(openai),
      modelTimeout = 30.seconds, user = null,
    )
    val service = HedgeImageService(config)
    val result = service.generateImage("a black cat") { provider ->
      FakeImageGen(provider) { model, _, _ ->
        if (provider == Provider.GEMINI) Reply.Error.Busy(provider)
        else Reply.Normal(listOf(png), null, provider, model)
      }
    }
    assertEquals(Provider.OPENAI, result?.provider)
  }
}
